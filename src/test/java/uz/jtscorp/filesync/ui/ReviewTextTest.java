package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.ContentSignals.Finding;
import uz.jtscorp.filesync.sync.ContentSignals.Signal;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

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

    @Test
    void forcedAndManifestDeletesNameTheSide() {
        SyncAction pruned = new SyncAction("a.txt", Direction.LAPTOP_TO_HDD, ActionType.DELETED,
                SyncAction.Origin.MANIFEST);
        SyncAction mirrored = new SyncAction("a.txt", Direction.HDD_TO_LAPTOP, ActionType.DELETED,
                SyncAction.Origin.MODE);

        assertEquals("Zaxiradan o'chadi", ReviewText.direction(pruned));
        assertEquals("Asosiydan o'chadi", ReviewText.direction(mirrored));
    }

    @Test
    void forcedChangeIsAnOverwrite() {
        SyncAction toHdd = new SyncAction("a.txt", Direction.LAPTOP_TO_HDD, ActionType.CHANGED, SyncAction.Origin.MODE);
        SyncAction toLaptop = new SyncAction("a.txt", Direction.HDD_TO_LAPTOP, ActionType.CHANGED, SyncAction.Origin.MODE);

        assertEquals("Zaxirada ustidan yoziladi", ReviewText.direction(toHdd));
        assertEquals("Asosiyda ustidan yoziladi", ReviewText.direction(toLaptop));
        assertEquals("Asosiy nusxa zaxiraga o'tkaziladi (ustidan yozish)", ReviewText.actionLine(toHdd));
        assertEquals("← Asosiyga", ReviewText.direction(action(Direction.HDD_TO_LAPTOP, ActionType.CHANGED)));
    }

    @Test
    void folderSummaryCountsFilesAndBytes() {
        CheckResult.FolderContents folder = new CheckResult.FolderContents(java.util.List.of(
                new CheckResult.Item("a.txt", 1024, false), new CheckResult.Item("b/.cache", 2048, true)), 3072);

        assertEquals("2 fayl · 3,0 KB", ReviewText.folderSummary(folder));
    }

    @Test
    void renameNamesBothPaths() {
        assertEquals("Qayta nomlandi: a.txt → b.txt",
                ReviewText.rename(new uz.jtscorp.filesync.sync.PlanInterpreter.Rename("a.txt", "b.txt",
                        Direction.LAPTOP_TO_HDD)));
    }

    @Test
    void aChangeFolderCountsItsFilesByKindThenTheSkippedOnes() {
        List<SyncAction> files = List.of(
                new SyncAction("a/1", Direction.LAPTOP_TO_HDD, ActionType.NEW),
                new SyncAction("a/2", Direction.LAPTOP_TO_HDD, ActionType.CHANGED),
                new SyncAction("a/3", Direction.HDD_TO_LAPTOP, ActionType.CHANGED),
                new SyncAction("a/4", Direction.LAPTOP_TO_HDD, ActionType.DELETED),
                new SyncAction("a/5", Direction.HDD_TO_LAPTOP, ActionType.CHANGED, SyncAction.Origin.MODE));

        assertEquals("5 fayl · 2 → Zaxira · 1 ← Asosiy · 1 o'chadi · 1 ustidan yoziladi · 2 tasi o'tkaziladi",
                ReviewText.folderMeta(files, Set.of("a/1", "a/4")::contains));
        // A kind with no file and a skip count of nought are left out.
        assertEquals("1 fayl · 1 → Zaxira", ReviewText.folderMeta(files.subList(0, 1), path -> false));
    }

    @Test
    void aConflictFolderSaysHowManyAreStillUndecided() {
        assertEquals("3 fayl · 2 tasi tanlanmagan", ReviewText.conflictFolderMeta(3, 2));
        assertEquals("3 fayl", ReviewText.conflictFolderMeta(3, 0));
    }

    @Test
    void aGroupsTimeIsOneMomentOrARangeOnItsFirstDay() {
        Instant start = Instant.parse("2026-10-06T11:34:20Z");

        assertEquals("06.10, 11:34 da", ReviewText.groupWhen(start, start.plusSeconds(30), ZoneOffset.UTC));
        assertEquals("06.10, 11:34–11:40", ReviewText.groupWhen(start, start.plusSeconds(6 * 60), ZoneOffset.UTC));
        assertEquals("/", ReviewText.groupName(""));
        assertEquals("2026/sentabr", ReviewText.groupName("2026/sentabr"));
    }

    @Test
    void aGroupsMakeupNamesItsThreeLargestKindsThenTheTotalSize() {
        List<SyncAction> rows = new java.util.ArrayList<>();
        java.util.Map<String, FilePair> facts = new java.util.HashMap<>();
        for (int i = 0; i < 3; i++) {
            rows.add(new SyncAction(i + ".jpg", Direction.LAPTOP_TO_HDD, ActionType.DELETED));
            facts.put(i + ".jpg", new FilePair(MISSING, file(1024)));
            rows.add(new SyncAction(i + ".jpg.locked", Direction.LAPTOP_TO_HDD, ActionType.NEW));
            facts.put(i + ".jpg.locked", new FilePair(file(1024), MISSING));
        }
        rows.add(new SyncAction("izoh.txt", Direction.LAPTOP_TO_HDD, ActionType.NEW));
        rows.add(new SyncAction("README", Direction.LAPTOP_TO_HDD, ActionType.NEW));
        facts.put("izoh.txt", new FilePair(file(512), MISSING));
        facts.put("README", new FilePair(file(512), MISSING));

        assertEquals("3 .jpg o'chadi · 3 .locked → Zaxira · 1 .txt → Zaxira · 7,0 KB",
                ReviewText.groupMakeup(rows, facts::get));
    }

    @Test
    void aGroupsCountSaysHowManySitThisApplyOut() {
        assertEquals("18 fayl", ReviewText.groupCount(18, 0));
        assertEquals("18 fayl · 4 tasi o'tkaziladi", ReviewText.groupCount(18, 4));
    }

    private static Finding finding(Signal signal, boolean laptopBad) {
        return new Finding(signal, laptopBad, "pdf", 7.9912, 4.6061, 1.0, 0.004,
                new byte[] {(byte) 0xd4, 0x1f, (byte) 0x8a, 0x3c, 0, 1, 2, 3}, "%PDF-1.7".getBytes(),
                2_516_582);
    }

    @Test
    void eachSignalNamesTheBadCopyAndTheOneItWouldReplace() {
        assertEquals("Asosiy nusxaning mazmuni tasodifiy baytlarga o'xshaydi: entropiya 4,61 → 7,99 bit/bayt. "
                + "Fayl shifrlangan bo'lishi mumkin. Qo'llansa, Zaxira nusxa ham shu bilan almashtiriladi.",
                ReviewText.signalReason(finding(Signal.ENTROPY, true)));
        assertEquals("Kengaytmasi .pdf, lekin Zaxira nusxaning boshida PDF belgisi yo'q (d4 1f 8a 3c). "
                + "Fayl buzilgan bo'lishi mumkin. Qo'llansa, Asosiy nusxa shu bilan almashtiriladi.",
                ReviewText.signalReason(finding(Signal.MAGIC, false)));
        assertEquals("Zaxira nusxa 2,4 MB, lekin butunlay nol baytlardan iborat. Hajmi o'zgarmagani uchun hajm "
                + "tekshiruvi buni ko'rmaydi — diskdagi sektorlar o'chgan bo'lishi mumkin. Qo'llansa, Asosiy "
                + "nusxa ham shu bilan almashtiriladi.", ReviewText.signalReason(finding(Signal.ZEROS, false)));
    }

    @Test
    void aSignalsShortLabelFitsBesideAPath() {
        assertEquals("entropiya 4,61 → 7,99", ReviewText.signalShort(finding(Signal.ENTROPY, true)));
        assertEquals(".pdf, lekin PDF emas", ReviewText.signalShort(finding(Signal.MAGIC, true)));
        assertEquals("hammasi nol bayt", ReviewText.signalShort(finding(Signal.ZEROS, true)));
    }

    @Test
    void englishWritesADecimalPoint() {
        Texts.use(Texts.Language.EN);
        try {
            assertEquals("entropy 4.61 → 7.99", ReviewText.signalShort(finding(Signal.ENTROPY, true)));
            assertEquals("1 content signal · 2 groups", ReviewText.analysisDone(1, 2));
        } finally {
            Texts.use(Texts.Language.UZ);
        }
    }

    @Test
    void theAnalysisRowPutsEachValueUnderItsSide() {
        ReviewText.AnalysisRow entropy = ReviewText.analysis(finding(Signal.ENTROPY, true));
        assertEquals(new ReviewText.AnalysisRow("Entropiya, bit/bayt", "7,99", "4,61"), entropy);
        // The backup copy is the bad one: its header is shown in hex, the good one as text.
        assertEquals(new ReviewText.AnalysisRow("Fayl boshi", "%PDF-1.7", "d4 1f 8a 3c"),
                ReviewText.analysis(finding(Signal.MAGIC, false)));
        assertEquals(new ReviewText.AnalysisRow("Nol baytlar", "0%", "100%"),
                ReviewText.analysis(finding(Signal.ZEROS, false)));
    }

    @Test
    void theFourthStageCountsSignalsAndGroups() {
        assertEquals("3 ta kontent belgisi · 0 ta guruh", ReviewText.analysisDone(3, 0));
    }

    @Test
    void theModelsMarkSaysItsScoreAndEveryReason() {
        Oddity.Mark mark = new Oddity.Mark(0.6849, List.of(new Oddity.Reason(Oddity.Why.NIGHT, "03:41"),
                new Oddity.Reason(Oddity.Why.STALE, "6")));

        assertEquals("◆ G'ayrioddiy 0,68 · kechasi 03:41 da o'zgargan, 6 yil tegilmagan edi", ReviewText.oddLine(mark));
        assertEquals("bu papkada .locked kengaytmasi uchramagan · eski nusxasi o'chgan", ReviewText.reasons(
                new Oddity.Mark(0.9, List.of(new Oddity.Reason(Oddity.Why.RARE_EXT, ".locked"),
                        new Oddity.Reason(Oddity.Why.PAIR, ""))), " · "));
        assertEquals("G'ayrioddiylik modeli 10 ta tekshiruvdan keyin yoqiladi (hozir 4 ta)", ReviewText.modelCold(4));
        assertEquals("3 ta kontent belgisi · 2 ta guruh · 4 ta g'ayrioddiy", ReviewText.analysisDone(3, 2, 4));
    }

    @Test
    void englishCountsTheYearsUntouched() {
        Texts.use(Texts.Language.EN);
        try {
            assertEquals("untouched for 1 year", ReviewText.reason(new Oddity.Reason(Oddity.Why.STALE, "1")));
            assertEquals("untouched for 6 years", ReviewText.reason(new Oddity.Reason(Oddity.Why.STALE, "6")));
        } finally {
            Texts.use(Texts.Language.UZ);
        }
    }
}
