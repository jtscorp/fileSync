package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaneWidthsTest {

    @Test
    void theSidebarStaysInItsRangeAndStartsAtItsDefault() {
        assertEquals(236, PaneWidths.sidebar(Double.NaN));
        assertEquals(180, PaneWidths.sidebar(120));
        assertEquals(300, PaneWidths.sidebar(300));
        assertEquals(340, PaneWidths.sidebar(500));
    }

    @Test
    void thePanelLeavesTheListItsWidth() {
        // 1180 window - 236 sidebar - 7 grip = 937 beside the sidebar: 937 - 420 - 28 = 489.
        assertEquals(440, PaneWidths.detail(Double.NaN, 937));
        assertEquals(489, PaneWidths.detail(700, 937));
        assertEquals(320, PaneWidths.detail(200, 937));
    }

    @Test
    void inANarrowWindowThePanelKeepsItsMinimumAndTheListGivesWay() {
        // 960 - 236 - 7 = 717 beside the sidebar: 717 - 448 = 269, under the panel's 320.
        assertEquals(320, PaneWidths.detail(440, 717));
        assertEquals(320, PaneWidths.detail(Double.NaN, 717));
    }
}
