package uz.jtscorp.filesync.ui;

import java.util.List;

/**
 * This profile's own normal amount of change, from the change counts of its past syncs: a
 * photo archive that changes by five files and a code folder that changes by thirty need
 * different limits. The median and the MAD are used so that one odd past check does not move
 * the normal. Free of JavaFX so the numbers have a plain test.
 *
 * @param history the change counts of past syncs, oldest first
 * @param today   this check's change count
 */
record Baseline(List<Integer> history, int today) {

    /** Fewer past syncs than this are no normal yet: the fixed rule stays in force. */
    static final int HISTORY_MIN = 10;
    /** How many MADs above the median still count as normal: a robust "4 sigma". */
    private static final double SIGMAS = 4;
    /** Scales the MAD to a standard deviation for normally distributed counts. */
    private static final double MAD_TO_SIGMA = 1.4826;
    /** Headroom above the tallest bar of the chart. */
    private static final double CHART_HEADROOM = 1.08;

    Baseline {
        history = List.copyOf(history);
    }

    boolean enough() {
        return history.size() >= HISTORY_MIN;
    }

    private List<Integer> sorted() {
        return history.stream().sorted().toList();
    }

    /** The value at this fraction of the sorted history, the way the design reads it. */
    private int at(double fraction) {
        List<Integer> sorted = sorted();
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(fraction * sorted.size())));
    }

    int median() {
        return at(0.5);
    }

    /** The median absolute deviation from the median. */
    int mad() {
        int median = median();
        List<Integer> deviations = history.stream().map(v -> Math.abs(v - median)).sorted().toList();
        return deviations.get(deviations.size() / 2);
    }

    int limit() {
        return (int) Math.ceil(median() + SIGMAS * MAD_TO_SIGMA * Math.max(1, mad()));
    }

    /** The usual range's bottom, for the sentence and the chart. */
    int low() {
        return at(0.1);
    }

    int high() {
        return at(0.9);
    }

    /** More change than this profile's normal allows; never during a cold start. */
    boolean odd() {
        return enough() && today > limit();
    }

    /** The top of the chart's scale. */
    double chartTop() {
        int tallest = history.stream().mapToInt(Integer::intValue).max().orElse(0);
        return Math.max(Math.max(today, limit()), tallest) * CHART_HEADROOM;
    }
}
