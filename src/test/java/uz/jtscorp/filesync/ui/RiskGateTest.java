package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.ContentSignals;
import uz.jtscorp.filesync.sync.RiskFlag;
import uz.jtscorp.filesync.sync.SyncAction.Direction;

import java.time.Instant;
import java.time.ZoneOffset;
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

    @Test
    void irreversibleItemAppearsOnlyWithCounts() {
        assertTrue(RiskGate.from(List.of(), 0, false, 0, 0).items().isEmpty());

        RiskGate gate = RiskGate.from(List.of(RiskFlag.forFile("a.xlsx", "x")), 0, false, 3, 2);

        assertEquals(List.of(RiskGate.IRREVERSIBLE_KEY, "a.xlsx"),
                gate.items().stream().map(RiskGate.Item::key).toList());
        RiskGate.Item item = gate.items().get(0);
        assertNull(item.path());
        assertEquals("3 ta fayl o'chadi, 2 ta fayl ustidan yoziladi. Nusxa saqlanmaydi — "
                + "buni qaytarib bo'lmaydi.", item.reason());
        assertEquals(1, RiskGate.from(List.of(), 0, false, 0, 1).items().size());
        assertEquals(1, RiskGate.from(List.of(), 0, false, 1, 0).items().size());
    }

    @Test
    void irreversibleItemCanOnlyBeAcknowledged() {
        RiskGate gate = RiskGate.from(List.of(), 0, false, 1, 0);

        assertThrows(IllegalArgumentException.class,
                () -> gate.setSkipped(RiskGate.IRREVERSIBLE_KEY, true));
    }

    @Test
    void irreversibleItemBlocksUntilAcknowledged() {
        RiskGate gate = RiskGate.from(List.of(), 0, false, 1, 1);
        assertFalse(gate.allAcknowledged());

        gate.setAcknowledged(RiskGate.IRREVERSIBLE_KEY, true);

        assertTrue(gate.allAcknowledged());
    }

    private static final Instant AT = Instant.parse("2026-10-07T02:14:00Z");

    private static ChangeClusters.Cluster group(String id, boolean suspicious, String... paths) {
        return new ChangeClusters.Cluster(id, "2026/sentabr", List.of(paths), AT, AT, suspicious ? 5 : 0,
                suspicious ? ".locked" : "", suspicious ? Direction.LAPTOP_TO_HDD : null, suspicious);
    }

    private static RiskGate gateWith(List<RiskFlag> risks, ChangeClusters.Cluster... clusters) {
        return RiskGate.from(risks, 0, false, 0, 0, List.of(clusters), ZoneOffset.UTC);
    }

    @Test
    void onlyASuspiciousGroupIsAnItemAndItSaysWhatHappened() {
        RiskGate gate = gateWith(List.of(), group("#group:a", true, "a.jpg.locked", "a.jpg"),
                group("#group:b", false, "x", "y", "z"));

        assertEquals(List.of("#group:a"), gate.items().stream().map(RiskGate.Item::key).toList());
        RiskGate.Item item = gate.items().getFirst();
        assertEquals(RiskGate.Kind.CLUSTER, item.kind());
        assertNull(item.path());
        assertEquals(List.of("a.jpg.locked", "a.jpg"), item.members());
        assertEquals("5 ta fayl .locked ga qayta nomlangan", item.title());
        assertEquals("2026/sentabr ichidagi fayllar 07.10, 02:14 da bir vaqtda yangi kengaytmaga o'tgan, "
                + "eski nusxalari o'chgan. Bu to'lov dasturi (ransomware) shifrlashining belgisi. "
                + "Qo'llansa, zaxiradagi asl nusxalar ham o'chadi.", item.reason());
    }

    @Test
    void aGroupIsDecidedByItsAcknowledgementOrBySkippingEveryFileInIt() {
        RiskGate gate = gateWith(List.of(), group("#group:a", true, "1", "2"));
        assertFalse(gate.allAcknowledged());

        gate.setGroupSkipped("#group:a", true);
        assertTrue(gate.allAcknowledged());
        assertTrue(gate.isGroupSkipped("#group:a"));
        assertEquals(List.of("1", "2"), gate.skippedPaths());

        // The bulk bar brings one back: the group waits again.
        gate.setPathSkipped("2", false);
        assertFalse(gate.allAcknowledged());
        assertEquals(List.of("1"), gate.skippedPaths());

        gate.setAcknowledged("#group:a", true);
        assertTrue(gate.allAcknowledged());
        assertTrue(gate.skippedPaths().isEmpty(), "applying the group brings all its files back");
    }

    @Test
    void acknowledgingAGroupNeverAcknowledgesAFlaggedFileInIt() {
        RiskGate gate = gateWith(List.of(RiskFlag.forFile("2", "Hajm keskin qisqargan")),
                group("#group:a", true, "1", "2"));
        gate.setGroupSkipped("#group:a", true);
        assertTrue(gate.allAcknowledged(), "skipping the group skips the flagged file too");

        gate.setAcknowledged("#group:a", true);

        assertFalse(gate.isAcknowledged("2"));
        assertTrue(gate.isPendingFile("2"));
        assertEquals(1, gate.pendingCount());
    }

    @Test
    void onlyAGroupMemberOrAFlaggedFileKeepsItsSkipInTheGate() {
        RiskGate gate = gateWith(List.of(RiskFlag.forFile("f", "x")), group("#group:a", true, "1"));

        assertTrue(gate.keepsSkip("1"));
        assertTrue(gate.keepsSkip("f"));
        assertFalse(gate.keepsSkip("other"));
        assertThrows(IllegalArgumentException.class, () -> gate.setPathSkipped("other", true));
        assertThrows(IllegalArgumentException.class, () -> gate.setSkipped("#group:a", true));
        assertThrows(IllegalArgumentException.class, () -> gate.setGroupSkipped("f", true));
    }

    @Test
    void theMassChangeItemSaysItsKind() {
        RiskGate gate = RiskGate.from(List.of(RiskFlag.forBatch("60 / 100")), 0, false);

        assertEquals(RiskGate.Kind.MASS_CHANGE, gate.items().getFirst().kind());
    }

    @Test
    void aFileWithTwoFlagsIsOneItemWithBothReasonsAndTheContentKind() {
        ContentSignals.Finding finding = new ContentSignals.Finding(ContentSignals.Signal.MAGIC, true, "pdf",
                0, 0, 0, 0, new byte[0], new byte[0], 10);
        RiskGate gate = RiskGate.from(List.of(
                RiskFlag.forFile("a.pdf", "Hajm keskin qisqargan."),
                RiskFlag.forContent("a.pdf", "Sarlavha mos emas.", finding)), 0, false);

        assertEquals(1, gate.items().size());
        RiskGate.Item item = gate.items().getFirst();
        assertEquals("Hajm keskin qisqargan. Sarlavha mos emas.", item.reason());
        assertEquals(RiskGate.Kind.CONTENT, item.kind());
        assertEquals(finding, item.finding());
        assertEquals(1, gate.pendingCount());

        gate.setSkipped("a.pdf", true);
        assertTrue(gate.allAcknowledged(), "one decision covers both flags");
    }

    private static final List<Integer> DOCUMENTS = List.of(14, 9, 22, 17, 31, 12, 8, 19, 25, 16, 11, 27, 13, 18,
            21, 10, 24, 15, 29, 12, 17, 20, 14, 23);

    private static RiskGate withBaseline(List<RiskFlag> risks, Baseline baseline) {
        return RiskGate.from(risks, 0, false, 0, 0, List.of(), baseline, ZoneOffset.UTC);
    }

    @Test
    void withEnoughHistoryTheBaselineReplacesTheFixedMassChangeRule() {
        RiskGate gate = withBaseline(List.of(RiskFlag.forBatch("60 / 100"), RiskFlag.forUnreadable("x")),
                new Baseline(DOCUMENTS, 87));

        assertEquals(List.of("#batch-0", RiskGate.BASELINE_KEY),
                gate.items().stream().map(RiskGate.Item::key).toList(), "the unreadable places still gate");
        RiskGate.Item item = gate.item(RiskGate.BASELINE_KEY).orElseThrow();
        assertEquals(RiskGate.Kind.BASELINE, item.kind());
        assertEquals("Odatdagidan ko'p o'zgarish", item.title());
        assertEquals("Odatda 10–27 ta fayl o'zgaradi (oxirgi 24 ta tekshiruv), bu safar 87 ta. Bu profil uchun "
                + "chegara — 47 ta. Disk nosozligi yoki tasodifiy ommaviy o'chirish bo'lishi mumkin.", item.reason());
        assertThrows(IllegalArgumentException.class, () -> gate.setSkipped(RiskGate.BASELINE_KEY, true));
        gate.setAcknowledged("#batch-0", true);
        assertFalse(gate.allAcknowledged());
        gate.setAcknowledged(RiskGate.BASELINE_KEY, true);
        assertTrue(gate.allAcknowledged());
    }

    @Test
    void aNormalDayWithEnoughHistoryHasNoMassChangeItemAtAll() {
        RiskGate gate = withBaseline(List.of(RiskFlag.forBatch("60 / 100")), new Baseline(DOCUMENTS, 30));

        assertTrue(gate.items().isEmpty(), "not both: the profile's own normal decides");
    }

    @Test
    void duringAColdStartTheFixedRuleSaysItIsStillLearning() {
        RiskGate gate = withBaseline(List.of(RiskFlag.forBatch("19 / 30 fayl bir vaqtda o'zgargan.")),
                new Baseline(List.of(1, 2, 3, 4), 87));

        RiskGate.Item item = gate.items().getFirst();
        assertEquals(RiskGate.Kind.MASS_CHANGE, item.kind());
        assertEquals("19 / 30 fayl bir vaqtda o'zgargan. Profil me'yori hali o'rganilmoqda (4/10 tekshiruv), "
                + "shuning uchun qat'iy chegara ishlatildi.", item.reason());
    }

    @Test
    void reasonsJoinAsSentencesEvenWhenOneLacksItsFullStop() {
        assertEquals("Hajm keskin qisqargan: 2918 -> 6 bayt. Fayl buzilgan.",
                RiskGate.sentences(List.of("Hajm keskin qisqargan: 2918 -> 6 bayt", "Fayl buzilgan.")));
        assertEquals("Bir. Ikki", RiskGate.sentences(List.of("Bir.", "Ikki")));
        assertEquals("Yakka", RiskGate.sentences(List.of("Yakka")));
    }
}
