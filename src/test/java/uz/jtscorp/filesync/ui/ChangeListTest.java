package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.ChangeList.Filter;
import uz.jtscorp.filesync.ui.ChangeList.Sort;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeListTest {

    private static final FileFacts ABSENT = new FileFacts(false, false, 0, null, null);

    private static FileFacts file(long size, long modifiedSecond) {
        return new FileFacts(true, false, size, Instant.ofEpochSecond(modifiedSecond), null);
    }

    private static final SyncAction REPORT = new SyncAction("hisobot/b.docx", Direction.LAPTOP_TO_HDD, ActionType.CHANGED);
    private static final SyncAction SCAN = new SyncAction("skanlar/a.jpg", Direction.LAPTOP_TO_HDD, ActionType.NEW);
    private static final SyncAction ALBUM = new SyncAction("albom/d.json", Direction.HDD_TO_LAPTOP, ActionType.CHANGED);
    private static final SyncAction OLD = new SyncAction("eski/c.txt", Direction.LAPTOP_TO_HDD, ActionType.DELETED);
    private static final List<SyncAction> CHANGES = List.of(REPORT, SCAN, ALBUM, OLD);

    private static final Map<String, FilePair> FACTS = Map.of(
            REPORT.relativePath(), new FilePair(file(184, 40), file(179, 10)),
            SCAN.relativePath(), new FilePair(file(2400, 50), ABSENT),
            ALBUM.relativePath(), new FilePair(file(356, 20), file(402, 30)),
            OLD.relativePath(), new FilePair(ABSENT, file(812, 5)));

    private static List<SyncAction> shown(Filter filter, Sort sort, boolean reversed) {
        return ChangeList.shown(CHANGES, FACTS::get, filter, sort, reversed);
    }

    @Test
    void aDeletionIsItsOwnFilterWhicheverSideItLandsOn() {
        assertEquals(List.of(REPORT, SCAN), shown(Filter.TO_HDD, Sort.PATH, false));
        assertEquals(List.of(ALBUM), shown(Filter.TO_LAPTOP, Sort.PATH, false));
        assertEquals(List.of(OLD), shown(Filter.DELETES, Sort.PATH, false));
        assertEquals(4, ChangeList.count(CHANGES, Filter.ALL));
        assertEquals(2, ChangeList.count(CHANGES, Filter.TO_HDD));
        assertEquals(1, ChangeList.count(CHANGES, Filter.DELETES));
    }

    @Test
    void eachOrderStartsWhereAReaderExpectsAndTurnsAround() {
        assertEquals(List.of(ALBUM, OLD, REPORT, SCAN), shown(Filter.ALL, Sort.PATH, false));
        assertEquals(List.of(SCAN, REPORT, OLD, ALBUM), shown(Filter.ALL, Sort.NAME, false));
        // The larger and the later of the two copies; a missing copy counts for nothing.
        assertEquals(List.of(SCAN, OLD, ALBUM, REPORT), shown(Filter.ALL, Sort.SIZE, false));
        assertEquals(List.of(SCAN, REPORT, ALBUM, OLD), shown(Filter.ALL, Sort.DATE, false));
        assertEquals(List.of(OLD, ALBUM, REPORT, SCAN), shown(Filter.ALL, Sort.DATE, true));
    }

    @Test
    void theArrowMarksTheOrderInUse() {
        assertEquals("Hajm", Sort.SIZE.label(false, false));
        assertEquals("Hajm ↓", Sort.SIZE.label(true, false));
        assertEquals("Hajm ↑", Sort.SIZE.label(true, true));
    }
}
