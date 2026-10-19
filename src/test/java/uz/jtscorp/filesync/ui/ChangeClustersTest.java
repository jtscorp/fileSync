package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.ChangeClusters.Cluster;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeClustersTest {

    private static final Instant T0 = Instant.parse("2026-10-07T02:14:00Z");
    private static final FileFacts ABSENT = new FileFacts(false, false, 0, null, null);

    private final List<SyncAction> changes = new ArrayList<>();
    private final Map<String, FilePair> facts = new HashMap<>();

    private static FileFacts at(long minutes) {
        return new FileFacts(true, false, 100, T0.plusSeconds(minutes * 60), null);
    }

    /** A file written on the main side {@code minutes} after T0, going to the backup. */
    private void toHdd(String path, ActionType type, long minutes) {
        changes.add(new SyncAction(path, Direction.LAPTOP_TO_HDD, type));
        facts.put(path, new FilePair(at(minutes), type == ActionType.NEW ? ABSENT : at(-600)));
    }

    /** A file gone from the main side, so deleted on the backup: it has no time of its own. */
    private void deletedOnHdd(String path) {
        changes.add(new SyncAction(path, Direction.LAPTOP_TO_HDD, ActionType.DELETED));
        facts.put(path, new FilePair(ABSENT, at(-600)));
    }

    private List<Cluster> clusters() {
        return ChangeClusters.of(changes, facts::get);
    }

    @Test
    void changesUnderTheSameTwoFoldersWithinTenMinutesAreOneGroup() {
        toHdd("kod/backend/A.class", ActionType.CHANGED, 0);
        toHdd("kod/backend/sub/B.class", ActionType.CHANGED, 6);
        toHdd("kod/backend/C.class", ActionType.NEW, 15);
        // Eleven minutes after the last one: a new event.
        toHdd("kod/backend/D.class", ActionType.CHANGED, 26);

        List<Cluster> clusters = clusters();

        assertEquals(1, clusters.size());
        Cluster build = clusters.get(0);
        assertEquals(List.of("kod/backend/A.class", "kod/backend/sub/B.class", "kod/backend/C.class"), build.paths());
        assertEquals("kod/backend", build.prefix());
        assertEquals(T0, build.first());
        assertEquals(T0.plusSeconds(15 * 60), build.last());
        assertEquals("#group:kod/backend/A.class", build.id());
        assertFalse(build.suspicious());
    }

    @Test
    void theKeyIsTheFirstTwoFoldersSoNeighboursAtThatDepthAreApart() {
        for (int i = 0; i < 3; i++) {
            toHdd("2026/sentabr/" + i + ".jpg", ActionType.NEW, i);
            toHdd("2026/oktabr/" + i + ".jpg", ActionType.NEW, i);
        }

        List<String> prefixes = clusters().stream().map(Cluster::prefix).sorted().toList();

        assertEquals(List.of("2026/oktabr", "2026/sentabr"), prefixes);
    }

    @Test
    void fewerThanThreeChangesAreNoGroupAndTheLargestComesFirst() {
        toHdd("a/x/1", ActionType.NEW, 0);
        toHdd("a/x/2", ActionType.NEW, 1);
        for (int i = 0; i < 4; i++) {
            toHdd("b/y/" + i, ActionType.NEW, i);
        }
        for (int i = 0; i < 3; i++) {
            toHdd("c/z/" + i, ActionType.NEW, i);
        }

        assertEquals(List.of("b/y", "c/z"), clusters().stream().map(Cluster::prefix).toList());
    }

    @Test
    void aDeletionJoinsTheGroupOfTheFileItWasRenamedTo() {
        for (int i = 0; i < 5; i++) {
            toHdd("2026/sentabr/IMG_" + i + ".jpg.locked", ActionType.NEW, 0);
            deletedOnHdd("2026/sentabr/IMG_" + i + ".jpg");
        }
        // Gone too, but nothing took its name: not a rename, and not in the group.
        deletedOnHdd("2026/sentabr/eski.txt");

        Cluster locked = clusters().get(0);

        assertEquals(10, locked.paths().size());
        assertFalse(locked.paths().contains("2026/sentabr/eski.txt"));
        assertEquals(5, locked.renames());
        assertEquals(".locked", locked.renameExt());
        assertEquals(Direction.LAPTOP_TO_HDD, locked.renameDirection());
        assertTrue(locked.suspicious(), "five renames to one extension in one event");
    }

    @Test
    void aRenamePairNeedsTheSameDirectionAndNoFolderInBetween() {
        for (int i = 0; i < 3; i++) {
            toHdd("q/r.bak/" + i + ".jpg", ActionType.NEW, 0);
        }
        // Deleted on the main side, while the new file goes to the backup: not one move.
        changes.add(new SyncAction("q/r.bak/0", Direction.HDD_TO_LAPTOP, ActionType.DELETED));
        // "q/r" + "." starts "q/r.bak/1.jpg", but a folder lies between: not a new extension.
        deletedOnHdd("q/r");

        Cluster cluster = clusters().get(0);

        assertEquals(3, cluster.paths().size());
        assertEquals(0, cluster.renames());
        assertEquals("", cluster.renameExt());
        assertNull(cluster.renameDirection());
    }

    @Test
    void fourRenamesAreNotYetSuspicious() {
        for (int i = 0; i < 4; i++) {
            toHdd("p/q/" + i + ".doc.enc", ActionType.NEW, 0);
            deletedOnHdd("p/q/" + i + ".doc");
        }

        assertFalse(clusters().get(0).suspicious());
    }

    @Test
    void topLevelFilesShareTheEmptyKeyAndAChangeWithoutATimeIsLeftOut() {
        toHdd("a.txt", ActionType.CHANGED, 0);
        toHdd("b.txt", ActionType.CHANGED, 1);
        toHdd("c.txt", ActionType.CHANGED, 2);
        changes.add(new SyncAction("d.txt", Direction.LAPTOP_TO_HDD, ActionType.CHANGED));

        Cluster top = clusters().get(0);

        assertEquals("", top.prefix());
        assertEquals(List.of("a.txt", "b.txt", "c.txt"), top.paths());
    }
}
