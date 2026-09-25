package com.jeevesh415.blutoothconnector.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public final class SessionAuthenticatorTest {
    @Test public void transcriptIsSymmetric() {
        byte[] keyA = bytes(65, (byte) 0x11);
        byte[] keyB = bytes(65, (byte) 0x77);
        byte[] nonceA = bytes(SessionAuthenticator.NONCE_BYTES, (byte) 0x21);
        byte[] nonceB = bytes(SessionAuthenticator.NONCE_BYTES, (byte) 0x42);

        byte[] left = SessionAuthenticator.transcript(
                keyA, nonceA, keyB, nonceB);
        byte[] right = SessionAuthenticator.transcript(
                keyB, nonceB, keyA, nonceA);

        assertArrayEquals(left, right);
    }

    @Test public void transcriptChangesWhenNonceChanges() {
        byte[] keyA = bytes(65, (byte) 0x11);
        byte[] keyB = bytes(65, (byte) 0x77);
        byte[] nonceA = bytes(SessionAuthenticator.NONCE_BYTES, (byte) 0x21);
        byte[] nonceB = bytes(SessionAuthenticator.NONCE_BYTES, (byte) 0x42);
        byte[] changed = Arrays.copyOf(nonceB, nonceB.length);
        changed[0] ^= 0x01;

        assertFalse(Arrays.equals(
                SessionAuthenticator.transcript(keyA, nonceA, keyB, nonceB),
                SessionAuthenticator.transcript(keyA, nonceA, keyB, changed)));
    }

    @Test public void fingerprintIsDeterministic() {
        byte[] key = bytes(65, (byte) 0x55);
        assertEquals(
                SessionAuthenticator.publicKeyFingerprint(key),
                SessionAuthenticator.publicKeyFingerprint(key.clone()));
    }

    private static byte[] bytes(int count, byte value) {
        byte[] out = new byte[count];
        Arrays.fill(out, value);
        return out;
    }
}
