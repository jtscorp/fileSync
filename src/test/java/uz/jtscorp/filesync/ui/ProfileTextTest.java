package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.config.ProfileStats;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileTextTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 7, 9, 0, 0, 0, ZoneOffset.UTC);

    private static ProfileStats syncedAt(String instant) {
        return ProfileStats.NONE.withLastSync(Instant.parse(instant));
    }

    @Test
    void sidebarLineGoesFromTodayToADate() {
        assertEquals("Oxirgi: Bugun, 08:15", ProfileText.sidebarLine(true, syncedAt("2026-10-07T08:15:00Z"), NOW));
        // Yesterday by the calendar, although less than 24 hours ago.
        assertEquals("Oxirgi: Kecha, 22:10", ProfileText.sidebarLine(true, syncedAt("2026-10-06T22:10:00Z"), NOW));
        assertEquals("Oxirgi: 3 kun oldin", ProfileText.sidebarLine(true, syncedAt("2026-10-04T12:00:00Z"), NOW));
        assertEquals("Oxirgi: 28.09.2026", ProfileText.sidebarLine(true, syncedAt("2026-09-28T12:00:00Z"), NOW));
    }

    @Test
    void sidebarLineWithoutASyncSaysWhy() {
        assertEquals("Hali sinxronlanmagan", ProfileText.sidebarLine(true, ProfileStats.NONE, NOW));
        assertEquals("Sozlanmagan", ProfileText.sidebarLine(false, syncedAt("2026-10-07T08:15:00Z"), NOW));
    }

    @Test
    void countsGroupThousandsWithASpace() {
        assertEquals("12 480 fayl · 18 GB", ProfileText.counts(ProfileStats.NONE.withCounts(12_480, 19_542_101_197L)));
        assertEquals("7 fayl · 812 B", ProfileText.counts(ProfileStats.NONE.withCounts(7, 812)));
        assertEquals("", ProfileText.counts(ProfileStats.NONE));
    }

    @Test
    void syncHintAddsCountsOnceThereAreSome() {
        assertEquals("⇅ ikki tomonlama sinxronlash", ProfileText.syncHint(true, ProfileStats.NONE));
        assertEquals("⇅ ikki tomonlama sinxronlash · 7 fayl · 812 B",
                ProfileText.syncHint(true, ProfileStats.NONE.withCounts(7, 812)));
        assertEquals("⇅ ikki tomonlama sinxronlash · papkalar tanlanmagan",
                ProfileText.syncHint(false, ProfileStats.NONE.withCounts(7, 812)));
    }
}
