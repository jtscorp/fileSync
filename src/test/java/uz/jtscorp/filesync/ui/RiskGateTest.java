package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.RiskFlag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskGateTest {

    @Test
    void noRisksMeansAnOpenGate() {
        RiskGate gate = RiskGate.from(List.of(), 0, false);

        assertTrue(gate.items().isEmpty());
        assertTrue(gate.allAcknowledged());
    }

    @Test
    void everyItemMustBeAcknowledgedOnItsOwn() {
        RiskGate gate = RiskGate.from(List.of(
                RiskFlag.forFile("b.xlsx", "Hajm keskin qisqargan"),
                RiskFlag.forFile("a.xlsx", "Hajm keskin qisqargan")), 0, false);

        assertEquals(List.of("a.xlsx", "b.xlsx"), gate.items().stream().map(RiskGate.Item::key).toList());
        assertEquals(2, gate.pendingCount());
        assertFalse(gate.allAcknowledged());

        gate.setAcknowledged("a.xlsx", true);
        assertEquals(1, gate.pendingCount());
        assertFalse(gate.allAcknowledged());
        assertTrue(gate.isPendingFile("b.xlsx"));
        assertFalse(gate.isPendingFile("a.xlsx"));

        gate.setAcknowledged("b.xlsx", true);
        assertTrue(gate.allAcknowledged());

        gate.setAcknowledged("b.xlsx", false);
        assertFalse(gate.allAcknowledged());
    }

    @Test
    void skippingAFileDecidesItAndUndoesAnAcknowledgement() {
        RiskGate gate = RiskGate.from(List.of(
                RiskFlag.forFile("a.xlsx", "x"), RiskFlag.forFile("b.xlsx", "x")), 0, false);

        gate.setAcknowledged("a.xlsx", true);
        gate.setSkipped("a.xlsx", true);
        gate.setSkipped("b.xlsx", true);
        assertFalse(gate.isAcknowledged("a.xlsx"), "a file is applied or skipped, never both");
        assertTrue(gate.allAcknowledged());
        assertFalse(gate.isPendingFile("a.xlsx"));
        assertEquals(List.of("a.xlsx", "b.xlsx"), gate.skippedPaths());

        gate.setAcknowledged("b.xlsx", true);
        assertEquals(List.of("a.xlsx"), gate.skippedPaths());
        assertTrue(gate.allAcknowledged());

        gate.setSkipped("a.xlsx", false);
        assertFalse(gate.allAcknowledged());
    }

    @Test
    void onlyAFileCanBeSkipped() {
        RiskGate gate = RiskGate.from(List.of(RiskFlag.forBatch("60 / 100 fayl")), 1, false);

        assertThrows(IllegalArgumentException.class, () -> gate.setSkipped("#batch-0", true));
        assertThrows(IllegalArgumentException.class, () -> gate.setSkipped(RiskGate.EXIT_ONE_KEY, true));
    }

    @Test
    void massChangeIsAnItemWithoutAPathAndComesFirst() {
        RiskGate gate = RiskGate.from(List.of(
                RiskFlag.forFile("a.xlsx", "Hajm keskin qisqargan"),
                RiskFlag.forBatch("60 / 100 fayl bir vaqtda o'zgargan")), 0, false);

        RiskGate.Item first = gate.items().getFirst();
        assertFalse(first.isFile());
        assertNull(first.path());
        assertEquals("Ommaviy o'zgarish", first.title());
        assertEquals(1, gate.fileCount());
        assertEquals(2, gate.pendingCount());
    }

    @Test
    void exitOneGatesOnlyWithoutConflicts() {
        assertTrue(RiskGate.from(List.of(), 1, true).items().isEmpty(),
                "conflict rows already explain an exit code of 1");

        RiskGate unexplained = RiskGate.from(List.of(), 1, false);
        assertEquals(List.of(RiskGate.EXIT_ONE_KEY),
                unexplained.items().stream().map(RiskGate.Item::key).toList());
        assertFalse(unexplained.allAcknowledged());
    }

    @Test
    void fileRiskWithoutPlanRowStillGates() {
        // The gate is built from RiskScanner's flags alone; whether Unison listed the
        // file is not its business.
        RiskGate gate = RiskGate.from(List.of(RiskFlag.forFile("faqat/skanerda.bin", "x")), 0, false);

        assertEquals(1, gate.pendingCount());
        assertTrue(gate.isPendingFile("faqat/skanerda.bin"));
    }

    @Test
    void acknowledgingAnUnknownKeyIsABug() {
        RiskGate gate = RiskGate.from(List.of(), 0, false);

        assertThrows(IllegalArgumentException.class, () -> gate.setAcknowledged("yo'q", true));
    }

    @Test
    void unreadablePlacesAreTheirOwnItemAndCanOnlyBeAcknowledged() {
        RiskGate gate = RiskGate.from(List.of(RiskFlag.forUnreadable("O'qib bo'lmadi (1 ta)")), 0, false);

        RiskGate.Item item = gate.items().getFirst();
        assertEquals("O'qilmagan joylar", item.title());
        assertFalse(gate.allAcknowledged());
        assertThrows(IllegalArgumentException.class, () -> gate.setSkipped(item.key(), true));
        gate.setAcknowledged(item.key(), true);
        assertTrue(gate.allAcknowledged());
    }
}
