package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeProfileTest {

    private static final Instant T = Instant.parse("2026-10-07T10:00:00Z");

    @Test
    void foldersCountTheirFilesSizesAndExtensions() {
        TreeProfile tree = new TreeProfile(10, 1);
        tree.add("rasmlar/a.jpg", 300, T);
        tree.add("rasmlar/b.JPG", 100, T);
        tree.add("rasmlar/c.png", 200, T);
        tree.add("readme", 7, T);

        assertEquals(3, tree.files("rasmlar"));
        assertEquals(2, tree.extensionCount("rasmlar", ".jpg"), "an extension counts whatever its case");
        assertEquals(0, tree.extensionCount("rasmlar", ".locked"));
        assertEquals(200.0, tree.meanSize("rasmlar"));
        assertEquals(1, tree.files(""));
        assertEquals(1, tree.extensionCount("", ""));
    }

    @Test
    void theSampleIsBoundedAndTheSameForTheSameSeed() {
        TreeProfile first = new TreeProfile(50, 7);
        TreeProfile second = new TreeProfile(50, 7);
        for (int i = 0; i < 10_000; i++) {
            first.add("f/" + i, i, T);
            second.add("f/" + i, i, T);
        }

        assertEquals(50, first.sample().size());
        assertEquals(first.sample(), second.sample());
        // A reservoir draws from the whole walk, not only its start.
        assertTrue(first.sample().stream().anyMatch(s -> s.size() > 5_000));
    }
}
