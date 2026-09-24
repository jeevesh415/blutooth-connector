package com.jeevesh415.blutoothconnector.transport;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MultipathCryptoTest {
    @Test public void authorizationAndEncryptionRoundTrip() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] token = new byte[MultipathCrypto.TOKEN_BYTES];
        byte[] hash = new byte[MultipathCrypto.HASH_BYTES];
        byte[] iv = new byte[MultipathCrypto.GCM_IV_BYTES];
        random.nextBytes(token);
        random.nextBytes(hash);
        random.nextBytes(iv);

        String id = "transfer-1";
        String name = "payload.bin";
        byte[] plaintext =
                "multipath-information".getBytes(StandardCharsets.UTF_8);

        byte[] aad = MultipathCrypto.descriptor(
                id, 128, 0, plaintext.length, hash,
                0, 1, name, iv);
        byte[] tag = MultipathCrypto.authorizationTag(
                token, id, 128, 0, plaintext.length, hash,
                0, 1, name, iv);

        assertTrue(MultipathCrypto.verifyAuthorizationTag(
                token, tag, id, 128, 0, plaintext.length, hash,
                0, 1, name, iv));

        byte[] tamperedDescriptorTag = MultipathCrypto.authorizationTag(
                token, id, 128, 0, plaintext.length, hash,
                0, 1, name, iv);
        tamperedDescriptorTag[0] ^= 0x01;
        assertFalse(MultipathCrypto.verifyAuthorizationTag(
                token, tamperedDescriptorTag, id, 128, 0, plaintext.length, hash,
                0, 1, name, iv));

        byte[] ciphertext = MultipathCrypto.encrypt(
                token, id, 0, plaintext, aad, iv);
        byte[] recovered = MultipathCrypto.decrypt(
                token, id, 0, ciphertext, aad, iv);

        assertArrayEquals(plaintext, recovered);
    }

    @Test public void tamperingIsDetected() throws Exception {
        byte[] token = new byte[MultipathCrypto.TOKEN_BYTES];
        Arrays.fill(token, (byte) 0x21);
        byte[] hash = new byte[MultipathCrypto.HASH_BYTES];
        Arrays.fill(hash, (byte) 0x42);
        byte[] iv = new byte[MultipathCrypto.GCM_IV_BYTES];
        Arrays.fill(iv, (byte) 0x09);

        byte[] plaintext = new byte[128];
        Arrays.fill(plaintext, (byte) 0x55);

        byte[] aad = MultipathCrypto.descriptor(
                "transfer-2", 256, 128, plaintext.length,
                hash, 1, 2, "data.bin", iv);
        byte[] ciphertext = MultipathCrypto.encrypt(
                token, "transfer-2", 1, plaintext, aad, iv);
        ciphertext[0] ^= 0x01;

        boolean failed = false;
        try {
            MultipathCrypto.decrypt(
                    token, "transfer-2", 1, ciphertext, aad, iv);
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed);

        byte[] otherNameTag = MultipathCrypto.authorizationTag(
                token, "transfer-2", 256, 128, plaintext.length,
                hash, 1, 2, "other.bin", iv);
        assertFalse(MultipathCrypto.verifyAuthorizationTag(
                token, otherNameTag,
                "transfer-2", 256, 128, plaintext.length,
                hash, 1, 2, "data.bin", iv));
    }
}
