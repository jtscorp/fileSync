package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Every sentence the review screen computes. Kept apart from the nodes that show them so
 * the wording -- which the user reads to decide what gets overwritten -- has tests.
 */
final class ReviewText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private ReviewText() {}

    /** @param dir includes its trailing slash, or is empty for a top-level file */
    record PathParts(String dir, String name) {}

    static PathParts splitPath(String path) {
        int slash = path.lastIndexOf('/');
        return new PathParts(path.substring(0, slash + 1), path.substring(slash + 1));
    }

    static String size(FileFacts facts) {
        if (!facts.exists()) {
            return "—";
        }
        return facts.directory() ? Texts.t("review.folder") : SizeFormat.format(facts.size());
    }

    /** "old → new" for the copy that is about to be written; a creation shows one size. */
    static String sizes(SyncAction action, FilePair pair) {
        boolean toHdd = action.direction() == Direction.LAPTOP_TO_HDD;
        FileFacts source = toHdd ? pair.laptop() : pair.hdd();
        FileFacts destination = toHdd ? pair.hdd() : pair.laptop();
        return switch (action.actionType()) {
            case NEW -> size(source);
            case DELETED -> size(destination) + " → —";
            default -> size(destination) + " → " + size(source);
        };
    }

    static String direction(SyncAction action) {
        boolean toHdd = action.direction() == Direction.LAPTOP_TO_HDD;
        if (action.actionType() == ActionType.DELETED) {
            return Texts.t(toHdd ? "dir.hddDelete" : "dir.laptopDelete");
        }
        return Texts.t(toHdd ? "dir.toHdd" : "dir.toLaptop");
    }

    static String actionLine(SyncAction action) {
        String line = Texts.t(action.direction() == Direction.LAPTOP_TO_HDD ? "action.toHdd" : "action.toLaptop");
        return action.actionType() == ActionType.DELETED ? Texts.t("action.delete", line) : line;
    }

    /**
     * The size drop in a few characters. Follows the plan's direction when there is one;
     * a conflict or a file Unison did not list has none, so it reads larger → smaller.
     */
    static String riskShort(SyncAction action, FilePair pair) {
        if (action != null && action.actionType() == ActionType.CHANGED) {
            return sizes(action, pair);
        }
        boolean laptopLarger = pair.laptop().size() >= pair.hdd().size();
        return size(laptopLarger ? pair.laptop() : pair.hdd()) + " → "
                + size(laptopLarger ? pair.hdd() : pair.laptop());
    }

    static String statsLabel(long toHdd, long toLaptop) {
        return Texts.t("review.stat.changes", toHdd, toLaptop);
    }

    /** What will happen to a risky item, shown beside it. */
    static String riskState(boolean acknowledged, boolean skipped) {
        return Texts.t(acknowledged ? "risk.state.ack" : skipped ? "risk.state.skip" : "risk.state.pending");
    }

    /** Stands in for the direction on a row the user left out of this Apply. */
    static String skippedDirection() {
        return Texts.t("dir.skipped");
    }

    /** The bulk bar's line: an invitation until something is ticked, then the count. */
    static String bulkText(int picked) {
        return picked == 0 ? Texts.t("review.selectAll") : Texts.n("review.picked", picked);
    }

    static String applySummary(int changes, int resolved, int untouched) {
        StringBuilder summary = new StringBuilder(Texts.n("summary.changes", changes));
        if (resolved > 0) {
            summary.append(" · ").append(Texts.n("summary.resolved", resolved));
        }
        if (untouched > 0) {
            summary.append(" · ").append(Texts.n("summary.untouched", untouched));
        }
        return summary.toString();
    }

    static String gateText(int pending) {
        return pending == 0 ? "" : Texts.n("gate.pending", pending);
    }

    static String riskTitle(int pending) {
        return pending == 0 ? Texts.t("risk.title.done") : Texts.n("risk.title.pending", pending);
    }

    /** Null when merging is available; otherwise why it is not. */
    static String mergeTooltip(boolean bothAreText, boolean profileKeepsBackups, boolean diff3Found) {
        if (!diff3Found) {
            // Unison would run diff3, fail to start it and leave the conflict as it was.
            return Texts.t("merge.noDiff3");
        }
        if (!bothAreText) {
            return Texts.t("merge.textOnly");
        }
        return profileKeepsBackups ? null : Texts.t("merge.off");
    }

    static String time(FileFacts facts, ZoneId zone) {
        return facts.modified() == null ? "—" : TIME.withZone(zone).format(facts.modified());
    }

    /** -1 when the laptop copy is newer, 1 when the HDD copy is, 0 when there is nothing to compare. */
    static int newerSide(FilePair pair) {
        if (pair.laptop().modified() == null || pair.hdd().modified() == null) {
            return 0;
        }
        return -Integer.signum(pair.laptop().modified().compareTo(pair.hdd().modified()));
    }

    static String typeLetter(ActionType type) {
        return switch (type) {
            case NEW -> "N";
            case CHANGED -> "M";
            case DELETED -> "D";
            case CONFLICT -> "C";
        };
    }
}
