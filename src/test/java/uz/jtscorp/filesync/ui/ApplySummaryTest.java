package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.ui.ApplySummary.Mark;
import uz.jtscorp.filesync.ui.ApplySummary.Row;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplySummaryTest {

    private static SyncAction change(String path) {
        return new SyncAction(path, SyncAction.Direction.LAPTOP_TO_HDD, SyncAction.ActionType.CHANGED);
    }

    private static SyncAction conflict(String path) {
        return new SyncAction(path, SyncAction.Direction.CONFLICT, SyncAction.ActionType.CONFLICT);
    }

    @Test
    void oneRowPerOutcomeWithFileNames() {
        List<SyncAction> actions = List.of(change("h/a.docx"), change("s/b.jpg"), change("e/c.txt"),
                conflict("n/kundalik.md"), conflict("k/config.yaml"), conflict("sh/ijara.pdf"));
        ApplyPlan plan = new ApplyPlan(List.of("n/kundalik.md"), List.of(), List.of("k/config.yaml"), List.of());

        assertEquals(List.of(
                new Row(Mark.DONE, "3 ta o'zgarish o'tkazildi", "a.docx, b.jpg …"),
                new Row(Mark.DONE, "1 ta konflikt bir tomon tanlab hal qilindi", "kundalik.md"),
                new Row(Mark.DONE, "1 ta konflikt birlashtirildi", "config.yaml"),
                new Row(Mark.NONE, "1 ta konflikt tegilmadi", "ijara.pdf")),
                ApplySummary.rows(actions, plan, 1, false, List.of()));
    }

    @Test
    void aFailedMergeIsNotReportedAsMerged() {
        List<SyncAction> actions = List.of(conflict("k/config.yaml"));
        ApplyPlan plan = new ApplyPlan(List.of(), List.of(), List.of("k/config.yaml"), List.of());

        assertEquals(List.of(
                new Row(Mark.NONE, "0 ta o'zgarish o'tkazildi", ""),
                new Row(Mark.WARN, "1 ta konflikt uchun birlashtirish so'raldi — log'ga qarang", "config.yaml")),
                ApplySummary.rows(actions, plan, 1, true, List.of()));
    }

    @Test
    void aMergeRunThatFailedForSomeFilesNamesThem() {
        List<SyncAction> actions = List.of(conflict("n/kundalik.md"), conflict("k/config.yaml"));
        ApplyPlan plan = new ApplyPlan(List.of(), List.of(), List.of("n/kundalik.md", "k/config.yaml"), List.of());

        assertEquals(List.of(
                new Row(Mark.NONE, "0 ta o'zgarish o'tkazildi", ""),
                new Row(Mark.DONE, "1 ta konflikt birlashtirildi", "kundalik.md"),
                new Row(Mark.WARN, "1 ta konflikt birlashtirilmadi", "config.yaml")),
                ApplySummary.rows(actions, plan, 1, true, List.of("k/config.yaml")));
        assertEquals(new Row(Mark.DONE, "config.yaml — zaxira versiya qoldirildi", ""),
                ApplySummary.keptRow("k/config.yaml", false));
    }

    @Test
    void skippedFilesAreCountedOnceAndNotAsTransferred() {
        List<SyncAction> actions = List.of(change("h/byudjet.xlsx"), change("s/b.jpg"), conflict("sh/ijara.pdf"));
        ApplyPlan plan = new ApplyPlan(List.of(), List.of(), List.of(), List.of("h/byudjet.xlsx", "sh/ijara.pdf"));

        assertEquals(List.of(
                new Row(Mark.DONE, "1 ta o'zgarish o'tkazildi", "b.jpg"),
                new Row(Mark.NONE, "2 ta fayl o'tkazib yuborildi", "byudjet.xlsx, ijara.pdf")),
                ApplySummary.rows(actions, plan, 1, false, List.of()));
    }

    @Test
    void exitOneWithoutConflictsIsCalledOut() {
        List<SyncAction> actions = List.of(change("a.txt"));
        ApplyPlan none = new ApplyPlan(List.of(), List.of(), List.of(), List.of());

        assertEquals(List.of(
                new Row(Mark.DONE, "1 ta o'zgarish o'tkazildi", "a.txt"),
                new Row(Mark.WARN, "Unison ba'zi elementlarni o'tkazib yubordi — log'ga qarang", "")),
                ApplySummary.rows(actions, none, 1, false, List.of()));
        assertEquals(1, ApplySummary.rows(actions, none, 0, false, List.of()).size());
    }

    @Test
    void aMergeThatWasNotAllowedCountsAsUntouched() {
        List<SyncAction> actions = List.of(conflict("k/config.yaml"));
        // What ApplyPlan.from yields when the merge is dropped (e.g. backups switched off).
        ApplyPlan plan = new ApplyPlan(List.of(), List.of(), List.of(), List.of());

        assertEquals(List.of(
                new Row(Mark.NONE, "0 ta o'zgarish o'tkazildi", ""),
                new Row(Mark.NONE, "1 ta konflikt tegilmadi", "config.yaml")),
                ApplySummary.rows(actions, plan, 1, false, List.of()));
    }
}
