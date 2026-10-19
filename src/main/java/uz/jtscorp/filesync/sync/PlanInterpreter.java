package uz.jtscorp.filesync.sync;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.config.SyncMode;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.sync.SyncAction.Origin;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Reads Unison's plan through the profile's sync mode and the backup-side manifest. Without
 * an archive Unison takes "missing on Main" for "new on Backup" and copies stale folders back;
 * this decides when such a row is really "delete from Backup". A wrong yes deletes data that
 * has no other copy, so every doubt (no manifest, a size off by one byte, a folder it could
 * not read) leaves Unison's row as it is.
 */
public final class PlanInterpreter {

    /** {@code notes} maps a path to a sentence saying why its row reads the way it does. */
    public record Interpretation(List<SyncAction> actions, List<RiskFlag> flags, List<Rename> renames,
                                 Map<String, String> notes) {}

    /** Display only: Unison still deletes {@code from} and writes {@code to}. */
    public record Rename(String from, String to, Direction direction) {}

    /** More lost places than this is a failing drive rather than a few stray deletes. */
    private static final int LOST_MASS_THRESHOLD = 50;
    private static final String FOLDER = ".filesync";

    private PlanInterpreter() {}

    /** The hdd tree as walked now; {@code unreadable} are places the walk says nothing about. */
    private record Tree(NavigableMap<String, Manifest.Entry> entries, Set<String> unreadable) {
        /** Everything strictly below {@code path}; '0' is the character after '/'. */
        SortedMap<String, Manifest.Entry> below(String path) {
            return entries.subMap(path + "/", path + "0");
        }

        boolean touchesUnreadable(String path) {
            // "" stands for the root itself, i.e. the whole tree.
            return unreadable.stream().anyMatch(u -> u.isEmpty() || underOrEqual(path, u) || underOrEqual(u, path));
        }
    }

    public static Interpretation interpret(List<SyncAction> parsed, SyncMode mode, Optional<Manifest> manifest,
                                           Path laptopRoot, Path hddRoot, List<String> ignores) throws IOException {
        IgnoreRules rules = IgnoreRules.parse(ignores);
        // Another machine's or another folder's manifest would call our files "deleted" (spec 5.5).
        Optional<Manifest> own = manifest.filter(m -> m.belongsTo(laptopRoot.toString(), Manifest.localHost()));
        // The rules may have changed since the manifest was written; an ignored path is no evidence.
        Map<String, Manifest.Entry> known = new TreeMap<>();
        own.ifPresent(m -> m.entries().forEach((p, e) -> {
            if (!ignored(p, rules)) {
                known.put(p, e);
            }
        }));
        Tree hdd = own.isPresent() ? walk(hddRoot, hddRoot, rules) : new Tree(new TreeMap<>(), Set.of());

        List<SyncAction> actions = new ArrayList<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (SyncAction a : parsed) {
            boolean backupOnly = a.actionType() == ActionType.NEW && a.direction() == Direction.HDD_TO_LAPTOP
                    && a.origin() == Origin.UNISON;
            if (backupOnly && mode == SyncMode.TWO_WAY_PRUNE) {
                actions.add(backupDelete(a, Origin.MODE));
                note(notes, a.relativePath(), Texts.t("note.mode.prune"));
            } else if (backupOnly && mode == SyncMode.TWO_WAY && own.isPresent()) {
                switch (deletedOnMain(a.relativePath(), known, hdd, laptopRoot)) {
                    case YES -> {
                        actions.add(backupDelete(a, Origin.MANIFEST));
                        note(notes, a.relativePath(), Texts.t("note.manifest.deletedOnMain"));
                    }
                    case ADDED_INSIDE -> {
                        actions.add(a);
                        note(notes, a.relativePath(), Texts.t("note.manifest.addedInside"));
                    }
                    case NO -> actions.add(a);
                }
            } else {
                actions.add(a);
            }
        }

        List<RiskFlag> flags = own.isPresent() ? lostOnBackup(known, hdd, laptopRoot) : List.of();
        if (own.isPresent()) {
            typeChanges(actions, known, laptopRoot, hddRoot, notes);
        }
        List<Rename> renames = renames(actions, laptopRoot, hddRoot, rules);
        return new Interpretation(List.copyOf(actions), flags, renames, Collections.unmodifiableMap(notes));
    }

    private static SyncAction backupDelete(SyncAction a, Origin origin) {
        return new SyncAction(a.relativePath(), Direction.LAPTOP_TO_HDD, ActionType.DELETED, origin);
    }

    private static void note(Map<String, String> notes, String path, String sentence) {
        notes.merge(path, sentence, (old, added) -> old + " · " + added);
    }

    private enum Verdict { YES, NO, ADDED_INSIDE }

