package com.jeevesh415.blutoothconnector.transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final int MAX_HISTORY = 32;

    /**
     * Immutable point-in-time diagnostics for a scheduled path.
     *
     * Throughput is in bytes/second, latency is in milliseconds, and
     * allocationFraction is the share assigned by the current schedule.
     */
    public static final class PathSnapshot {
        public final String id;
        public final String kind;
        public final double estimatedBytesPerSecond;
        public final double estimatedRttMs;
        public final double failureRateEstimate;
        public final double instabilityScore;
        public final double utility;
        public final double allocationFraction;
        public final int throughputSamples;
        public final int rttSamples;

        private PathSnapshot(PathMetrics metrics, double allocationFraction) {
            this.id = metrics.id;
            this.kind = metrics.kind;
            this.estimatedBytesPerSecond = metrics.estimatedBytesPerSecond;
            this.estimatedRttMs = metrics.estimatedRttMs;
            this.failureRateEstimate = metrics.failureRateEstimate;
            this.instabilityScore = metrics.instabilityScore;
            this.utility = metrics.utility;
            this.allocationFraction = allocationFraction;
            this.throughputSamples = metrics.throughputSamples;
            this.rttSamples = metrics.rttSamples;
        }
    }

    public static final class Path {
        public final String id;
        public final String kind;
        private final Deque<Double> rate = new ArrayDeque<>();
        private final Deque<Double> rtt = new ArrayDeque<>();
        private double ewmaRate;
        private double ewmaRtt = 50.0;
        private double failureEwma;
        private int throughputSamples;
        private int rttSamples;

        public Path(String id, String kind) {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("path id");
            }
            this.id = id;
            this.kind = kind == null ? "unknown" : kind;
        }

        /**
         * Records valid measurements. Non-finite values are ignored so one
         * malformed timing/rate sample cannot poison all future allocations.
         * A failure penalty decays only when at least one valid success metric
         * is observed.
         */
        public synchronized void observe(
                double bytesPerSecond, double rttMs) {
            boolean observedSuccess = false;

            if (Double.isFinite(bytesPerSecond) && bytesPerSecond > 0) {
                ewmaRate = ewmaRate == 0
                        ? bytesPerSecond
                        : 0.2 * bytesPerSecond + 0.8 * ewmaRate;
                push(rate, bytesPerSecond);
                if (throughputSamples < Integer.MAX_VALUE) {
                    throughputSamples++;
                }
                observedSuccess = true;
            }

            if (Double.isFinite(rttMs) && rttMs >= 0) {
                ewmaRtt = 0.2 * rttMs + 0.8 * ewmaRtt;
                push(rtt, rttMs);
                if (rttSamples < Integer.MAX_VALUE) {
                    rttSamples++;
                }
                observedSuccess = true;
            }

            if (observedSuccess) {
                failureEwma *= 0.70;
            }
        }

        public synchronized void observeFailure() {
            failureEwma = 0.35 + 0.65 * failureEwma;
        }

        public synchronized double failureRateEstimate() {
            return failureEwma;
        }

        private static void push(Deque<Double> q, double value) {
            q.addLast(value);
            while (q.size() > MAX_HISTORY) {
                q.removeFirst();
            }
        }

        private synchronized PathMetrics metrics() {
            double instability = instability();
            double rateScore = Math.log1p(Math.max(0, ewmaRate));
            double latencyPenalty = Math.log1p(Math.max(0, ewmaRtt));
            double utility = rateScore
                    - 0.35 * latencyPenalty
                    - 0.8 * instability
                    - 1.5 * failureEwma;

            return new PathMetrics(
                    id,
                    kind,
                    ewmaRate,
                    ewmaRtt,
                    failureEwma,
                    instability,
                    utility,
                    throughputSamples,
                    rttSamples);
        }

        private double instability() {
            return spectralHighFrequencyEnergy(rate)
                    + 0.25 * spectralHighFrequencyEnergy(rtt);
        }

        /**
         * Computes a normalized high-frequency energy ratio. Scaling samples
         * before the DFT avoids overflow when valid finite observations are
         * unusually large.
         */
        private static double spectralHighFrequencyEnergy(
                Deque<Double> values) {
            int n = values.size();
            if (n < 4) return 0.0;

            Double[] x = values.toArray(new Double[0]);
            double scale = 0.0;
            for (double value : x) {
                scale = Math.max(scale, Math.abs(value));
            }
            if (scale == 0.0) return 0.0;

            double mean = 0.0;
            for (int i = 0; i < n; i++) {
                double normalized = x[i] / scale;
                mean += (normalized - mean) / (i + 1);
            }

            double total = 0.0;
            double high = 0.0;

            for (int k = 1; k < n / 2; k++) {
                double re = 0.0;
                double im = 0.0;
                for (int t = 0; t < n; t++) {
                    double centered = x[t] / scale - mean;
                    double angle = 2.0 * Math.PI * k * t / n;
                    re += centered * Math.cos(angle);
                    im -= centered * Math.sin(angle);
                }

                double energy = (re * re + im * im) / (n * n);
                total += energy;
                if (k >= n / 4) high += energy;
            }

            return total == 0.0 ? 0.0 : high / total;
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

    private static final class PathMetrics {
        final String id;
        final String kind;
        final double estimatedBytesPerSecond;
        final double estimatedRttMs;
        final double failureRateEstimate;
        final double instabilityScore;
        final double utility;
        final int throughputSamples;
        final int rttSamples;

        PathMetrics(
                String id,
                String kind,
                double estimatedBytesPerSecond,
                double estimatedRttMs,
                double failureRateEstimate,
                double instabilityScore,
                double utility,
                int throughputSamples,
                int rttSamples) {
            this.id = id;
            this.kind = kind;
            this.estimatedBytesPerSecond = estimatedBytesPerSecond;
            this.estimatedRttMs = estimatedRttMs;
            this.failureRateEstimate = failureRateEstimate;
            this.instabilityScore = instabilityScore;
            this.utility = utility;
            this.throughputSamples = throughputSamples;
            this.rttSamples = rttSamples;
        }
    }

    private static final class ScheduledPath {
        final PathMetrics metrics;
        final double fraction;

        ScheduledPath(PathMetrics metrics, double fraction) {
            this.metrics = metrics;
            this.fraction = fraction;
        }
    }

    private final Map<String, Path> paths = new HashMap<>();

    public synchronized Path path(String id, String kind) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("path id");
        }
        return paths.computeIfAbsent(id, ignored -> new Path(id, kind));
    }

    /**
     * Returns an immutable, utility-descending allocation list. Each path's
     * utility (including its spectral estimate) is computed once per call.
     */
    public synchronized List<Allocation> allocate() {
        List<ScheduledPath> scheduled = buildSchedule();
        List<Allocation> result = new ArrayList<>(scheduled.size());
        for (ScheduledPath path : scheduled) {
            result.add(new Allocation(path.metrics.id, path.fraction));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns immutable point-in-time path metrics in the same order as
     * allocate(). Metrics and allocations are calculated from one path
     * snapshot per path, avoiding mismatched diagnostics under concurrent
     * observations.
     */
    public synchronized List<PathSnapshot> snapshots() {
        List<ScheduledPath> scheduled = buildSchedule();
        List<PathSnapshot> result = new ArrayList<>(scheduled.size());
        for (ScheduledPath path : scheduled) {
            result.add(new PathSnapshot(path.metrics, path.fraction));
        }
        return Collections.unmodifiableList(result);
    }

    private List<ScheduledPath> buildSchedule() {
        if (paths.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, PathMetrics> metricsById = new HashMap<>();
        double maxUtility = Double.NEGATIVE_INFINITY;
        for (Path path : paths.values()) {
            PathMetrics metrics = path.metrics();
            metricsById.put(path.id, metrics);
            maxUtility = Math.max(maxUtility, metrics.utility);
        }

        Map<String, Double> weights = new HashMap<>();
        double denominator = 0.0;
        for (PathMetrics metrics : metricsById.values()) {
            double weight = Math.exp(Math.max(
                    -20.0,
                    Math.min(20.0, metrics.utility - maxUtility)));
            weights.put(metrics.id, weight);
            denominator += weight;
        }

        List<PathMetrics> sorted = new ArrayList<>(metricsById.values());
        sorted.sort(Comparator.comparingDouble(
                (PathMetrics metrics) -> metrics.utility).reversed());

        List<ScheduledPath> result = new ArrayList<>(sorted.size());
        for (PathMetrics metrics : sorted) {
            double weight = weights.get(metrics.id);
            double fraction = denominator == 0.0 ? 0.0 : weight / denominator;
            result.add(new ScheduledPath(metrics, fraction));
        }
        return result;
    }
}
