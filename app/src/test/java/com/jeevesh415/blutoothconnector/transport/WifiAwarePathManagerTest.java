package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class WifiAwarePathManagerTest {
    private static final byte[] PREFIX =
            "BCL-AWARE-1:".getBytes(StandardCharsets.UTF_8);

    @Test public void parsesValidAwarePort() {
        byte[] port =
                "41871".getBytes(StandardCharsets.US_ASCII);
        byte[] message = new byte[PREFIX.length + port.length];
        System.arraycopy(PREFIX, 0, message, 0, PREFIX.length);
        System.arraycopy(port, 0, message, PREFIX.length, port.length);

        assertEquals(
                41871,
                WifiAwarePathManager.parsePort(message));
    }

    @Test public void rejectsWrongPrefixAndInvalidPort() {
        assertEquals(
                -1,
                WifiAwarePathManager.parsePortForTest(
                        "BCL-WRONG-1:41871"
                                .getBytes(StandardCharsets.US_ASCII)));
        assertEquals(
                -1,
                WifiAwarePathManager.parsePortForTest(
                        "BCL-AWARE-1:70000"
                                .getBytes(StandardCharsets.US_ASCII)));
        assertEquals(
                -1,
                WifiAwarePathManager.parsePortForTest(
                        "BCL-AWARE-1:abc"
                                .getBytes(StandardCharsets.US_ASCII)));
    }

    @Test public void rejectsTruncatedMetadata() {
        assertTrue(
                WifiAwarePathManager.parsePortForTest(
                        Arrays.copyOf(PREFIX, PREFIX.length)) < 0);
    }
}