    /** Rule 2: the backup copy is exactly what both sides held at the last sync. */
    private static Verdict deletedOnMain(String path, Map<String, Manifest.Entry> known, Tree hdd,
                                         Path laptopRoot) {
        Manifest.Entry then = known.get(path);
        Manifest.Entry now = hdd.entries().get(path);
        // Main must still lack it: if something is there now, deleting the backup copy is no longer
        // "propagating Main's delete".
        if (then == null || now == null || hdd.touchesUnreadable(path)
                || Files.exists(laptopRoot.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
            return Verdict.NO;
        }
        if (then.type() != 'd') {
            return then.equals(now) ? Verdict.YES : Verdict.NO;
        }
        if (now.type() != 'd') {
            return Verdict.NO;
        }
        boolean addedInside = false;
        for (Map.Entry<String, Manifest.Entry> inside : hdd.below(path).entrySet()) {
            Manifest.Entry recorded = known.get(inside.getKey());
            if (recorded == null) {
                addedInside = true;
            } else if (!recorded.equals(inside.getValue())) {
                return Verdict.NO;
            }
        }
        return addedInside ? Verdict.ADDED_INSIDE : Verdict.YES;
    }

    /** Rule 3: on the backup at the last sync, gone now, still on Main -- maybe a failing drive. */
    private static List<RiskFlag> lostOnBackup(Map<String, Manifest.Entry> known, Tree hdd, Path laptopRoot) {
        List<String> lost = new ArrayList<>();
        Set<String> lostSet = new HashSet<>();
        for (String path : known.keySet()) {
            // A lost folder is one flag; the ancestor test also spares a stat per file inside it.
            if (hdd.entries().containsKey(path) || hdd.touchesUnreadable(path) || hasAncestorIn(path, lostSet)
                    || !Files.exists(laptopRoot.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            lost.add(path);
            lostSet.add(path);
        }
        List<RiskFlag> flags = new ArrayList<>();
        lost.forEach(p -> flags.add(RiskFlag.forFile(p, Texts.t("risk.lostOnBackup"))));
        if (lost.size() > LOST_MASS_THRESHOLD) {
            flags.add(RiskFlag.forBatch(Texts.t("risk.lostOnBackupMass", lost.size())));
        }
        return flags;
    }

    /** Rule 5: a plan path that was a file at the last sync and is a folder now, or the reverse. */
    private static void typeChanges(List<SyncAction> actions, Map<String, Manifest.Entry> known, Path laptopRoot,
                                    Path hddRoot, Map<String, String> notes) {
        for (SyncAction a : actions) {
            Manifest.Entry then = known.get(a.relativePath());
            if (then == null || kindKey(then.type()) == null) {
                continue;
            }
            for (Path root : List.of(laptopRoot, hddRoot)) {
                char now = typeOf(root.resolve(a.relativePath()));
                if (now != then.type() && kindKey(now) != null) {
                    note(notes, a.relativePath(), Texts.t("note.manifest.typeChanged",
                            Texts.t(kindKey(then.type())), Texts.t(kindKey(now))));
                    break;
                }
            }
        }
    }

    /** Links have no word on screen, and a link change is not what the note is for. */
    private static String kindKey(char type) {
        return type == 'f' ? "kind.file" : type == 'd' ? "kind.dir" : null;
    }

    private static char typeOf(Path p) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isSymbolicLink() ? 'l' : attrs.isDirectory() ? 'd' : 'f';
        } catch (IOException e) {
            return '-';
        }
    }

    /** Rule 4: a delete and a create in the same direction holding the same bytes. */
    private static List<Rename> renames(List<SyncAction> actions, Path laptopRoot, Path hddRoot, IgnoreRules rules) {
        List<Rename> found = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Direction direction : List.of(Direction.LAPTOP_TO_HDD, Direction.HDD_TO_LAPTOP)) {
            // The delete's copy survives on the side being written; the new one comes from the other.
            Path target = direction == Direction.LAPTOP_TO_HDD ? hddRoot : laptopRoot;
            Path source = direction == Direction.LAPTOP_TO_HDD ? laptopRoot : hddRoot;
            List<String> deletes = paths(actions, direction, ActionType.DELETED);
            List<String> creates = paths(actions, direction, ActionType.NEW);
            if (deletes.isEmpty() || creates.isEmpty()) {
                continue;
            }
            // Grouped by size (files) or file count (folders), so only plausible pairs are read.
            Map<String, List<String>> createsByShape = new HashMap<>();
            Map<String, Map<String, Manifest.Entry>> trees = new HashMap<>();
            for (String b : creates) {
                String shape = shape(source, b, rules, trees);
                if (shape != null) {
                    createsByShape.computeIfAbsent(shape, k -> new ArrayList<>()).add(b);
                }
            }
            for (String a : deletes) {
                String shape = shape(target, a, rules, trees);
                for (String b : shape == null ? List.<String>of() : createsByShape.getOrDefault(shape, List.of())) {
                    // Each path takes part in one rename at most.
                    if (!used.contains(b) && sameContent(target, a, source, b, trees)) {
                        found.add(new Rename(a, b, direction));
                        used.add(b);
                        break;
                    }
                }
            }
        }
        return found;
    }

