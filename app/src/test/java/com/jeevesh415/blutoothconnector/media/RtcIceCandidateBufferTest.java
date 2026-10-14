package com.jeevesh415.blutoothconnector.media;

import org.junit.Test;
import org.webrtc.IceCandidate;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class RtcIceCandidateBufferTest {
    @Test
    public void drainsCandidatesInArrivalOrder() {
        RtcIceCandidateBuffer buffer = new RtcIceCandidateBuffer();
        IceCandidate first = new IceCandidate("0", 0, "candidate:first");
        IceCandidate second = new IceCandidate("0", 0, "candidate:second");

        buffer.offer(first);
        buffer.offer(second);
        List<IceCandidate> drained = buffer.drain();

        assertEquals(2, drained.size());
        assertEquals("candidate:first", drained.get(0).sdp);
        assertEquals("candidate:second", drained.get(1).sdp);
        assertEquals(0, buffer.size());
    }

    @Test
    public void rejectsOverflowInsteadOfSilentlyDroppingCandidates() {
        RtcIceCandidateBuffer buffer = new RtcIceCandidateBuffer();
        for (int i = 0; i < RtcIceCandidateBuffer.MAX_CANDIDATES; i++) {
            buffer.offer(new IceCandidate("0", 0, "candidate:" + i));
        }
        assertThrows(IllegalStateException.class,
                () -> buffer.offer(new IceCandidate("0", 0, "candidate:overflow")));
        assertEquals(RtcIceCandidateBuffer.MAX_CANDIDATES, buffer.size());
    }
}
