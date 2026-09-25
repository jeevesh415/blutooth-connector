package com.jeevesh415.blutoothconnector.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;

/**
 * Application-level identity for a bonded Bluetooth session.
 *
 * Bluetooth pairing protects the transport. This class adds a persistent
 * application identity, trust-on-first-use public-key pinning, and a signed
 * per-connection transcript so stale sessions and key replacement are rejected.
 */
public final class SessionAuthenticator {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "blutooth-connector.identity.ec";
    private static final String PREFS = "session_identity_pins";
    private static final String KEY_PREFIX = "peer_";
    public static final int NONCE_BYTES = 32;
    public static final int MAX_PUBLIC_KEY_BYTES = 512;

    private SessionAuthenticator() {}

    public static byte[] newNonce(java.security.SecureRandom random) {
        if (random == null) throw new IllegalArgumentException("random");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    public static byte[] publicKey(Context context) throws Exception {
        return getKeyPair(context).getPublic().getEncoded();
    }

    public static String publicKeyBase64(Context context) throws Exception {
        return Base64.encodeToString(publicKey(context), Base64.NO_WRAP);
    }

    public static byte[] sign(Context context, byte[] transcript) throws Exception {
        if (transcript == null) throw new IllegalArgumentException("transcript");
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(getKeyPair(context).getPrivate());
        signature.update(transcript);
        return signature.sign();
    }

    public static boolean verify(
            byte[] encodedPublicKey,
            byte[] transcript,
            byte[] signatureBytes) throws Exception {
        if (encodedPublicKey == null
                || encodedPublicKey.length == 0
                || encodedPublicKey.length > MAX_PUBLIC_KEY_BYTES
                || transcript == null
                || signatureBytes == null) {
            return false;
        }

        PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(
                        new X509EncodedKeySpec(encodedPublicKey));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(key);
        signature.update(transcript);
        return signature.verify(signatureBytes);
    }

    /**
     * Canonical, symmetric transcript. Address ordering makes both peers
     * construct exactly the same signed bytes without an initiator flag.
     */
    public static byte[] transcript(
            String addressA,
            byte[] publicKeyA,
            byte[] nonceA,
            String addressB,
            byte[] publicKeyB,
            byte[] nonceB) {
        if (!validAddress(addressA) || !validAddress(addressB)) {
            throw new IllegalArgumentException("Invalid Bluetooth address");
        }
        validateBlob(publicKeyA, "publicKeyA");
        validateBlob(publicKeyB, "publicKeyB");
        validateNonce(nonceA);
        validateNonce(nonceB);

        String a = normalize(addressA);
        String b = normalize(addressB);

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("BCL-AUTH-1");

            if (a.compareTo(b) <= 0) {
                writeTuple(out, a, publicKeyA, nonceA);
                writeTuple(out, b, publicKeyB, nonceB);
            } else {
                writeTuple(out, b, publicKeyB, nonceB);
                writeTuple(out, a, publicKeyA, nonceA);
            }

            out.flush();
            return bytes.toByteArray();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Unable to encode transcript", error);
        }
    }

    private static void writeTuple(
            DataOutputStream out,
            String address,
            byte[] publicKey,
            byte[] nonce) throws java.io.IOException {
        out.writeUTF(address);
        out.writeInt(publicKey.length);
        out.write(publicKey);
        out.writeInt(nonce.length);
        out.write(nonce);
    }

    public static byte[] pinnedPeer(
            Context context, String peerAddress) {
        if (context == null || !validAddress(peerAddress)) return null;
        String value = prefs(context)
                .getString(KEY_PREFIX + fingerprint(peerAddress), null);
        if (value == null) return null;
        try {
            byte[] key = Base64.decode(value, Base64.DEFAULT);
            return key.length == 0 ? null : key;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    public static void pinPeer(
            Context context,
            String peerAddress,
            byte[] publicKey) {
        if (context == null || !validAddress(peerAddress)) {
            throw new IllegalArgumentException("Invalid peer address");
        }
        validateBlob(publicKey, "publicKey");
        prefs(context).edit()
                .putString(
                        KEY_PREFIX + fingerprint(peerAddress),
                        Base64.encodeToString(publicKey, Base64.NO_WRAP))
                .apply();
    }

    public static String publicKeyFingerprint(byte[] publicKey) {
        validateBlob(publicKey, "publicKey");
        try {
            return hex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(publicKey));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static KeyPair getKeyPair(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("context");
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return new KeyPair(
                    store.getCertificate(ALIAS).getPublicKey(),
                    (java.security.PrivateKey)
                            store.getKey(ALIAS, null));
        }

        KeyPairGenerator generator =
                KeyPairGenerator.getInstance("EC", KEYSTORE);
        generator.initialize(
                new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String fingerprint(String address) {
        try {
            return hex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    normalize(address)
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String normalize(String address) {
        return address.trim().toUpperCase(Locale.US);
    }

    private static boolean validAddress(String address) {
        return address != null
                && address.matches(
                        "(?i)[0-9A-F]{2}(:[0-9A-F]{2}){5}");
    }

    private static void validateNonce(byte[] nonce) {
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("Invalid nonce");
        }
    }

    private static void validateBlob(byte[] value, String name) {
        if (value == null
                || value.length == 0
                || value.length > MAX_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException(name);
        }
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = alphabet[value >>> 4];
            chars[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(chars);
    }
}
