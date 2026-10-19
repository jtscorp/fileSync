package uz.jtscorp.filesync.ui;

/**
 * How wide the sidebar and the review screen's detail panel may be dragged. A width that
 * was never set, or could not be read back, is {@code NaN} and gives the default.
 */
final class PaneWidths {

    static final double SIDEBAR = 236;
    static final double SIDEBAR_MIN = 180;
    static final double SIDEBAR_MAX = 340;
    static final double DETAIL = 440;
    static final double DETAIL_MIN = 320;
    /** What the review list keeps beside the panel, as long as the window allows it. */
    static final double LIST_MIN = 420;
    /** The panel's drag edge and its margin from the window's right edge. */
    static final double DETAIL_EDGE = 28;

    private PaneWidths() {}

    static double sidebar(double wanted) {
        return clamp(Double.isNaN(wanted) ? SIDEBAR : wanted, SIDEBAR_MIN, SIDEBAR_MAX);
    }

    /**
     * @param beside the width right of the sidebar. When it is too narrow for both, the
     *               panel keeps its minimum and the list gives way.
     */
    static double detail(double wanted, double beside) {
        double max = Math.max(DETAIL_MIN, beside - LIST_MIN - DETAIL_EDGE);
        return clamp(Double.isNaN(wanted) ? DETAIL : wanted, DETAIL_MIN, max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
