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
        Scheduler balanced = new BalancedCompletionScheduler();
        check(balanced.requiresBandwidth(), "balanced requires bandwidth");
        check(balanced.chunksOnFirstPath(100, new PathMetrics(0, 14350), new PathMetrics(2000, 14350)) == 55,
                "RTT milliseconds and byte-rate units");
        check(balanced.chunksOnFirstPath(100, new PathMetrics(1000, 28700), new PathMetrics(3000, 14350)) == 73,
                "different RTT and bandwidth");
        check(balanced.chunksOnFirstPath(100, new PathMetrics(0, 14350), new PathMetrics(40000, 14350)) == 100,
                "clamp to whole window");
        check(balanced.chunksOnFirstPath(100, new PathMetrics(40000, 14350), new PathMetrics(0, 14350)) == 0,
                "clamp to zero");
        check(balanced.chunksOnFirstPath(0, new PathMetrics(10, 100), new PathMetrics(20, 200)) == 0, "balanced empty");
        check(balanced.chunksOnFirstPath(256, new PathMetrics(10, Double.MAX_VALUE),
                new PathMetrics(10, Double.MAX_VALUE)) == 128, "balanced overflow-safe");
        for (double invalid : new double[]{Double.NaN, 0, -1, Double.POSITIVE_INFINITY}) {
            check(balanced.chunksOnFirstPath(100, new PathMetrics(40, invalid), new PathMetrics(10, 100)) == 0,
                    "balanced bandwidth fallback");
        }
        check(!balanced.hasUsableMetrics(new PathMetrics(Double.NaN, 100), new PathMetrics(20, 100)),
                "invalid RTT triggers recorded fallback");
        Random cases = new Random(42);
        for (int i = 0; i < 1000; i++) {
            int count = 1 + cases.nextInt(256);
            PathMetrics a = new PathMetrics(cases.nextDouble() * 1000, 1000 + cases.nextDouble() * 1000000);
            PathMetrics b = new PathMetrics(cases.nextDouble() * 1000, 1000 + cases.nextDouble() * 1000000);
            int chosen = balanced.chunksOnFirstPath(count, a, b);
            check(chosen >= 0 && chosen <= count, "balanced allocation bounds");
            // Independent oracle: enumerate all integer splits and minimize completion-time difference.
            double chosenError = Math.abs(a.rttMs / 2000 + chosen * 1435.0 / a.bandwidth
                    - b.rttMs / 2000 - (count - chosen) * 1435.0 / b.bandwidth);
            for (int n = 0; n <= count; n++) {
                double error = Math.abs(a.rttMs / 2000 + n * 1435.0 / a.bandwidth
                        - b.rttMs / 2000 - (count - n) * 1435.0 / b.bandwidth);
                check(chosenError <= error + 1e-10, "balanced integer solution");
            }
            PathMetrics equalA = new PathMetrics(100, a.bandwidth), equalB = new PathMetrics(100, b.bandwidth);
            check(balanced.chunksOnFirstPath(count, equalA, equalB)
                    == ratio.chunksOnFirstPath(count, equalA, equalB), "equal RTT reduces to ratio");
        }
        System.out.println("PASS balanced units, latency shift, clamping, fallback, equal-RTT reduction and 1000 exhaustive split checks");
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
