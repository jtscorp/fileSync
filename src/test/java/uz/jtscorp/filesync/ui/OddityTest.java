package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.sync.TreeProfile;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;
import uz.jtscorp.filesync.ui.Oddity.Mark;
import uz.jtscorp.filesync.ui.Oddity.Reason;
import uz.jtscorp.filesync.ui.Oddity.Why;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OddityTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final FileFacts ABSENT = new FileFacts(false, false, 0, null, null);

    private final TreeProfile laptop = new TreeProfile();
    private final TreeProfile hdd = new TreeProfile();
    private final List<SyncAction> changes = new ArrayList<>();
    private final Map<String, FilePair> facts = new HashMap<>();

    /** A folder of ordinary documents, edited in office hours over the last two years. */
    OddityTest() {
        Random random = new Random(3);
        for (int i = 0; i < 400; i++) {
            // Office hours, 09:00 to 17:00.
            Instant when = NOW.minus(Duration.ofDays(1 + random.nextInt(700)))
                    .minus(Duration.ofMinutes(random.nextInt(8 * 60))).minus(Duration.ofHours(-5));
            String path = "hujjatlar/h" + i + ".docx";
            long size = 80_000 + random.nextInt(20_000);
            laptop.add(path, size, when);
            hdd.add(path, size, when);
        }
    }

    private static FileFacts file(long size, Instant modified) {
        return new FileFacts(true, false, size, modified, null);
    }

    private void change(String path, ActionType type, Instant now, Instant before, long size) {
        changes.add(new SyncAction(path, Direction.LAPTOP_TO_HDD, type));
        laptop.add(path, size, now);
        facts.put(path, new FilePair(file(size, now), before == null ? ABSENT : file(size, before)));
    }

    private Map<String, Mark> marks() {
        return Oddity.score(changes, facts::get, laptop, hdd, NOW, ZoneOffset.UTC);
    }

    @Test
    void aFileChangedAtNightAfterSixUntouchedYearsIsOddAndSaysWhy() {
        Instant night = Instant.parse("2026-10-07T03:41:00Z");
        change("hujjatlar/2019-shartnoma.docx", ActionType.CHANGED, night, Instant.parse("2020-02-12T09:15:00Z"),
                96_000);
        change("hujjatlar/h1.docx", ActionType.CHANGED, NOW.minus(Duration.ofHours(2)),
                NOW.minus(Duration.ofDays(30)), 90_000);

        Map<String, Mark> marks = marks();

        Mark odd = marks.get("hujjatlar/2019-shartnoma.docx");
        assertTrue(odd.score() >= Oddity.ODD, "score " + odd.score());
        assertEquals(List.of(new Reason(Why.NIGHT, "03:41"), new Reason(Why.STALE, "6")), odd.reasons());
        assertTrue(odd.odd());
        Mark ordinary = marks.get("hujjatlar/h1.docx");
        assertTrue(ordinary.score() < Oddity.ODD, "an office-hours edit is ordinary: " + ordinary.score());
        assertTrue(ordinary.reasons().isEmpty());
        assertFalse(ordinary.odd(), "a score without a reason is never shown");
    }

    @Test
    void aNewExtensionWhoseOriginalWasDeletedSaysBoth() {
        change("hujjatlar/h7.docx.locked", ActionType.NEW, NOW, null, 90_000);
        changes.add(new SyncAction("hujjatlar/h7.docx", Direction.LAPTOP_TO_HDD, ActionType.DELETED));
        facts.put("hujjatlar/h7.docx", new FilePair(ABSENT, file(90_000, NOW.minus(Duration.ofDays(9)))));

        Mark mark = marks().get("hujjatlar/h7.docx.locked");

        assertEquals(List.of(new Reason(Why.RARE_EXT, ".locked"), new Reason(Why.PAIR, "")), mark.reasons());
    }

    @Test
    void aDeletionHasNoTimeOfItsOwnAndIsNotScored() {
        changes.add(new SyncAction("hujjatlar/h2.docx", Direction.LAPTOP_TO_HDD, ActionType.DELETED));
        facts.put("hujjatlar/h2.docx", new FilePair(ABSENT, file(1, NOW)));

        assertTrue(marks().isEmpty());
    }

    @Test
    void theSameDataGivesTheSameScore() {
        change("hujjatlar/a.docx", ActionType.CHANGED, NOW, NOW.minus(Duration.ofDays(3000)), 90_000);

        assertEquals(marks().get("hujjatlar/a.docx").score(), marks().get("hujjatlar/a.docx").score());
    }

    @Test
    void theHourAloneMakesANightEditStandOutAgainstTheTreesOwnHours() {
        Instant before = NOW.minus(Duration.ofDays(40));
        change("hujjatlar/kunduz.docx", ActionType.CHANGED, Instant.parse("2026-10-07T14:00:00Z"), before, 90_000);
        change("hujjatlar/tun.docx", ActionType.CHANGED, Instant.parse("2026-10-07T03:10:00Z"), before, 90_000);

        Map<String, Mark> marks = marks();

        double day = marks.get("hujjatlar/kunduz.docx").score();
        double night = marks.get("hujjatlar/tun.docx").score();
        assertTrue(night > day + 0.05, "night " + night + " against day " + day);
    }
}
