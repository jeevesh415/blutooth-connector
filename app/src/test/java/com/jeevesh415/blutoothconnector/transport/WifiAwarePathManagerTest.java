package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test public void pmkDerivationIsSymmetric() {
        byte[] a = fill((byte) 0x11);
        byte[] b = fill((byte) 0x77);

        byte[] left = WifiAwarePathManager.derivePmk(a, b);
        byte[] right = WifiAwarePathManager.derivePmk(b, a);

        assertArrayEquals(left, right);
    }

    @Test public void pmkChangesWhenEitherTokenChanges() {
        byte[] a = fill((byte) 0x11);
        byte[] b = fill((byte) 0x77);
        byte[] changed = Arrays.copyOf(b, b.length);
        changed[0] ^= 0x01;

        assertFalse(Arrays.equals(
                WifiAwarePathManager.derivePmk(a, b),
                WifiAwarePathManager.derivePmk(a, changed)));
    }

    private static byte[] fill(byte value) {
        byte[] result = new byte[32];
        Arrays.fill(result, value);
        return result;
    }
}
