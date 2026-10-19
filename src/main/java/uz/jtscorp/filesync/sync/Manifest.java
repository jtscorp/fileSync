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

    /**
     * type is 'f' (file), 'd' (directory) or 'l' (symbolic link); directories and links have size 0.
     * {@code hddModified} is the backup copy's own (not followed) modified time in epoch millis:
     * it is what tells a same-size edit on the backup from the copy both sides last held.
     */
    public record Entry(char type, long size, long hddModified) {

        /** Same thing as {@code other} apart from the time -- what "same on both sides" can mean. */
        boolean sameShape(Entry other) {
            return other != null && type == other.type && size == other.size;
        }
    }

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
            // Split on \n only: readAllLines would also split on a bare CR, which is legal in a name.
            List<String> lines = List.of(Files.readString(file(hddRoot), StandardCharsets.UTF_8).split("\n", -1));
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
                if (parts.length != 4 || parts[0].length() != 1 || "fdl".indexOf(parts[0].charAt(0)) < 0) {
                    return Optional.empty();
                }
                entries.put(unescape(parts[3]), new Entry(parts[0].charAt(0), Long.parseLong(parts[1]),
                        Long.parseLong(parts[2])));
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
                sb.append(e.type()).append('\t').append(e.size()).append('\t').append(e.hddModified())
                        .append('\t').append(escape(path)).append('\n'));

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
        // Another machine's or another folder's record is not our last synced state: carried
        // into ours, the next Check would read its entries as "deleted on the main side".
        Optional<Manifest> own = previous.filter(p -> p.belongsTo(laptopRoot.toString(), host));
        IgnoreRules rules = IgnoreRules.parse(ignores);
        // Unreadable places are simply not recorded; RiskScanner reports them.
        Map<String, Entry> laptop = walk(laptopRoot, "", rules).entries();
        Map<String, Entry> hdd = walk(hddRoot, "", rules).entries();
        Map<String, Entry> entries = new TreeMap<>();
        laptop.forEach((path, entry) -> {
            // Type and size only: Unison does not carry modified times across. The record keeps the hdd time.
            if (entry.sameShape(hdd.get(path))) {
                entries.put(path, hdd.get(path));
            }
        });
        for (String carried : carryOver) {
            // The walk cannot vouch for a skipped path (same size is not same content), so
            // it is dropped even when there is no previous record to put back.
            entries.keySet().removeIf(p -> underOrEqual(p, carried));
            own.ifPresent(prev -> prev.entries().forEach((p, e) -> {
                if (underOrEqual(p, carried)) {
                    entries.put(p, e);
                }
            }));
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

    static boolean underOrEqual(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + "/");
    }

    /** What a walk found, and the places ("" = the root itself) it could not read and so says nothing about. */
    record Walk(NavigableMap<String, Entry> entries, Set<String> unreadable) {

        /** Everything strictly below {@code path}; '0' is the character after '/'. */
        SortedMap<String, Entry> below(String path) {
            return entries.subMap(path + "/", path + "0");
        }

        /** True when an unreadable place is at, above or below {@code path}. */
        boolean touchesUnreadable(String path) {
            return unreadable.stream().anyMatch(u -> u.isEmpty() || underOrEqual(path, u) || underOrEqual(u, path));
        }
    }

    /**
     * Walks {@code root} from {@code below} ("" = the whole tree) down; paths are relative to
     * {@code root}. The root itself is followed (a drive is often mounted through a link), links
     * inside the tree are not. {@code .filesync} at the top is never recorded, whatever the rules.
     */
    static Walk walk(Path root, String below, IgnoreRules rules) throws IOException {
        NavigableMap<String, Entry> found = new TreeMap<>();
        Set<String> unreadable = new HashSet<>();
        Path real;
        try {
            real = root.toRealPath();
        } catch (IOException e) {
            real = null;
        }
        if (real == null || !Files.isDirectory(real)) {
            unreadable.add("");
            return new Walk(found, unreadable);
        }
        Path top = real;
        Files.walkFileTree(below.isEmpty() ? top : top.resolve(below), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(top)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = relative(top, dir);
                if (rel.equals(FOLDER) || rules.matches(rel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                found.put(rel, new Entry('d', 0, attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = relative(top, file);
                if (!rules.matches(rel)) {
                    long modified = attrs.lastModifiedTime().toMillis();
                    found.put(rel, attrs.isSymbolicLink() ? new Entry('l', 0, modified)
                            : new Entry('f', attrs.size(), modified));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // An unopenable directory lands here before preVisitDirectory, hence the rules here too.
                String rel = relative(top, file);
                if (rel.isEmpty() || !rules.matches(rel)) {
                    unreadable.add(rel);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                // The default rethrows; a listing that broke off midway is one more unknown place.
                if (exc != null) {
                    unreadable.add(relative(top, dir));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new Walk(found, unreadable);
    }

    private static String relative(Path root, Path p) {
        // Join the name elements: a backslash is an ordinary character in a Linux file name.
        StringJoiner joined = new StringJoiner("/");
        root.relativize(p).forEach(part -> joined.add(part.toString()));
        return joined.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
                .replace("\r", "\\r");
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
                case 'r' -> sb.append('\r');
                default -> throw new IllegalArgumentException("bad escape");
            }
        }
        return sb.toString();
    }
}
