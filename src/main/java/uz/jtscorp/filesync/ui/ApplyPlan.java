package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.sync.SyncAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The path lists for Apply's scoped Unison runs. Only conflicts from this check's plan get in. */
record ApplyPlan(List<String> laptopWins, List<String> hddWins, List<String> merges, List<String> skipped) {

    /** Everything this Apply leaves out: gate skips, then skipped changes that are in the plan. */
    static List<String> skipped(List<String> gateSkipped, Set<String> skippedChanges, List<SyncAction> actions) {
        List<String> skipped = new ArrayList<>(gateSkipped);
        for (SyncAction action : actions) {
            String path = action.relativePath();
            if (action.actionType() != SyncAction.ActionType.CONFLICT && skippedChanges.contains(path)
                    && !skipped.contains(path)) {
                skipped.add(path);
            }
        }
        return List.copyOf(skipped);
    }

    /**
     * @param skipped files the user left out of this Apply. Skipping wins over any
     *                resolution picked for the same file, so it reaches no {@code -path}.
     */
    static ApplyPlan from(Map<String, Resolution> resolutions, List<SyncAction> actions,
                          Set<String> mergeablePaths, boolean keepMergeBackups, List<String> skipped) {
        List<String> laptopWins = new ArrayList<>();
        List<String> hddWins = new ArrayList<>();
        List<String> merges = new ArrayList<>();
        for (SyncAction action : actions) {
            if (action.actionType() != SyncAction.ActionType.CONFLICT) {
                continue;
            }
            String path = action.relativePath();
            if (skipped.contains(path)) {
                continue;
            }
            switch (resolutions.getOrDefault(path, Resolution.SKIP)) {
                case LAPTOP -> laptopWins.add(path);
                case HDD -> hddWins.add(path);
                case MERGE -> {
                    // The UI never offers merge without both conditions; checked again
                    // because diff3 on a binary can exit 0 and corrupt both copies.
                    if (keepMergeBackups && mergeablePaths.contains(path)) {
                        merges.add(path);
                    }
                }
                case SKIP -> { }
            }
        }
        return new ApplyPlan(List.copyOf(laptopWins), List.copyOf(hddWins), List.copyOf(merges),
                List.copyOf(skipped));
    }
}
