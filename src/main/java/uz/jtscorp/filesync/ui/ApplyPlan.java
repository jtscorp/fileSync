package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.config.SyncMode;
import uz.jtscorp.filesync.sync.PlanInterpreter;
import uz.jtscorp.filesync.sync.SyncAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The path lists for Apply's scoped Unison runs. Only paths from this check's plan get in.
 *
 * @param pruneFromHdd backup-only files the mode or the manifest turned into "delete from backup"
 */
record ApplyPlan(List<String> laptopWins, List<String> hddWins, List<String> merges, List<String> skipped,
                 List<String> pruneFromHdd) {

    ApplyPlan(List<String> laptopWins, List<String> hddWins, List<String> merges, List<String> skipped) {
        this(laptopWins, hddWins, merges, skipped, List.of());
    }

    /**
     * What the plain -batch run must ignore: the skipped files and every prune. A prune left
     * to -batch would be copied onto the main side, the very bug the prune run exists to avoid.
     */
    List<String> batchIgnores() {
        List<String> ignores = new ArrayList<>(skipped);
        for (String path : pruneFromHdd) {
            if (!ignores.contains(path)) {
                ignores.add(path);
            }
        }
        return List.copyOf(ignores);
    }

    static List<String> skipped(List<String> gateSkipped, Set<String> skippedChanges, List<SyncAction> actions) {
        return skipped(gateSkipped, skippedChanges, actions, List.of());
    }

    /**
     * Everything this Apply leaves out: gate skips, then skipped changes that are in the plan.
     * A rename is skipped by its {@code to} path and takes its {@code from} path with it,
     * whether the skip was made in the list or at the gate: left alone, the {@code from} half
     * would still be deleted while its new name is never written.
     */
    static List<String> skipped(List<String> gateSkipped, Set<String> skippedChanges, List<SyncAction> actions,
                                List<PlanInterpreter.Rename> renames) {
        List<String> skipped = new ArrayList<>(gateSkipped);
        for (PlanInterpreter.Rename rename : renames) {
            if (!skippedChanges.contains(rename.to()) && !gateSkipped.contains(rename.to())) {
                continue;
            }
            for (String path : List.of(rename.from(), rename.to())) {
                boolean inPlan = actions.stream().anyMatch(a -> a.relativePath().equals(path)
                        && a.actionType() != SyncAction.ActionType.CONFLICT);
                if (inPlan && !skipped.contains(path)) {
                    skipped.add(path);
                }
            }
        }
        for (SyncAction action : actions) {
            String path = action.relativePath();
            if (action.actionType() != SyncAction.ActionType.CONFLICT && skippedChanges.contains(path)
                    && !skipped.contains(path)) {
                skipped.add(path);
            }
        }
        return List.copyOf(skipped);
    }

    static ApplyPlan from(Map<String, Resolution> resolutions, List<SyncAction> actions,
                          Set<String> mergeablePaths, boolean keepMergeBackups, List<String> skipped) {
        return from(resolutions, actions, mergeablePaths, keepMergeBackups, skipped, SyncMode.TWO_WAY);
    }

    /**
     * @param mode a forced mode leaves the conflict and prune lists empty: its batch -force does
     *             it all, and a -prefer after -force would mean nothing.
     * @param skipped files the user left out of this Apply. Skipping wins over any
     *                resolution picked for the same file, so it reaches no {@code -path}.
     */
    static ApplyPlan from(Map<String, Resolution> resolutions, List<SyncAction> actions,
                          Set<String> mergeablePaths, boolean keepMergeBackups, List<String> skipped,
                          SyncMode mode) {
        List<String> laptopWins = new ArrayList<>();
        List<String> hddWins = new ArrayList<>();
        List<String> merges = new ArrayList<>();
        List<String> prune = new ArrayList<>();
        for (SyncAction action : actions) {
            if (mode.forced()) {
                break;
            }
            if (action.actionType() == SyncAction.ActionType.DELETED
                    && action.direction() == SyncAction.Direction.LAPTOP_TO_HDD
                    && action.origin() != SyncAction.Origin.UNISON
                    && !skipped.contains(action.relativePath())) {
                prune.add(action.relativePath());
            }
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
                List.copyOf(skipped), List.copyOf(prune));
    }
}
