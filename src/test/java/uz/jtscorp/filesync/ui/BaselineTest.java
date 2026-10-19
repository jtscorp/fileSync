package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineTest {

    /** The design's Documents profile: 24 past checks. */
    private static final List<Integer> DOCUMENTS = List.of(14, 9, 22, 17, 31, 12, 8, 19, 25, 16, 11, 27, 13, 18,
            21, 10, 24, 15, 29, 12, 17, 20, 14, 23);

    @Test
    void theProfilesOwnNormalGivesItsUsualRangeAndItsLimit() {
        Baseline baseline = new Baseline(DOCUMENTS, 87);

        assertTrue(baseline.enough());
        assertEquals(17, baseline.median());
        assertEquals(5, baseline.mad());
        assertEquals(47, baseline.limit());
        assertEquals(10, baseline.low());
        assertEquals(27, baseline.high());
        assertTrue(baseline.odd());
        assertFalse(new Baseline(DOCUMENTS, 47).odd(), "the limit itself is still normal");
        assertTrue(new Baseline(DOCUMENTS, 48).odd());
    }

    @Test
    void oneOddPastCheckDoesNotMoveTheNormal() {
        List<Integer> history = new ArrayList<>(Collections.nCopies(11, 5));
        int before = new Baseline(history, 0).limit();
        history.add(5000);

        assertEquals(before, new Baseline(history, 0).limit());
    }

    @Test
    void aQuietProfileStillAllowsSomeChange() {
        // Every past check changed nothing: the MAD is 0, and max(1, MAD) keeps the limit above it.
        Baseline baseline = new Baseline(Collections.nCopies(10, 0), 6);

        assertEquals(6, baseline.limit());
        assertFalse(baseline.odd());
    }

    @Test
    void belowTenChecksThereIsNoNormalYet() {
        Baseline cold = new Baseline(Collections.nCopies(9, 3), 500);

        assertFalse(cold.enough());
        assertFalse(cold.odd(), "the fixed rule speaks during a cold start");
        assertFalse(new Baseline(List.of(), 10).enough());
    }

    @Test
    void theChartsScaleLeavesRoomAboveTheTallestOfTodayTheLimitAndThePast() {
        assertEquals(87 * 1.08, new Baseline(DOCUMENTS, 87).chartTop(), 1e-9);
        assertEquals(47 * 1.08, new Baseline(DOCUMENTS, 3).chartTop(), 1e-9);
    }
}
