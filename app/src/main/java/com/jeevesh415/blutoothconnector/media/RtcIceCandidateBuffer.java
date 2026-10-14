package com.jeevesh415.blutoothconnector.media;

import org.webrtc.IceCandidate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded queue used while an SDP description is being applied or sent.
 * A candidate buffer is deliberately finite so malformed peers cannot grow
 * process memory without bound. Overflow is surfaced to the caller.
 */
final class RtcIceCandidateBuffer {
    static final int MAX_CANDIDATES = 256;

    private final ArrayDeque<IceCandidate> queue = new ArrayDeque<>();

    synchronized void offer(IceCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate");
        }
        if (queue.size() >= MAX_CANDIDATES) {
            throw new IllegalStateException("Too many pending ICE candidates");
        }
        queue.addLast(candidate);
    }

    synchronized List<IceCandidate> drain() {
        List<IceCandidate> drained = new ArrayList<>(queue.size());
        while (!queue.isEmpty()) {
            drained.add(queue.removeFirst());
        }
        return drained;
    }

    synchronized void clear() {
        queue.clear();
    }

    synchronized int size() {
        return queue.size();
    }
}
