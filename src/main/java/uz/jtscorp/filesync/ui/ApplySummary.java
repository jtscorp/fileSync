package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.SyncAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The result card's rows, counted from the approved plan rather than parsed from Unison's output. */
final class ApplySummary {

    enum Mark { DONE, NONE, WARN }

    record Row(Mark mark, String text, String meta) {}

    private ApplySummary() {}

    /**
     * @param batchExitCode exit code of the plain {@code -batch} run
     * @param mergeFailed   the merge run exited non-zero
     * @param unmerged      the merge paths whose two copies still differ afterwards
     */
    static List<Row> rows(List<SyncAction> actions, ApplyPlan plan, int batchExitCode, boolean mergeFailed,
                          List<String> unmerged) {
        return rows(actions, plan, batchExitCode, mergeFailed, unmerged, null);
    }

    /** @param manifestError why the manifest could not be written to the backup, or null */
    static List<Row> rows(List<SyncAction> actions, ApplyPlan plan, int batchExitCode, boolean mergeFailed,
                          List<String> unmerged, String manifestError) {
        // Sets: a plan can hold thousands of rows, and each is looked up in these.
        Set<String> skipped = new HashSet<>(plan.skipped());
        List<SyncAction> done = actions.stream()
                .filter(a -> a.actionType() != SyncAction.ActionType.CONFLICT)
                .filter(a -> !skipped.contains(a.relativePath())).toList();
        // Deletes the mode or the manifest decided and forced overwrites get their own rows:
        // they are what the user most wants to see counted.
        List<String> deleted = done.stream().filter(ApplySummary::isDecidedDelete)
                .map(SyncAction::relativePath).toList();
        List<String> overwritten = done.stream().filter(ApplySummary::isForcedOverwrite)
                .map(SyncAction::relativePath).toList();
        List<String> changes = done.stream()
                .filter(a -> !isDecidedDelete(a) && !isForcedOverwrite(a))
                .map(SyncAction::relativePath).toList();
        List<String> conflicts = actions.stream()
                .filter(a -> a.actionType() == SyncAction.ActionType.CONFLICT)
                .map(SyncAction::relativePath).toList();

        List<Row> rows = new ArrayList<>();
        String firstTwo = names(changes.stream().limit(2).toList()) + (changes.size() > 2 ? " …" : "");
        rows.add(new Row(changes.isEmpty() ? Mark.NONE : Mark.DONE,
                Texts.n("result.changes", changes.size()), firstTwo));
        if (!deleted.isEmpty()) {
            rows.add(new Row(Mark.DONE, Texts.n("result.deleted", deleted.size()), names(deleted)));
        }
        if (!overwritten.isEmpty()) {
            rows.add(new Row(Mark.DONE, Texts.n("result.overwritten", overwritten.size()), names(overwritten)));
        }
        if (manifestError != null) {
            rows.add(new Row(Mark.WARN, Texts.t("result.manifestFailed", manifestError), ""));
        }
        if (skippedUnnamed(actions, batchExitCode)) {
            rows.add(new Row(Mark.WARN, Texts.t("result.unisonSkipped"), ""));
            // MainView leaves the manifest alone on the same test.
            rows.add(new Row(Mark.WARN, Texts.t("result.manifestSkipped"), ""));
        }
        List<String> sidePicked = Stream.concat(plan.laptopWins().stream(), plan.hddWins().stream()).toList();
        if (!sidePicked.isEmpty()) {
            rows.add(new Row(Mark.DONE, Texts.n("result.sidePicked", sidePicked.size()),
                    names(sidePicked)));
        }
        if (mergeFailed && unmerged.isEmpty()) {
            rows.add(new Row(Mark.WARN, Texts.n("result.mergeAsked", plan.merges().size()), names(plan.merges())));
        } else {
            Set<String> unmergedSet = new HashSet<>(unmerged);
            List<String> merged = plan.merges().stream().filter(path -> !unmergedSet.contains(path)).toList();
            if (!merged.isEmpty()) {
                rows.add(new Row(Mark.DONE, Texts.n("result.merged", merged.size()), names(merged)));
            }
            if (!unmerged.isEmpty()) {
                rows.add(new Row(Mark.WARN, Texts.n("result.unmerged", unmerged.size()), names(unmerged)));
            }
        }
        Set<String> handled = new HashSet<>(sidePicked);
        handled.addAll(plan.merges());
        handled.addAll(skipped);
        List<String> untouched = conflicts.stream().filter(path -> !handled.contains(path)).toList();
        if (!untouched.isEmpty()) {
            rows.add(new Row(Mark.NONE, Texts.n("result.untouched", untouched.size()), names(untouched)));
        }
        if (!plan.skipped().isEmpty()) {
            rows.add(new Row(Mark.NONE, Texts.n("result.skipped", plan.skipped().size()),
                    names(plan.skipped())));
        }
        return rows;
    }

    /**
     * The plain batch skipped something the plan does not name. With conflicts in the plan
     * {@code -batch} always exits 1; only without them is it news. The manifest is then not
     * written: the skipped file would be recorded as synced, and the next Check would read its
     * absence on one side as a delete.
     */
    static boolean skippedUnnamed(List<SyncAction> actions, int batchExitCode) {
        return batchExitCode == 1
                && actions.stream().noneMatch(a -> a.actionType() == SyncAction.ActionType.CONFLICT);
    }

    private static boolean isDecidedDelete(SyncAction a) {
        return a.actionType() == SyncAction.ActionType.DELETED && a.origin() != SyncAction.Origin.UNISON;
    }

    private static boolean isForcedOverwrite(SyncAction a) {
        return a.actionType() == SyncAction.ActionType.CHANGED && a.origin() == SyncAction.Origin.MODE;
    }

    /** The row that replaces a failed merge once the user has picked which copy stays. */
    static Row keptRow(String path, boolean laptop) {
        return new Row(Mark.DONE, Texts.t(laptop ? "result.kept.laptop" : "result.kept.hdd",
                ReviewText.splitPath(path).name()), "");
    }

    private static String names(List<String> paths) {
        return paths.stream().map(path -> ReviewText.splitPath(path).name()).collect(Collectors.joining(", "));
    }
}
