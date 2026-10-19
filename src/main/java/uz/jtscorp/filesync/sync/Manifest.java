package uz.jtscorp.filesync.sync;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/**
 * The last synced state, kept on the backup side: when, and which paths were identical on
 * both sides. A later Check reads it to tell "deleted on the main side" from "new on the
 * backup side" when Unison's own archive is missing.
 */
public record Manifest(String profile, String laptopRoot, String host, Instant lastSync,
                       Map<String, Entry> entries) {

    /** type is 'f' (file), 'd' (directory) or 'l' (symbolic link); directories and links have size 0. */
    public record Entry(char type, long size) {}

    private static final String FIRST_LINE = "# filesync-manifest 1";
    private static final String FOLDER = ".filesync";

    public Manifest {
        // Sorted, so the written file is the same for the same state.
        entries = Collections.unmodifiableMap(new TreeMap<>(entries));
    }

    public static Path file(Path hddRoot) {
        return hddRoot.resolve(FOLDER).resolve("manifest.tsv");
    }

    /** Anything wrong with the file reads as "no manifest": the caller then just has less to go on. */
    public static Optional<Manifest> read(Path hddRoot) {
        try {
            List<String> lines = Files.readAllLines(file(hddRoot), StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(FIRST_LINE)) {
                return Optional.empty();
            }
            Map<String, String> header = new HashMap<>();
            Map<String, Entry> entries = new TreeMap<>();
            for (String line : lines.subList(1, lines.size())) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("# ")) {
                    int eq = line.indexOf(" = ");
                    if (eq < 0) {
                        return Optional.empty();
                    }
                    header.put(line.substring(2, eq), unescape(line.substring(eq + 3)));
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 3 || parts[0].length() != 1 || "fdl".indexOf(parts[0].charAt(0)) < 0) {
                    return Optional.empty();
                }
                entries.put(unescape(parts[2]), new Entry(parts[0].charAt(0), Long.parseLong(parts[1])));
            }
            String profile = header.get("profile");
            String laptopRoot = header.get("laptopRoot");
            String host = header.get("host");
            String lastSync = header.get("lastSync");
            if (profile == null || laptopRoot == null || host == null || lastSync == null) {
                return Optional.empty();
            }
            return Optional.of(new Manifest(profile, laptopRoot, host, Instant.parse(lastSync), entries));
        } catch (IOException | RuntimeException e) {
            // RuntimeException: bad number, bad instant, bad escape -- all mean "malformed".
            return Optional.empty();
        }
    }

    public void write(Path hddRoot) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(FIRST_LINE).append('\n');
        sb.append("# profile = ").append(escape(profile)).append('\n');
        sb.append("# laptopRoot = ").append(escape(laptopRoot)).append('\n');
        sb.append("# host = ").append(escape(host)).append('\n');
        sb.append("# lastSync = ").append(lastSync).append('\n');
        entries.forEach((path, e) ->
                sb.append(e.type()).append('\t').append(e.size()).append('\t').append(escape(path)).append('\n'));

        Path target = file(hddRoot);
        Files.createDirectories(target.getParent());
        Path tmpFile = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try {
            Files.writeString(tmpFile, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmpFile);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Records a path only when both sides hold the same thing there, because only then is it
     * "identical as of now". Paths in {@code carryOver} (and below) keep their previous entry
     * instead: the sync skipped them, so the old record is still the truth.
     */
    public static Manifest capture(Path laptopRoot, Path hddRoot, List<String> ignores, String profile,
                                   String host, Instant now, Optional<Manifest> previous,
                                   Set<String> carryOver) throws IOException {
        IgnoreRules rules = IgnoreRules.parse(ignores);
        Map<String, Entry> laptop = walk(laptopRoot, rules);
        Map<String, Entry> hdd = walk(hddRoot, rules);
        Map<String, Entry> entries = new TreeMap<>();
        laptop.forEach((path, entry) -> {
            if (entry.equals(hdd.get(path))) {
                entries.put(path, entry);
            }
        });
        if (previous.isPresent()) {
            for (String carried : carryOver) {
                entries.keySet().removeIf(p -> underOrEqual(p, carried));
                previous.get().entries().forEach((p, e) -> {
                    if (underOrEqual(p, carried)) {
                        entries.put(p, e);
                    }
                });
            }
        }
        return new Manifest(profile, laptopRoot.toString(), host, now, entries);
    }

    public boolean belongsTo(String laptopRoot, String host) {
        return this.laptopRoot.equals(laptopRoot) && this.host.equals(host);
    }

    public static String localHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException | RuntimeException e) {
            return "unknown";
        }
    }

    private static boolean underOrEqual(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + "/");
    }

    private static Map<String, Entry> walk(Path root, IgnoreRules rules) throws IOException {
        Map<String, Entry> found = new HashMap<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = relative(root, dir);
                // Never record our own folder, whatever the ignore rules say.
                if (rel.equals(FOLDER) || rules.matches(rel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                found.put(rel, new Entry('d', 0));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = relative(root, file);
                if (rules.matches(rel)) {
                    return FileVisitResult.CONTINUE;
                }
                found.put(rel, attrs.isSymbolicLink()
                        ? new Entry('l', 0)
                        : new Entry('f', attrs.size()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // Unreadable places are simply not recorded; RiskScanner reports them.
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= s.length()) {
                throw new IllegalArgumentException("dangling escape");
            }
            switch (s.charAt(i)) {
                case '\\' -> sb.append('\\');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                default -> throw new IllegalArgumentException("bad escape");
            }
        }
        return sb.toString();
    }
}
