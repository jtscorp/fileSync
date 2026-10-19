package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.SyncAction;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyPlanTest {

    private static SyncAction conflict(String path) {
        return new SyncAction(path, SyncAction.Direction.CONFLICT, SyncAction.ActionType.CONFLICT);
    }

    private static final List<SyncAction> ACTIONS = List.of(
            conflict("a.md"), conflict("b.yaml"), conflict("c.pdf"), conflict("d.txt"),
            new SyncAction("e.jpg", SyncAction.Direction.LAPTOP_TO_HDD, SyncAction.ActionType.NEW));

    @Test
    void groupsResolvedConflictsBySideInPlanOrder() {
        ApplyPlan plan = ApplyPlan.from(Map.of(
                "d.txt", Resolution.LAPTOP, "a.md", Resolution.LAPTOP,
                "c.pdf", Resolution.HDD, "b.yaml", Resolution.MERGE),
                ACTIONS, Set.of("b.yaml"), true, List.of());

        assertEquals(List.of("a.md", "d.txt"), plan.laptopWins());
        assertEquals(List.of("c.pdf"), plan.hddWins());
        assertEquals(List.of("b.yaml"), plan.merges());
    }

    @Test
    void skipAndUnresolvedConflictsAreLeftOut() {
        ApplyPlan plan = ApplyPlan.from(Map.of("a.md", Resolution.SKIP), ACTIONS, Set.of(), true, List.of());

        assertTrue(plan.laptopWins().isEmpty());
        assertTrue(plan.hddWins().isEmpty());
        assertTrue(plan.merges().isEmpty());
    }

    @Test
    void mergeIsDroppedWhenNotAllowed() {
        // diff3 on a binary can exit 0 and corrupt both copies, and without backups the
        // merge run fails outright -- neither may reach the command line.
        Map<String, Resolution> resolutions = Map.of("b.yaml", Resolution.MERGE, "c.pdf", Resolution.MERGE);

        assertEquals(List.of("b.yaml"), ApplyPlan.from(resolutions, ACTIONS, Set.of("b.yaml"), true, List.of()).merges());
        assertTrue(ApplyPlan.from(resolutions, ACTIONS, Set.of("b.yaml"), false, List.of()).merges().isEmpty());
    }

    @Test
    void aSkippedFileReachesNoScopedRunWhateverWasPickedForIt() {
        ApplyPlan plan = ApplyPlan.from(
                Map.of("a.md", Resolution.LAPTOP, "c.pdf", Resolution.HDD, "b.yaml", Resolution.MERGE),
                ACTIONS, Set.of("b.yaml"), true, List.of("a.md", "b.yaml", "e.jpg"));

        assertTrue(plan.laptopWins().isEmpty());
        assertTrue(plan.merges().isEmpty());
        assertEquals(List.of("c.pdf"), plan.hddWins());
        assertEquals(List.of("a.md", "b.yaml", "e.jpg"), plan.skipped());
    }

    @Test
    void aResolutionForSomethingThatIsNotAConflictIsIgnored() {
        ApplyPlan plan = ApplyPlan.from(Map.of("e.jpg", Resolution.HDD, "yo'q.txt", Resolution.LAPTOP),
                ACTIONS, Set.of(), true, List.of());

        assertTrue(plan.hddWins().isEmpty());
        assertTrue(plan.laptopWins().isEmpty());
    }

    @Test
    void onlyAChangeFromThisPlanJoinsTheSkippedFiles() {
        // A conflict is skipped at the gate or not at all, and a path the plan does not
        // hold must never reach an -ignore.
        assertEquals(List.of("risky.xlsx", "e.jpg"),
                ApplyPlan.skipped(List.of("risky.xlsx"), Set.of("e.jpg", "a.md", "elsewhere.txt"), ACTIONS));
        assertEquals(List.of("e.jpg"), ApplyPlan.skipped(List.of("e.jpg"), Set.of("e.jpg"), ACTIONS));
    }

    private static SyncAction prune(String path, SyncAction.Origin origin) {
        return new SyncAction(path, SyncAction.Direction.LAPTOP_TO_HDD, SyncAction.ActionType.DELETED, origin);
    }

    @Test
    void prunePathsComeFromModeAndManifestRowsOnly() {
        List<SyncAction> actions = List.of(
                prune("m.txt", SyncAction.Origin.MODE), prune("u.txt", SyncAction.Origin.UNISON),
                prune("f.txt", SyncAction.Origin.MANIFEST),
                new SyncAction("n.txt", SyncAction.Direction.HDD_TO_LAPTOP, SyncAction.ActionType.DELETED,
                        SyncAction.Origin.MANIFEST));

        ApplyPlan plan = ApplyPlan.from(Map.of(), actions, Set.of(), true, List.of(),
                uz.jtscorp.filesync.config.SyncMode.TWO_WAY_PRUNE);

        assertEquals(List.of("m.txt", "f.txt"), plan.pruneFromHdd());
        assertEquals(List.of("m.txt", "f.txt"), plan.batchIgnores());
    }

    @Test
    void skippedPruneStaysIgnoredInBatchAndOutOfPrune() {
        ApplyPlan plan = ApplyPlan.from(Map.of(), List.of(prune("old", SyncAction.Origin.MANIFEST)), Set.of(), true,
                List.of("old"), uz.jtscorp.filesync.config.SyncMode.TWO_WAY_PRUNE);

        assertTrue(plan.pruneFromHdd().isEmpty());
        assertEquals(List.of("old"), plan.batchIgnores());
    }

    @Test
    void unskippedPruneIsIgnoredInBatchToo() {
        // Otherwise the plain -batch run copies the backup-only file onto the main side.
        ApplyPlan plan = ApplyPlan.from(Map.of(), List.of(prune("old", SyncAction.Origin.MANIFEST)), Set.of(), true,
                List.of(), uz.jtscorp.filesync.config.SyncMode.TWO_WAY_PRUNE);

        assertEquals(List.of("old"), plan.pruneFromHdd());
        assertTrue(plan.batchIgnores().contains("old"));
    }

    @Test
    void forcedModeHasNoPruneAndNoConflictRuns() {
        List<SyncAction> actions = List.of(conflict("a.md"), prune("old", SyncAction.Origin.MODE));
        for (var mode : List.of(uz.jtscorp.filesync.config.SyncMode.MIRROR_TO_BACKUP,
                uz.jtscorp.filesync.config.SyncMode.RESTORE_FROM_BACKUP)) {
            ApplyPlan plan = ApplyPlan.from(Map.of("a.md", Resolution.LAPTOP), actions, Set.of(), true, List.of(), mode);

            assertTrue(plan.pruneFromHdd().isEmpty());
            assertTrue(plan.laptopWins().isEmpty());
            assertTrue(plan.hddWins().isEmpty());
            assertTrue(plan.merges().isEmpty());
        }
    }

    @Test
    void skippingARenameSkipsBothPaths() {
        List<SyncAction> actions = List.of(
                new SyncAction("old.txt", SyncAction.Direction.LAPTOP_TO_HDD, SyncAction.ActionType.DELETED),
                new SyncAction("new.txt", SyncAction.Direction.LAPTOP_TO_HDD, SyncAction.ActionType.NEW));
        var rename = new uz.jtscorp.filesync.sync.PlanInterpreter.Rename("old.txt", "new.txt",
                SyncAction.Direction.LAPTOP_TO_HDD);

        assertEquals(List.of("old.txt", "new.txt"),
                ApplyPlan.skipped(List.of(), Set.of("new.txt"), actions, List.of(rename)));
        assertTrue(ApplyPlan.skipped(List.of(), Set.of(), actions, List.of(rename)).isEmpty());
    }
}