    private static List<String> paths(List<SyncAction> actions, Direction direction, ActionType type) {
        return actions.stream().filter(a -> a.direction() == direction && a.actionType() == type)
                .map(SyncAction::relativePath).toList();
    }

    /** "f:size" or "d:fileCount"; null for anything that cannot take part (a link, unreadable, empty). */
    private static String shape(Path root, String path, IgnoreRules rules,
                                Map<String, Map<String, Manifest.Entry>> trees) {
        Path p = root.resolve(path);
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isRegularFile()) {
                return "f:" + attrs.size();
            }
            if (!attrs.isDirectory()) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        Tree tree;
        try {
            tree = walk(root, p, rules);
        } catch (IOException e) {
            return null;
        }
        Map<String, Manifest.Entry> inside = new TreeMap<>();
        tree.below(path).forEach((k, v) -> inside.put(k.substring(path.length() + 1), v));
        long files = inside.values().stream().filter(e -> e.type() == 'f').count();
        if (!tree.unreadable().isEmpty() || files == 0) {
            return null;
        }
        trees.put(root + "\0" + path, inside);
        return "d:" + files;
    }

    private static boolean sameContent(Path rootA, String a, Path rootB, String b,
                                       Map<String, Map<String, Manifest.Entry>> trees) {
        Map<String, Manifest.Entry> treeA = trees.get(rootA + "\0" + a);
        Map<String, Manifest.Entry> treeB = trees.get(rootB + "\0" + b);
        try {
            if (treeA == null) {
                return Files.mismatch(rootA.resolve(a), rootB.resolve(b)) == -1;
            }
            if (!treeA.equals(treeB)) {
                return false;
            }
            for (Map.Entry<String, Manifest.Entry> e : treeA.entrySet()) {
                Path pa = rootA.resolve(a).resolve(e.getKey());
                Path pb = rootB.resolve(b).resolve(e.getKey());
                boolean same = switch (e.getValue().type()) {
                    case 'f' -> Files.mismatch(pa, pb) == -1;
                    case 'l' -> Files.readSymbolicLink(pa).equals(Files.readSymbolicLink(pb));
                    default -> true;
                };
                if (!same) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean ignored(String path, IgnoreRules rules) {
        if (underOrEqual(path, FOLDER)) {
            return true;
        }
        // An ignored folder hides everything below it, as it does for Unison.
        for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1)) {
            if (rules.matches(path.substring(0, i))) {
                return true;
            }
        }
        return rules.matches(path);
    }

    private static boolean hasAncestorIn(String path, Set<String> paths) {
        for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1)) {
            if (paths.contains(path.substring(0, i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean underOrEqual(String path, String ancestor) {
        return path.equals(ancestor) || path.startsWith(ancestor + "/");
    }

    /** Like {@code Manifest.walk}, from {@code start} down, but keeps what it could not read. */
    private static Tree walk(Path root, Path start, IgnoreRules rules) throws IOException {
        NavigableMap<String, Manifest.Entry> found = new TreeMap<>();
        Set<String> unreadable = new HashSet<>();
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            unreadable.add("");
            return new Tree(found, unreadable);
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = relative(root, dir);
                if (rel.equals(FOLDER) || rules.matches(rel)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                found.put(rel, new Manifest.Entry('d', 0));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = relative(root, file);
                if (!rules.matches(rel)) {
                    found.put(rel, attrs.isSymbolicLink() ? new Manifest.Entry('l', 0)
                            : new Manifest.Entry('f', attrs.size()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // An unopenable directory lands here before preVisitDirectory, hence the rules here too.
                String rel = relative(root, file);
                if (rel.isEmpty() || !rules.matches(rel)) {
                    unreadable.add(rel);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                // The default rethrows; a listing that broke off midway is just one more unknown place.
                if (e != null) {
                    unreadable.add(relative(root, dir));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new Tree(found, unreadable);
    }

    private static String relative(Path root, Path p) {
        // Join the name elements: a backslash is an ordinary character in a Linux file name.
        StringJoiner joined = new StringJoiner("/");
        root.relativize(p).forEach(part -> joined.add(part.toString()));
        return joined.toString();
    }
}
