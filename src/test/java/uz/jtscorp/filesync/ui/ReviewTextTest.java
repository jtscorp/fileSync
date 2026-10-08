package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReviewTextTest {

    private static final FileFacts MISSING = new FileFacts(false, false, 0, null, null);

    private static FileFacts file(long size) {
        return new FileFacts(true, false, size, Instant.parse("2026-10-06T18:22:00Z"), null);
    }

    private static SyncAction action(Direction direction, ActionType type) {
        return new SyncAction("hisobot/byudjet.xlsx", direction, type);
    }

    @Test
    void splitsAPathIntoFolderAndName() {
        assertEquals(new ReviewText.PathParts("hisobot/", "byudjet.xlsx"), ReviewText.splitPath("hisobot/byudjet.xlsx"));
        assertEquals(new ReviewText.PathParts("a/b/", "c d.txt"), ReviewText.splitPath("a/b/c d.txt"));
        assertEquals(new ReviewText.PathParts("", "yakka.txt"), ReviewText.splitPath("yakka.txt"));
    }

    @Test
    void sizesReadOldToNewOnTheSideThatWillChange() {
        FilePair pair = new FilePair(file(184 * 1024), file(179 * 1024));

        assertEquals("179 KB → 184 KB",
                ReviewText.sizes(action(Direction.LAPTOP_TO_HDD, ActionType.CHANGED), pair));
        assertEquals("184 KB → 179 KB",
                ReviewText.sizes(action(Direction.HDD_TO_LAPTOP, ActionType.CHANGED), pair));
    }

    @Test
    void sizesForNewDeletedAndDirectory() {
        assertEquals("2,4 MB", ReviewText.sizes(action(Direction.LAPTOP_TO_HDD, ActionType.NEW),
                new FilePair(file(2_516_582), MISSING)));
        // Deleted on the laptop, so the HDD copy is the one that goes away.
        assertEquals("812 B → —", ReviewText.sizes(action(Direction.LAPTOP_TO_HDD, ActionType.DELETED),
                new FilePair(MISSING, file(812))));
        assertEquals("papka", ReviewText.sizes(action(Direction.HDD_TO_LAPTOP, ActionType.NEW),
                new FilePair(MISSING, new FileFacts(true, true, 4096, Instant.EPOCH, null))));
    }

    @Test
    void directionSaysWhichSideIsWrittenOrLosesTheFile() {
        assertEquals("→ Zaxiraga", ReviewText.direction(action(Direction.LAPTOP_TO_HDD, ActionType.NEW)));
        assertEquals("← Asosiyga", ReviewText.direction(action(Direction.HDD_TO_LAPTOP, ActionType.CHANGED)));
        assertEquals("Zaxiradan o'chadi", ReviewText.direction(action(Direction.LAPTOP_TO_HDD, ActionType.DELETED)));
        assertEquals("Asosiydan o'chadi", ReviewText.direction(action(Direction.HDD_TO_LAPTOP, ActionType.DELETED)));
    }

    @Test
    void actionLineForTheDetailPanel() {
        assertEquals("Asosiy nusxa zaxiraga o'tkaziladi",
                ReviewText.actionLine(action(Direction.LAPTOP_TO_HDD, ActionType.CHANGED)));
        assertEquals("Zaxira nusxa asosiyga o'tkaziladi (o'chirish)",
                ReviewText.actionLine(action(Direction.HDD_TO_LAPTOP, ActionType.DELETED)));
    }

    @Test
    void riskShortFollowsThePlanDirection() {
        // The empty HDD copy is about to replace the laptop's 48 KB.
        assertEquals("48 KB → 0 B", ReviewText.riskShort(action(Direction.HDD_TO_LAPTOP, ActionType.CHANGED),
                new FilePair(file(48 * 1024), file(0))));
    }

    @Test
    void riskShortWorksWithoutAnAction() {
        // Flagged by the scanner but not in Unison's plan, or a conflict: no direction
        // to follow, so read it as the drop it is.
        assertEquals("48 KB → 0 B", ReviewText.riskShort(null, new FilePair(file(0), file(48 * 1024))));
        assertEquals("48 KB → 0 B", ReviewText.riskShort(action(Direction.CONFLICT, ActionType.CONFLICT),
                new FilePair(file(48 * 1024), file(0))));
    }

    @Test
    void summaryNamesOnlyWhatThereIs() {
        assertEquals("4 ta o'zgarish · 2 ta konflikt yechimi · 1 ta tegilmaydi", ReviewText.applySummary(4, 2, 1));
        assertEquals("4 ta o'zgarish", ReviewText.applySummary(4, 0, 0));
    }

    @Test
    void summaryForAnEmptyPlan() {
        assertEquals("0 ta o'zgarish", ReviewText.applySummary(0, 0, 0));
    }

    @Test
    void gateAndRiskTitles() {
        assertEquals("1 ta xavf bo'yicha qaror kerak", ReviewText.gateText(1));
        assertEquals("", ReviewText.gateText(0));
        assertEquals("2 ta shubhali o'zgarish — qo'llashdan oldin qaror qiling", ReviewText.riskTitle(2));
        assertEquals("Shubhali o'zgarishlar ko'rib chiqildi", ReviewText.riskTitle(0));
        assertEquals("o'zgarish · 3→Zaxira, 1→Asosiy", ReviewText.statsLabel(3, 1));
        assertEquals("qaror kutilmoqda", ReviewText.riskState(false, false));
        assertEquals("qo'llanadi", ReviewText.riskState(true, false));
        assertEquals("o'tkazib yuboriladi", ReviewText.riskState(false, true));
    }

    @Test
    void mergeTooltipExplainsWhyMergeIsOff() {
        assertNull(ReviewText.mergeTooltip(true, true, true));
        assertEquals("Profil sozlamasida 'birlashtirishga ruxsat' o'chiq", ReviewText.mergeTooltip(true, false, true));
        assertEquals("Faqat matnli fayllar birlashtiriladi", ReviewText.mergeTooltip(false, true, true));
        assertEquals("Faqat matnli fayllar birlashtiriladi", ReviewText.mergeTooltip(false, false, true));
        assertEquals("diff3 dasturi topilmadi — chap pastdagi dasturlar qatorini bosing",
                ReviewText.mergeTooltip(true, true, false));
    }

    @Test
    void timeAndNewerSide() {
        assertEquals("06.10.2026 18:22", ReviewText.time(file(1), ZoneOffset.UTC));
        assertEquals("—", ReviewText.time(MISSING, ZoneOffset.UTC));

        FileFacts older = new FileFacts(true, false, 1, Instant.parse("2026-10-01T00:00:00Z"), null);
        assertEquals(-1, ReviewText.newerSide(new FilePair(file(1), older)));
        assertEquals(1, ReviewText.newerSide(new FilePair(older, file(1))));
        assertEquals(0, ReviewText.newerSide(new FilePair(file(1), MISSING)));
    }

    @Test
    void typeLetters() {
        assertEquals("N", ReviewText.typeLetter(ActionType.NEW));
        assertEquals("M", ReviewText.typeLetter(ActionType.CHANGED));
        assertEquals("D", ReviewText.typeLetter(ActionType.DELETED));
        assertEquals("C", ReviewText.typeLetter(ActionType.CONFLICT));
    }
}
