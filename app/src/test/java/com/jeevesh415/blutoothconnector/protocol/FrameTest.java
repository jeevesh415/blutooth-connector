package com.jeevesh415.blutoothconnector.protocol;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public final class FrameTest {
    @Test public void constructorPreservesEnvelopeWithoutAndroidJsonRuntime() {
        JSONObject payload = new JSONObject();
        Frame frame = new Frame(
                Protocol.VERSION,
                Protocol.COMMAND,
                42,
                1234L,
                payload);

        assertEquals(Protocol.VERSION, frame.version);
        assertEquals(Protocol.COMMAND, frame.type);
        assertEquals(42L, frame.sequence);
        assertEquals(1234L, frame.timestampMs);
        assertNotNull(frame.payload);
        assertSame(payload, frame.payload);
    }
}
