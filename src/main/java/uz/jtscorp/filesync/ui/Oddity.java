package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.IsolationForest;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.TreeProfile;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * How unusual each change is, by an Isolation Forest built for this check from the two
 * trees and the changes themselves. It only marks: a score never becomes a gate item, since
 * a false alarm that blocks Apply teaches the user to click through. A score is shown only
 * with a reason a person can check, and the reasons are plain rules, not the model's.
 */
final class Oddity {

    /** A score at least this, with a reason, marks the change. */
    static final double ODD = 0.6;
    /** A change before this hour of the day counts as made at night. */
    private static final int NIGHT_ENDS = 6;
    private static final double STALE_YEARS = 1;
    private static final long SEED = 42;
    private static final double YEAR_SECONDS = 365.25 * 24 * 3600;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** In the order a row lists them. */
    enum Why { NIGHT, STALE, RARE_EXT, PAIR }

    /** @param value what the sentence names: the time, the years, the extension; empty for a pair */
    record Reason(Why why, String value) {}

    record Mark(double score, List<Reason> reasons) {
        /** What the list marks: a high score that can also say why. */
        boolean odd() {
            return score >= ODD && !reasons.isEmpty();
        }
    }

    /** One file as the model sees it, and the reasons it would give. */
    private record Seen(double[] features, List<Reason> reasons) {}

    private Oddity() {}

    /**
     * @param changes the plan's non-conflict rows; a deletion has no time of its own and is not scored
     * @param now     the check's time, against which the trees' files are aged
     * @return a mark for every scored change, by path
     */
    static Map<String, Mark> score(List<SyncAction> changes, Function<String, FilePair> facts,
                                   TreeProfile laptopTree, TreeProfile hddTree, Instant now, ZoneId zone) {
        Map<String, SyncAction> deleted = new HashMap<>();
        for (SyncAction action : changes) {
            if (action.actionType() == ActionType.DELETED) {
                deleted.put(action.relativePath(), action);
            }
        }
        Map<String, Seen> seen = new LinkedHashMap<>();
        for (SyncAction action : changes) {
            FilePair pair = facts.apply(action.relativePath());
            if (action.actionType() == ActionType.DELETED || pair == null) {
                continue;
            }
            boolean toHdd = action.direction() == SyncAction.Direction.LAPTOP_TO_HDD;
            FileFacts source = toHdd ? pair.laptop() : pair.hdd();
            FileFacts before = toHdd ? pair.hdd() : pair.laptop();
            if (source.modified() == null) {
                continue;
            }
            // How long the copy it replaces had stood; a new file replaces nothing.
            Double years = before.exists() && before.modified() != null
                    ? yearsBetween(before.modified(), source.modified()) : null;
            seen.put(action.relativePath(), see(action.relativePath(), source.size(), source.modified(), years,
                    toHdd ? laptopTree : hddTree, isRenamed(action, deleted), zone));
        }
        if (seen.isEmpty()) {
            return Map.of();
        }

        List<double[]> data = new ArrayList<>();
        for (TreeProfile tree : List.of(laptopTree, hddTree)) {
            for (TreeProfile.FileStat file : tree.sample()) {
                // An unchanged file: written at its own hour, untouched from then until now.
                data.add(see(file.path(), file.size(), file.modified(), yearsBetween(file.modified(), now), tree,
                        false, zone).features());
            }
        }
        seen.values().forEach(s -> data.add(s.features()));
        IsolationForest forest = IsolationForest.fit(data, SEED);

        Map<String, Mark> marks = new LinkedHashMap<>();
        seen.forEach((path, s) -> marks.put(path, new Mark(forest.score(s.features()), s.reasons())));
        return marks;
    }

    private static double yearsBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).getSeconds() / YEAR_SECONDS);
    }

    private static boolean isRenamed(SyncAction action, Map<String, SyncAction> deleted) {
        String path = action.relativePath();
        // Each dot of the name could end the deleted file's path: "a.jpg" for "a.jpg.locked".
        for (int dot = path.indexOf('.', path.lastIndexOf('/') + 1); dot > 0; dot = path.indexOf('.', dot + 1)) {
            SyncAction original = deleted.get(path.substring(0, dot));
            if (original != null && ChangeClusters.isRename(action, original)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The features: the hour it was written, the years it had been untouched, how rare its
     * extension is in its folder, whether it replaced a deleted original, and its size against
     * its folder's mean.
     *
     * @param written    when this version was written
     * @param untouched  how many years the file had stood unchanged, or null for a new file
     */
    private static Seen see(String path, long size, Instant written, Double untouched, TreeProfile tree,
                            boolean renamed, ZoneId zone) {
        LocalTime time = LocalTime.ofInstant(written, zone);
        double hour = time.getHour() + time.getMinute() / 60.0;
        double years = untouched == null ? 0 : untouched;
        String folder = TreeProfile.folderOf(path);
        String extension = TreeProfile.extensionOf(path);
        // The tree holds this file too, so it is taken out of both counts.
        int others = tree.files(folder) - 1;
        int sameExtension = tree.extensionCount(folder, extension) - 1;
        double rarity = others <= 0 ? 0 : 1 - (double) Math.max(0, sameExtension) / others;
        double mean = tree.meanSize(folder);
        double relativeSize = Double.isNaN(mean) ? 0 : Math.log1p(size) - Math.log1p(mean);

        List<Reason> reasons = new ArrayList<>();
        if (time.getHour() < NIGHT_ENDS) {
            reasons.add(new Reason(Why.NIGHT, CLOCK.format(time)));
        }
        if (untouched != null && years >= STALE_YEARS) {
            reasons.add(new Reason(Why.STALE, String.valueOf((int) Math.floor(years))));
        }
        if (!extension.isEmpty() && others > 0 && sameExtension <= 0) {
            reasons.add(new Reason(Why.RARE_EXT, extension));
        }
        if (renamed) {
            reasons.add(new Reason(Why.PAIR, ""));
        }
        return new Seen(new double[] {hour, years, rarity, renamed ? 1 : 0, relativeSize}, reasons);
    }
}
