package com.jeevesh415.blutoothconnector.transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

public final class ConnectionMetrics {
    private static final int WINDOW = 128;
    private final Deque<Long> samplesMs = new ArrayDeque<>(WINDOW);
    private double ewmaMs = Double.NaN;
    private double variance = Double.NaN;
    private long samples;

    public synchronized void observe(long rttMs) {
        if (rttMs < 0) return;
        samples++;
        if (Double.isNaN(ewmaMs)) {
            ewmaMs = rttMs;
            variance = 0;
        } else {
            final double alpha = 0.20;
            final double beta = 0.20;
            double previous = ewmaMs;
            ewmaMs = alpha * rttMs + (1.0 - alpha) * ewmaMs;
            double delta = rttMs - previous;
            variance = beta * delta * delta + (1.0 - beta) * variance;
        }
        if (samplesMs.size() == WINDOW) samplesMs.removeFirst();
        samplesMs.addLast(rttMs);
    }

    public synchronized long count() { return samples; }
    public synchronized double ewmaMs() { return ewmaMs; }
    public synchronized double standardDeviationMs() {
        return Double.isNaN(variance) ? Double.NaN : Math.sqrt(Math.max(0, variance));
    }

    public synchronized long p95Ms() {
        if (samplesMs.isEmpty()) return -1;
        ArrayList<Long> sorted = new ArrayList<>(samplesMs);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    public synchronized long adaptiveTimeoutMs() {
        if (Double.isNaN(ewmaMs)) return 5000;
        double timeout = ewmaMs + 4.0 * standardDeviationMs();
        return (long) Math.max(1000, Math.min(30000, timeout));
    }
}
