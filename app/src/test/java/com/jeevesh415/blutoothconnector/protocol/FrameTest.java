package com.jeevesh415.blutoothconnector.protocol;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FrameTest {
    @Test public void roundTripPreservesEnvelope() throws Exception {
        Frame frame = new Frame(
                Protocol.VERSION,
                Protocol.COMMAND,
                42,
                1234L,
                new JSONObject().put("requestId", "r1").put("x", 7));
        Frame decoded = Frame.fromBytes(frame.toBytes());

        assertEquals(Protocol.VERSION, decoded.version);
        assertEquals(Protocol.COMMAND, decoded.type);
        assertEquals(42L, decoded.sequence);
        assertEquals(1234L, decoded.timestampMs);
        assertTrue(decoded.payload.optInt("x") == 7);
    }
}
