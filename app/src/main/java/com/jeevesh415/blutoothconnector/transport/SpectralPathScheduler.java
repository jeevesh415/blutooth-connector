package com.jeevesh415.blutoothconnector.transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptive path scheduler with a small spectral model.
 *
 * The scheduler is deliberately application-layer: it allocates work among
 * paths exposed by Android and cannot create PHY bandwidth or path independence.
 */
public final class SpectralPathScheduler {
    public static final class Path {
        public final String id;
        public final String kind;
        private final Deque<Double> rate = new ArrayDeque<>();
        private final Deque<Double> rtt = new ArrayDeque<>();
        private double ewmaRate;
        private double ewmaRtt = 50.0;
        private double failureEwma;

        public Path(String id, String kind) {
            this.id = id;
            this.kind = kind == null ? "unknown" : kind;
        }

        public synchronized void observe(
                double bytesPerSecond, double rttMs) {
            if (bytesPerSecond > 0) {
                ewmaRate = ewmaRate == 0
                        ? bytesPerSecond
                        : 0.2 * bytesPerSecond
                                + 0.8 * ewmaRate;
                push(rate, bytesPerSecond);
            }

            if (rttMs >= 0) {
                ewmaRtt = 0.2 * rttMs
                        + 0.8 * ewmaRtt;
                push(rtt, rttMs);
            }

            failureEwma *= 0.70;
        }

        public synchronized void observeFailure() {
            failureEwma =
                    0.35 + 0.65 * failureEwma;
        }

        public synchronized double failureRateEstimate() {
            return failureEwma;
        }

        private static void push(
                Deque<Double> q, double value) {
            q.addLast(value);
            while (q.size() > 32) {
                q.removeFirst();
            }
        }

        private synchronized double instability() {
            return spectralHighFrequencyEnergy(rate)
                    + 0.25
                    * spectralHighFrequencyEnergy(rtt);
        }

        private static double spectralHighFrequencyEnergy(
                Deque<Double> values) {
            int n = values.size();
            if (n < 4) return 0.0;

            Double[] x = values.toArray(new Double[0]);
            double mean = 0.0;
            for (double v : x) mean += v;
            mean /= n;

            double total = 0.0;
            double high = 0.0;

            for (int k = 1; k < n / 2; k++) {
                double re = 0.0;
                double im = 0.0;
                for (int t = 0; t < n; t++) {
                    double centered = x[t] - mean;
                    double angle =
                            2.0 * Math.PI * k * t / n;
                    re += centered * Math.cos(angle);
                    im -= centered * Math.sin(angle);
                }

                double energy =
                        (re * re + im * im)
                                / (n * n);
                total += energy;
                if (k >= n / 4) high += energy;
            }

            return total == 0 ? 0 : high / total;
        }

        synchronized double utility() {
            double rateScore =
                    Math.log1p(
                            Math.max(0, ewmaRate));
            double latencyPenalty =
                    Math.log1p(
                            Math.max(0, ewmaRtt));
            double instabilityPenalty =
                    0.8 * instability();
            double failurePenalty =
                    1.5 * failureEwma;

            return rateScore
                    - 0.35 * latencyPenalty
                    - instabilityPenalty
                    - failurePenalty;
        }
    }

    public static final class Allocation {
        public final String pathId;
        public final double fraction;

        Allocation(String pathId, double fraction) {
            this.pathId = pathId;
            this.fraction = fraction;
        }
    }

    private final Map<String, Path> paths =
            new HashMap<>();

    public synchronized Path path(
            String id, String kind) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("path id");
        }
        return paths.computeIfAbsent(
                id,
                ignored -> new Path(id, kind));
    }

    public synchronized List<Allocation> allocate() {
        if (paths.isEmpty()) {
            return new ArrayList<>();
        }

        double max =
                Double.NEGATIVE_INFINITY;
        for (Path path : paths.values()) {
            max = Math.max(max, path.utility());
        }

        double denominator = 0.0;
        Map<Path, Double> weights =
                new HashMap<>();

        for (Path path : paths.values()) {
            double weight = Math.exp(
                    Math.max(
                            -20,
                            Math.min(
                                    20,
                                    path.utility() - max)));
            weights.put(path, weight);
            denominator += weight;
        }

        List<Path> sorted =
                new ArrayList<>(paths.values());
        sorted.sort(
                Comparator.comparingDouble(
                        Path::utility).reversed());

        List<Allocation> result =
                new ArrayList<>(sorted.size());
        for (Path path : sorted) {
            result.add(
                    new Allocation(
                            path.id,
                            denominator == 0
                                    ? 0
                                    : weights.get(path)
                                            / denominator));
        }
        return result;
    }
}
