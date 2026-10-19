package uz.jtscorp.filesync.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Isolation Forest (Liu, Ting and Zhou, 2008): random trees split the data on random
 * features at random values, and a point that is isolated in few splits is unusual. The
 * score is {@code 2^(-E(h) / c(n))}, about 0.5 for an ordinary point and towards 1 for an
 * odd one. Hand-written and seeded, so the same data always gives the same score.
 */
public final class IsolationForest {

    static final int TREES = 100;
    /** Points each tree is built from: the paper's ψ. */
    static final int SAMPLE = 256;
    private static final double EULER = 0.5772156649;

    private sealed interface Node permits Split, Leaf {}

    private record Split(int feature, double value, Node left, Node right) implements Node {}

    /** @param size how many points ended here; their remaining depth is estimated, not built */
    private record Leaf(int size) implements Node {}

    private final List<Node> trees;
    private final int sample;

    private IsolationForest(List<Node> trees, int sample) {
        this.trees = trees;
        this.sample = sample;
    }

    public static IsolationForest fit(List<double[]> data, long seed) {
        Random random = new Random(seed);
        int sample = Math.min(SAMPLE, data.size());
        int heightLimit = (int) Math.ceil(Math.log(Math.max(2, sample)) / Math.log(2));
        List<Node> trees = new ArrayList<>();
        for (int t = 0; t < TREES && sample > 1; t++) {
            List<double[]> picked = new ArrayList<>(sample);
            for (int i = 0; i < sample; i++) {
                picked.add(data.get(random.nextInt(data.size())));
            }
            trees.add(build(picked, 0, heightLimit, random));
        }
        return new IsolationForest(trees, sample);
    }

    /** 0 when there was too little data to learn from: nothing is called odd on no evidence. */
    public double score(double[] point) {
        if (trees.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (Node tree : trees) {
            total += pathLength(point, tree, 0);
        }
        return Math.pow(2, -(total / trees.size()) / averagePath(sample));
    }

    private static Node build(List<double[]> points, int depth, int heightLimit, Random random) {
        if (depth >= heightLimit || points.size() <= 1) {
            return new Leaf(points.size());
        }
        int features = points.get(0).length;
        List<Integer> varying = new ArrayList<>();
        for (int f = 0; f < features; f++) {
            if (min(points, f) < max(points, f)) {
                varying.add(f);
            }
        }
        if (varying.isEmpty()) {
            return new Leaf(points.size());
        }
        int feature = varying.get(random.nextInt(varying.size()));
        double low = min(points, feature);
        double value = low + random.nextDouble() * (max(points, feature) - low);
        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();
        for (double[] p : points) {
            (p[feature] < value ? left : right).add(p);
        }
        return new Split(feature, value, build(left, depth + 1, heightLimit, random),
                build(right, depth + 1, heightLimit, random));
    }

    private static double pathLength(double[] point, Node node, int depth) {
        return switch (node) {
            case Leaf leaf -> depth + averagePath(leaf.size());
            case Split split -> pathLength(point, point[split.feature()] < split.value() ? split.left() : split.right(),
                    depth + 1);
        };
    }

    /** The average path length of an unsuccessful search in a binary search tree of n points: the paper's c(n). */
    static double averagePath(int n) {
        if (n <= 1) {
            return 0;
        }
        if (n == 2) {
            return 1;
        }
        return 2 * (Math.log(n - 1) + EULER) - 2.0 * (n - 1) / n;
    }

    private static double min(List<double[]> points, int feature) {
        double min = Double.POSITIVE_INFINITY;
        for (double[] p : points) {
            min = Math.min(min, p[feature]);
        }
        return min;
    }

    private static double max(List<double[]> points, int feature) {
        double max = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            max = Math.max(max, p[feature]);
        }
        return max;
    }
}
