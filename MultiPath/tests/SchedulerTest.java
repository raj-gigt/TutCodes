import java.util.*;

public class SchedulerTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Scheduler ratio = new BandwidthRatioScheduler();
        check(ratio.chunksOnFirstPath(300, new PathMetrics(10, 200), new PathMetrics(40, 100)) == 200, "2:1 ratio");
        check(ratio.chunksOnFirstPath(256, new PathMetrics(10, 100), new PathMetrics(40, 100)) == 128, "equal BW ignores RTT");
        check(ratio.chunksOnFirstPath(300, new PathMetrics(10, 100), new PathMetrics(40, 200)) == 100, "updated estimates");
        check(ratio.chunksOnFirstPath(1, new PathMetrics(10, 100), new PathMetrics(40, 100)) == 1, "rounding");
        check(ratio.chunksOnFirstPath(0, new PathMetrics(10, 100), new PathMetrics(40, 100)) == 0, "empty window");
        for (double invalid : new double[]{Double.NaN, 0, -1, Double.POSITIVE_INFINITY}) {
            check(ratio.chunksOnFirstPath(256, new PathMetrics(40, invalid), new PathMetrics(10, 100)) == 0, "fallback");
        }
        check(ratio.chunksOnFirstPath(256, new PathMetrics(10, Double.MAX_VALUE), new PathMetrics(10, Double.MAX_VALUE)) == 128, "finite overflow");
        Scheduler min = new MinRttScheduler();
        check(min.chunksOnFirstPath(256, new PathMetrics(30, 100), new PathMetrics(10, 100)) == 0, "minRTT");
        for (int size : new int[]{0, 1, 7, 256}) {
            for (int first = 0; first <= size; first++) {
                List<List<Integer>> plan = Server.assign(100, size, first);
                check(plan.get(0).size() == first && plan.get(1).size() == size - first, "allocation counts");
                Set<Integer> seen = new HashSet<>();
                for (List<Integer> path : plan) {
                    int previous = 99;
                    for (int sequence : path) {
                        check(sequence > previous && seen.add(sequence), "ordered unique chunks");
                        previous = sequence;
                    }
                }
                check(seen.size() == size, "no missing chunks");
            }
        }
        AddDelay zero = new AddDelay(0, 0), fixed = new AddDelay(7, 0);
        check(zero.addDelay() == 0 && fixed.addDelay() == 7, "zero std");
        AddDelay gaussian = new AddDelay(10, 2, new Random(123));
        double total = 0, squares = 0;
        for (int i = 0; i < 100000; i++) {
            int d = gaussian.addDelay(); check(d >= 0, "nonnegative delay"); total += d; squares += d * d;
        }
        double mean = total / 100000, std = Math.sqrt(squares / 100000 - mean * mean);
        check(Math.abs(mean - 10) < .05 && Math.abs(std - 2) < .05, "Gaussian distribution");
        AddDelay clipped = new AddDelay(0, 10, new Random(1));
        for (int i = 0; i < 1000; i++) check(clipped.addDelay() >= 0, "negative samples clamped");
        System.out.println("PASS strategy allocation, updates, fallback, rounding, exact coverage and Gaussian delays");
    }
}
