package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Changes that happened together, as one group: a build, a camera import, a mass rename.
 * A group whose files were renamed to a new extension with the originals gone in the same
 * event is suspicious -- that is what ransomware leaves behind. Free of JavaFX so the
 * grouping has a plain test.
 */
final class ChangeClusters {

    /** A change joins the group when it is no later than this after the group's last one. */
    static final Duration WINDOW = Duration.ofMinutes(10);
    static final int MIN_SIZE = 3;
    static final int SUSPICIOUS_RENAMES = 5;
    /** How many leading folders make the key: {@code kod/backend}, {@code 2026/sentabr}. */
    private static final int KEY_DEPTH = 2;

    /**
     * @param id              stable for the check: the first member's path
     * @param prefix          the members' common folder; empty when they share none
     * @param paths           the members, in the order they joined
     * @param first           the earliest change time among the members that have one
     * @param renames         deletions paired with a new file of the same name plus an extension
     * @param renameExt       the extension most of those new files got, or empty without renames
     * @param renameDirection which side the renames are written to, or null without renames
     */
    record Cluster(String id, String prefix, List<String> paths, Instant first, Instant last, int renames,
                   String renameExt, Direction renameDirection, boolean suspicious) {}

    private ChangeClusters() {}

    /**
     * @param changes the plan's non-conflict rows
     * @param facts   both copies' facts for a path, or null when unknown
     */
    static List<Cluster> of(List<SyncAction> changes, Function<String, FilePair> facts) {
        record Timed(SyncAction action, Instant time) {}
        List<Timed> timed = new ArrayList<>();
        for (SyncAction action : changes) {
            Instant time = sourceTime(action, facts.apply(action.relativePath()));
            if (action.actionType() != ActionType.DELETED && time != null) {
                timed.add(new Timed(action, time));
            }
        }
        timed.sort(Comparator.comparing(Timed::time).thenComparing(t -> t.action().relativePath()));

        Map<String, Builder> open = new HashMap<>();
        List<Builder> all = new ArrayList<>();
        for (Timed t : timed) {
            String key = key(t.action().relativePath());
            Builder current = open.get(key);
            if (current == null || Duration.between(current.last, t.time()).compareTo(WINDOW) > 0) {
                current = new Builder(t.time());
                open.put(key, current);
                all.add(current);
            }
            current.add(t.action(), t.time());
        }
        // A deletion has no time of its own; it joins the group of the file it was renamed to.
        for (SyncAction deleted : changes) {
            if (deleted.actionType() != ActionType.DELETED) {
                continue;
            }
            for (Builder builder : all) {
                SyncAction renamed = builder.renameOf(deleted);
                if (renamed != null) {
                    builder.addRename(deleted, renamed);
                    break;
                }
            }
        }
        return all.stream()
                .filter(b -> b.actions.size() >= MIN_SIZE)
                .sorted(Comparator.comparingInt((Builder b) -> -b.actions.size()))
                .map(Builder::build)
                .toList();
    }

    /**
     * Whether a new file is a deleted one renamed to a new extension: written to the same
     * side, its path the deleted path plus an extension, with no folder in between.
     */
    static boolean isRename(SyncAction renamed, SyncAction deleted) {
        String stem = deleted.relativePath() + ".";
        return renamed.actionType() == ActionType.NEW && deleted.actionType() == ActionType.DELETED
                && renamed.direction() == deleted.direction() && renamed.relativePath().startsWith(stem)
                && !renamed.relativePath().substring(stem.length()).contains("/");
    }

    /** The time of the copy that is about to be propagated: the side the change comes from. */
    private static Instant sourceTime(SyncAction action, FilePair pair) {
        if (pair == null) {
            return null;
        }
        FileFacts source = action.direction() == Direction.LAPTOP_TO_HDD ? pair.laptop() : pair.hdd();
        return source.modified();
    }

    private static String key(String path) {
        String[] folders = folders(path);
        return String.join("/", Arrays.copyOf(folders, Math.min(KEY_DEPTH, folders.length)));
    }

    private static String[] folders(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? new String[0] : path.substring(0, slash).split("/");
    }

    private static String commonFolder(List<String> paths) {
        String[] common = folders(paths.get(0));
        int length = common.length;
        for (String path : paths) {
            String[] folders = folders(path);
            int same = 0;
            while (same < Math.min(length, folders.length) && folders[same].equals(common[same])) {
                same++;
            }
            length = same;
        }
        return String.join("/", Arrays.copyOf(common, length));
    }

    private static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot);
    }

    private static final class Builder {
        final List<SyncAction> actions = new ArrayList<>();
        final Instant first;
        Instant last;
        int renames;
        Direction renameDirection;
        final Map<String, Integer> renameExts = new LinkedHashMap<>();

        Builder(Instant first) {
            this.first = first;
            this.last = first;
        }

        void add(SyncAction action, Instant time) {
            actions.add(action);
            last = time;
        }

        /** The new file this deletion was renamed to: same side, its path plus an extension, same folder. */
        SyncAction renameOf(SyncAction deleted) {
            return actions.stream().filter(a -> isRename(a, deleted)).findFirst().orElse(null);
        }

        void addRename(SyncAction deleted, SyncAction renamed) {
            actions.add(deleted);
            renames++;
            renameDirection = renamed.direction();
            renameExts.merge(extension(renamed.relativePath()), 1, Integer::sum);
        }

        Cluster build() {
            List<String> paths = actions.stream().map(SyncAction::relativePath).toList();
            String ext = renameExts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
            return new Cluster("#group:" + paths.get(0), commonFolder(paths), paths, first, last, renames, ext,
                    renameDirection, renames >= SUSPICIOUS_RENAMES);
        }
    }
}
