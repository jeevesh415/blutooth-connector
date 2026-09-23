package com.jeevesh415.blutoothconnector.transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptive path scheduler using a small spectral model.
 *
 * The transport graph is treated as a weighted graph.  For each path we keep
 * a short time series of throughput/RTT observations.  A discrete Fourier
 * projection separates the slowly varying component from high-frequency
 * instability.  The scheduler then allocates work using a softmax utility.
 *
 * This is intentionally an application-layer scheduler: it cannot change the
 * phone's PHY or Wi-Fi channel width.  It can, however, decide how much work
 * to put on each independently usable path.
 */
public final class SpectralPathScheduler {
    public static final class Path {
        public final String id;
        public final String kind;
        private final Deque<Double> rate = new ArrayDeque<>();
        private final Deque<Double> rtt = new ArrayDeque<>();
        private double ewmaRate;
        private double ewmaRtt = 50.0;

        public Path(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        public synchronized void observe(double bytesPerSecond, double rttMs) {
            if (bytesPerSecond > 0) {
                ewmaRate = ewmaRate == 0 ? bytesPerSecond : 0.2 * bytesPerSecond + 0.8 * ewmaRate;
                push(rate, bytesPerSecond);
            }
            if (rttMs >= 0) {
                ewmaRtt = 0.2 * rttMs + 0.8 * ewmaRtt;
                push(rtt, rttMs);
            }
        }

        private static void push(Deque<Double> q, double value) {
            q.addLast(value);
            while (q.size() > 32) q.removeFirst();
        }

        private synchronized double instability() {
            return spectralHighFrequencyEnergy(rate) + 0.25 * spectralHighFrequencyEnergy(rtt);
        }

        private static double spectralHighFrequencyEnergy(Deque<Double> values) {
            int n = values.size();
            if (n < 4) return 0.0;
            Double[] x = values.toArray(new Double[0]);
            double mean = 0;
            for (double v : x) mean += v;
            mean /= n;

            double total = 0;
            double high = 0;
            for (int k = 1; k < n / 2; k++) {
                double re = 0, im = 0;
                for (int t = 0; t < n; t++) {
                    double centered = x[t] - mean;
                    double a = 2.0 * Math.PI * k * t / n;
                    re += centered * Math.cos(a);
                    im -= centered * Math.sin(a);
                }
                double e = (re * re + im * im) / (n * n);
                total += e;
                if (k >= n / 4) high += e;
            }
            return total == 0 ? 0 : high / total;
        }

        synchronized double utility() {
            double rateScore = Math.log1p(Math.max(0, ewmaRate));
            double latencyPenalty = Math.log1p(Math.max(0, ewmaRtt));
            return rateScore - 0.35 * latencyPenalty - 0.8 * instability();
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

    private final Map<String, Path> paths = new HashMap<>();

    public synchronized Path path(String id, String kind) {
        return paths.computeIfAbsent(id, ignored -> new Path(id, kind));
    }

    public synchronized List<Allocation> allocate() {
        if (paths.isEmpty()) return new ArrayList<>();

        double max = Double.NEGATIVE_INFINITY;
        for (Path p : paths.values()) max = Math.max(max, p.utility());

        double denominator = 0;
        Map<Path, Double> weights = new HashMap<>();
        for (Path p : paths.values()) {
            double w = Math.exp(Math.max(-20, Math.min(20, p.utility() - max)));
            weights.put(p, w);
            denominator += w;
        }

        List<Path> sorted = new ArrayList<>(paths.values());
        sorted.sort(Comparator.comparingDouble(Path::utility).reversed());

        List<Allocation> result = new ArrayList<>();
        for (Path p : sorted) {
            result.add(new Allocation(p.id, denominator == 0 ? 0 : weights.get(p) / denominator));
        }
        return result;
    }
}
