package com.jeevesh415.blutoothconnector.protocol;

/**
 * Strict monotonic replay guard for reliable ordered control channels.
 */
public final class ControlReplayGuard {
    private long highest;

    public synchronized boolean accept(long sequence) {
        if (sequence <= 0 || sequence <= highest) return false;
        highest = sequence;
        return true;
    }

    public synchronized long highest() {
        return highest;
    }
}
