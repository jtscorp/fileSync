package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.ChangeList.ConflictFilter;
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

    private static final SyncAction DRAFT = new SyncAction("hujjat/qoralama.txt", Direction.CONFLICT, ActionType.CONFLICT);
    private static final SyncAction PHOTO = new SyncAction("albom/rasm.jpg", Direction.CONFLICT, ActionType.CONFLICT);
    private static final SyncAction NOTES = new SyncAction("eslatma.md", Direction.CONFLICT, ActionType.CONFLICT);
    private static final SyncAction TABLE = new SyncAction("hisob/jadval.csv", Direction.CONFLICT, ActionType.CONFLICT);
    private static final List<SyncAction> CONFLICTS = List.of(DRAFT, PHOTO, NOTES, TABLE);

    private static final Map<String, FilePair> CONFLICT_FACTS = Map.of(
            DRAFT.relativePath(), new FilePair(file(10, 40), file(12, 10)),
            PHOTO.relativePath(), new FilePair(file(5000, 20), file(4800, 30)),
            NOTES.relativePath(), new FilePair(file(300, 5), file(310, 6)),
            TABLE.relativePath(), new FilePair(file(70, 50), file(60, 1)));

    /** NOTES has no entry: an untouched conflict is undecided. */
    private static final Map<String, Resolution> DECISIONS = Map.of(
            DRAFT.relativePath(), Resolution.LAPTOP,
            PHOTO.relativePath(), Resolution.HDD,
            TABLE.relativePath(), Resolution.SKIP);

    private static Resolution decision(String path) {
        return DECISIONS.getOrDefault(path, Resolution.SKIP);
    }

    private static List<SyncAction> shownConflicts(ConflictFilter filter, Sort sort, boolean reversed) {
        return ChangeList.shownConflicts(CONFLICTS, CONFLICT_FACTS::get, ChangeListTest::decision,
                filter, sort, reversed);
    }

    @Test
    void aConflictIsFilteredByWhatWasDecidedForIt() {
        assertEquals(List.of(NOTES, TABLE), shownConflicts(ConflictFilter.UNDECIDED, Sort.PATH, false));
        assertEquals(List.of(DRAFT), shownConflicts(ConflictFilter.LAPTOP, Sort.PATH, false));
        assertEquals(List.of(PHOTO), shownConflicts(ConflictFilter.HDD, Sort.PATH, false));
        assertEquals(List.of(), shownConflicts(ConflictFilter.MERGE, Sort.PATH, false));
        assertEquals(4, ChangeList.count(CONFLICTS, ChangeListTest::decision, ConflictFilter.ALL));
        assertEquals(2, ChangeList.count(CONFLICTS, ChangeListTest::decision, ConflictFilter.UNDECIDED));
        assertEquals(0, ChangeList.count(CONFLICTS, ChangeListTest::decision, ConflictFilter.MERGE));
    }

    @Test
    void conflictsTakeTheSameOrdersAsChanges() {
        assertEquals(List.of(PHOTO, NOTES, TABLE, DRAFT), shownConflicts(ConflictFilter.ALL, Sort.PATH, false));
        assertEquals(List.of(NOTES, TABLE, DRAFT, PHOTO), shownConflicts(ConflictFilter.ALL, Sort.NAME, false));
        assertEquals(List.of(PHOTO, NOTES, TABLE, DRAFT), shownConflicts(ConflictFilter.ALL, Sort.SIZE, false));
        assertEquals(List.of(TABLE, DRAFT, PHOTO, NOTES), shownConflicts(ConflictFilter.ALL, Sort.DATE, false));
        assertEquals(List.of(TABLE, NOTES), shownConflicts(ConflictFilter.UNDECIDED, Sort.PATH, true));
    }

    @Test
    void theArrowMarksTheOrderInUse() {
        assertEquals("Hajm", Sort.SIZE.label(false, false));
        assertEquals("Hajm ↓", Sort.SIZE.label(true, false));
        assertEquals("Hajm ↑", Sort.SIZE.label(true, true));
    }

    private static final SyncAction FORCED = new SyncAction("z/f.txt", Direction.LAPTOP_TO_HDD, ActionType.CHANGED,
            SyncAction.Origin.MODE);

    @Test
    void overwritesHaveTheirOwnFilter() {
        List<SyncAction> all = List.of(REPORT, FORCED, OLD);
        Map<String, FilePair> facts = Map.of(REPORT.relativePath(), new FilePair(file(1, 1), file(1, 1)),
                FORCED.relativePath(), new FilePair(file(1, 1), file(1, 1)),
                OLD.relativePath(), new FilePair(ABSENT, file(1, 1)));

        assertEquals(List.of(FORCED), ChangeList.shown(all, facts::get, Filter.OVERWRITES, Sort.PATH, false));
        assertEquals(List.of(REPORT), ChangeList.shown(all, facts::get, Filter.TO_HDD, Sort.PATH, false));
        assertEquals(1, ChangeList.count(all, Filter.OVERWRITES));
        assertEquals("Ustidan yoziladi", Filter.OVERWRITES.label());
    }

    @Test
    void renameIsOneEntryAndItsDeleteAndNewAreHidden() {
        SyncAction gone = new SyncAction("a/old.txt", Direction.HDD_TO_LAPTOP, ActionType.DELETED);
        SyncAction created = new SyncAction("b/new.txt", Direction.HDD_TO_LAPTOP, ActionType.NEW);
        var rename = new uz.jtscorp.filesync.sync.PlanInterpreter.Rename("a/old.txt", "b/new.txt",
                Direction.HDD_TO_LAPTOP);
        List<SyncAction> changes = List.of(gone, created, SCAN);
        Map<String, FilePair> facts = Map.of(SCAN.relativePath(), new FilePair(file(1, 1), ABSENT),
                created.relativePath(), new FilePair(ABSENT, file(1, 1)));

        assertEquals(List.of(new ChangeList.Entry(created, rename), new ChangeList.Entry(SCAN, null)),
                ChangeList.shownEntries(changes, List.of(rename), facts::get, Filter.ALL, Sort.PATH, false));
        // It filters as its new row, not as the delete it replaces.
        assertEquals(List.of(), ChangeList.shownEntries(changes, List.of(rename), facts::get,
                Filter.DELETES, Sort.PATH, false));
    }
}
