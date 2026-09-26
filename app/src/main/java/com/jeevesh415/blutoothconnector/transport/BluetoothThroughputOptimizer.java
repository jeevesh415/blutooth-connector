package com.jeevesh415.blutoothconnector.transport;

import android.bluetooth.BluetoothAdapter;
import android.os.Build;

/**
 * Host-side throughput controller for large Bluetooth transfers.
 *
 * This class does not claim to increase the radio PHY rate. The controller,
 * negotiated PHY, channel conditions, and regulatory limits remain authoritative.
 * It maximizes application throughput by keeping the host-side pipe full while
 * adapting memory pressure to measured bandwidth and RTT.
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

    public synchronized void observe(long bytes, long elapsedNanos) {
        if (bytes <= 0 || elapsedNanos <= 0) return;
        double seconds = elapsedNanos / 1_000_000_000.0;
        double rate = bytes / seconds;
        ewmaBytesPerSecond = ewmaBytesPerSecond == 0
                ? rate
                : EWMA_ALPHA * rate + (1.0 - EWMA_ALPHA) * ewmaBytesPerSecond;
        ewmaRttSeconds = ewmaRttSeconds == 0
                ? seconds
                : EWMA_ALPHA * seconds + (1.0 - EWMA_ALPHA) * ewmaRttSeconds;
    }

    /**
     * Size the host pipe from the bandwidth-delay product:
     * window ~= k * R * T, with a bounded safety margin.
     */
    public synchronized int bufferBytes(int packetSize) {
        int packet = Math.max(1024, packetSize);
        double rate = ewmaBytesPerSecond > 0 ? ewmaBytesPerSecond : 2_000_000.0;
        double rtt = ewmaRttSeconds > 0 ? ewmaRttSeconds : 0.020;
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
