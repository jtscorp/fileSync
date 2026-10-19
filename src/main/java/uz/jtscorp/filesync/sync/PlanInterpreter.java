package uz.jtscorp.filesync.sync;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.config.SyncMode;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.sync.SyncAction.Origin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
                                 Map<String, String> notes) {
        public Interpretation {
            actions = List.copyOf(actions);
            flags = List.copyOf(flags);
            renames = List.copyOf(renames);
            notes = Map.copyOf(notes);
        }
    }

    /** Display only: Unison still deletes {@code from} and writes {@code to}. */
    public record Rename(String from, String to, Direction direction) {}

    /** More lost places than this is a failing drive rather than a few stray deletes. */
    private static final int LOST_MASS_THRESHOLD = 50;
    private static final String FOLDER = ".filesync";
    private static final IgnoreRules NO_RULES = new IgnoreRules(List.of(), List.of(), List.of());

    private PlanInterpreter() {}

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
        Manifest.Walk hdd = own.isPresent() ? Manifest.walk(hddRoot, "", rules) : null;

        List<SyncAction> actions = new ArrayList<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (SyncAction a : parsed) {
            boolean backupOnly = a.actionType() == ActionType.NEW && a.direction() == Direction.HDD_TO_LAPTOP
                    && a.origin() == Origin.UNISON;
            if (backupOnly && mode == SyncMode.TWO_WAY_PRUNE) {
                actions.add(backupDelete(a, Origin.MODE));
                note(notes, a.relativePath(), Texts.t("note.mode.prune"));
            } else if (backupOnly && mode == SyncMode.TWO_WAY && hdd != null) {
                switch (deletedOnMain(a.relativePath(), known, hdd, laptopRoot, hddRoot)) {
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

        List<RiskFlag> flags = hdd != null ? lostOnBackup(known, hdd, laptopRoot) : List.of();
        if (hdd != null) {
            typeChanges(actions, known, laptopRoot, hddRoot, notes);
        }
        List<Rename> renames = RenameDetector.detect(actions, laptopRoot, hddRoot, rules);
        return new Interpretation(actions, flags, renames, notes);
    }

    private static SyncAction backupDelete(SyncAction a, Origin origin) {
        return new SyncAction(a.relativePath(), Direction.LAPTOP_TO_HDD, ActionType.DELETED, origin);
    }

    private static void note(Map<String, String> notes, String path, String sentence) {
        notes.merge(path, sentence, (old, added) -> old + " · " + added);
    }

    private enum Verdict { YES, NO, ADDED_INSIDE }

    /** Rule 2: the backup copy is exactly what both sides held at the last sync. */
    private static Verdict deletedOnMain(String path, Map<String, Manifest.Entry> known, Manifest.Walk hdd,
                                         Path laptopRoot, Path hddRoot) throws IOException {
        Manifest.Entry then = known.get(path);
        Manifest.Entry now = hdd.entries().get(path);
        // Main must still lack it: if something is there now, deleting the backup copy is no longer
        // "propagating Main's delete". notExists, so a path that cannot be examined is not "absent".
        if (then == null || now == null || hdd.touchesUnreadable(path)
                || !Files.notExists(laptopRoot.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
            return Verdict.NO;
        }
        if (then.type() != 'd') {
            return unchanged(then, now) ? Verdict.YES : Verdict.NO;
        }
        if (now.type() != 'd') {
            return Verdict.NO;
        }
        // Walked without the ignore rules: "-force -path" deletes the whole folder, ignored files
        // included (verified on 2.53.3), so an ignored file inside is one the user never saw go.
        Manifest.Walk inside = Manifest.walk(hddRoot, path, NO_RULES);
        if (!inside.unreadable().isEmpty()) {
            return Verdict.NO;
        }
        boolean addedInside = false;
        for (Map.Entry<String, Manifest.Entry> e : inside.below(path).entrySet()) {
            // known holds no ignored path, so an ignored file here also counts as added.
            Manifest.Entry recorded = known.get(e.getKey());
            if (recorded == null) {
                addedInside = true;
            } else if (!unchanged(recorded, e.getValue())) {
                return Verdict.NO;
            }
        }
        return addedInside ? Verdict.ADDED_INSIDE : Verdict.YES;
    }

    /**
     * A file or link is the recorded copy only if type, size and the backup copy's time all match:
     * a same-size edit changes only the time. A folder's time moves with any change inside it, and
     * those entries are checked one by one, so a folder is matched by type alone.
     */
    private static boolean unchanged(Manifest.Entry recorded, Manifest.Entry now) {
        return recorded.type() == 'd' ? now.type() == 'd' : recorded.equals(now);
    }

    /** Rule 3: on the backup at the last sync, gone now, still on Main -- maybe a failing drive. */
    private static List<RiskFlag> lostOnBackup(Map<String, Manifest.Entry> known, Manifest.Walk hdd,
                                               Path laptopRoot) {
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

    private static boolean ignored(String path, IgnoreRules rules) {
        if (Manifest.underOrEqual(path, FOLDER)) {
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
}
