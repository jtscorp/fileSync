package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolationForestTest {

    private static List<double[]> cloud(int n) {
        Random random = new Random(1);
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            points.add(new double[] {random.nextGaussian(), random.nextGaussian()});
        }
        return points;
    }

    @Test
    void aPointFarFromTheRestScoresHighAndOneInTheMiddleLow() {
        IsolationForest forest = IsolationForest.fit(cloud(1000), 42);

        double outlier = forest.score(new double[] {8, 8});
        double inlier = forest.score(new double[] {0, 0});

        assertTrue(outlier >= 0.6, "outlier " + outlier);
        assertTrue(inlier < 0.5, "inlier " + inlier);
    }

    @Test
    void theSameSeedGivesTheSameScore() {
        double first = IsolationForest.fit(cloud(500), 42).score(new double[] {3, -2});
        double second = IsolationForest.fit(cloud(500), 42).score(new double[] {3, -2});

        assertEquals(first, second);
    }

    @Test
    void aFeatureThatNeverVariesIsNotSplitOn() {
        List<double[]> points = new ArrayList<>();
        for (double[] p : cloud(300)) {
            points.add(new double[] {p[0], 5});
        }
        IsolationForest forest = IsolationForest.fit(points, 42);

        assertTrue(forest.score(new double[] {0, 5}) < 0.5);
    }

    @Test
    void tooLittleDataScoresNothingAsOdd() {
        assertEquals(0, IsolationForest.fit(List.of(new double[] {1}), 42).score(new double[] {100}));
    }
}
