package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.SyncAction;

import java.util.ArrayList;
import java.util.List;
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
        List<String> changes = actions.stream()
                .filter(a -> a.actionType() != SyncAction.ActionType.CONFLICT)
                .map(SyncAction::relativePath)
                .filter(path -> !plan.skipped().contains(path)).toList();
        List<String> conflicts = actions.stream()
                .filter(a -> a.actionType() == SyncAction.ActionType.CONFLICT)
                .map(SyncAction::relativePath).toList();

        List<Row> rows = new ArrayList<>();
        String firstTwo = names(changes.stream().limit(2).toList()) + (changes.size() > 2 ? " …" : "");
        rows.add(new Row(changes.isEmpty() ? Mark.NONE : Mark.DONE,
                Texts.n("result.changes", changes.size()), firstTwo));
        // With conflicts in the plan -batch always exits 1; only without them is it news.
        if (batchExitCode == 1 && conflicts.isEmpty()) {
            rows.add(new Row(Mark.WARN, Texts.t("result.unisonSkipped"), ""));
        }
        List<String> sidePicked = Stream.concat(plan.laptopWins().stream(), plan.hddWins().stream()).toList();
        if (!sidePicked.isEmpty()) {
            rows.add(new Row(Mark.DONE, Texts.n("result.sidePicked", sidePicked.size()),
                    names(sidePicked)));
        }
        if (mergeFailed && unmerged.isEmpty()) {
            rows.add(new Row(Mark.WARN, Texts.n("result.mergeAsked", plan.merges().size()), names(plan.merges())));
        } else {
            List<String> merged = plan.merges().stream().filter(path -> !unmerged.contains(path)).toList();
            if (!merged.isEmpty()) {
                rows.add(new Row(Mark.DONE, Texts.n("result.merged", merged.size()), names(merged)));
            }
            if (!unmerged.isEmpty()) {
                rows.add(new Row(Mark.WARN, Texts.n("result.unmerged", unmerged.size()), names(unmerged)));
            }
        }
        List<String> untouched = conflicts.stream()
                .filter(path -> !sidePicked.contains(path) && !plan.merges().contains(path)
                        && !plan.skipped().contains(path)).toList();
        if (!untouched.isEmpty()) {
            rows.add(new Row(Mark.NONE, Texts.n("result.untouched", untouched.size()), names(untouched)));
        }
        if (!plan.skipped().isEmpty()) {
            rows.add(new Row(Mark.NONE, Texts.n("result.skipped", plan.skipped().size()),
                    names(plan.skipped())));
        }
        return rows;
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
