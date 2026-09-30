package com.jeevesh415.blutoothconnector.transport;

import android.bluetooth.BluetoothAdapter;
import android.os.Build;

/**
 * Host-side throughput controller.
 *
 * This class never claims to increase the radio PHY rate. It sizes application
 * buffers from measured throughput and an independently measured RTT. A bulk
 * transfer duration is a throughput sample only; it is never treated as RTT.
 */
public final class BluetoothThroughputOptimizer {
    private static final int MIN_BUFFER = 64 * 1024;
    private static final int MAX_BUFFER = 8 * 1024 * 1024;
    private static final double EWMA_ALPHA = 0.20;

    private double ewmaBytesPerSecond;
    private double ewmaRttSeconds;

    public static Profile inspect(BluetoothAdapter adapter) {
        boolean le2m = Build.VERSION.SDK_INT >= 26
                && adapter != null
                && adapter.isLe2MPhySupported();
        boolean coded = Build.VERSION.SDK_INT >= 26
                && adapter != null
                && adapter.isLeCodedPhySupported();
        return new Profile(le2m, coded);
    }

    /** Record a completed transfer as a throughput sample only. */
    public synchronized void observeThroughput(long bytes, long elapsedNanos) {
        if (bytes <= 0 || elapsedNanos <= 0) {
            return;
        }
        double seconds = elapsedNanos / 1_000_000_000.0;
        double rate = bytes / seconds;
        ewmaBytesPerSecond = ewmaBytesPerSecond == 0
                ? rate
                : EWMA_ALPHA * rate
                        + (1.0 - EWMA_ALPHA) * ewmaBytesPerSecond;
    }

    /** Record an actual request/response RTT sample. */
    public synchronized void observeRtt(long rttNanos) {
        if (rttNanos <= 0) {
            return;
        }
        double seconds = rttNanos / 1_000_000_000.0;
        ewmaRttSeconds = ewmaRttSeconds == 0
                ? seconds
                : EWMA_ALPHA * seconds
                        + (1.0 - EWMA_ALPHA) * ewmaRttSeconds;
    }

    /**
     * Backwards-compatible alias. The second argument is explicitly a duration
     * of a throughput observation, not RTT.
     */
    public void observe(long bytes, long elapsedNanos) {
        observeThroughput(bytes, elapsedNanos);
    }

    public synchronized int bufferBytes(int packetSize) {
        int packet = Math.max(1024, packetSize);
        double rate = ewmaBytesPerSecond > 0
                ? ewmaBytesPerSecond
                : 2_000_000.0;

        // Until a real RTT sample exists, use a conservative 20 ms baseline.
        // This is a sizing fallback, not a measurement.
        double rtt = ewmaRttSeconds > 0
                ? ewmaRttSeconds
                : 0.020;

        long bdp = Math.round(rate * rtt);
        long target = Math.max(4L * packet, 4L * bdp);
        target = Math.max(MIN_BUFFER, Math.min(MAX_BUFFER, target));
        return (int) target;
    }

    public synchronized double estimatedBytesPerSecond() {
        return ewmaBytesPerSecond;
    }

    public synchronized double estimatedMbps() {
        return ewmaBytesPerSecond * 8.0 / 1_000_000.0;
    }

    public synchronized double estimatedRttMilliseconds() {
        return ewmaRttSeconds * 1000.0;
    }

    public static final class Profile {
        public final boolean le2mPhySupported;
        public final boolean leCodedPhySupported;

        private Profile(boolean le2mPhySupported, boolean leCodedPhySupported) {
            this.le2mPhySupported = le2mPhySupported;
            this.leCodedPhySupported = leCodedPhySupported;
        }

        public boolean highThroughputCapable() {
            return le2mPhySupported;
        }
    }
}
