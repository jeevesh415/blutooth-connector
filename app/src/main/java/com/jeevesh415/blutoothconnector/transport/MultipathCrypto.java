package com.jeevesh415.blutoothconnector.transport;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public final class MultipathCrypto {
    public static final int TOKEN_BYTES = 32;
    public static final int HASH_BYTES = 32;
    public static final int GCM_IV_BYTES = 12;
    public static final int GCM_TAG_BYTES = 16;

    private MultipathCrypto() {}

    public static byte[] authorizationTag(
            byte[] token,
            String transferId,
            long fileSize,
            long offset,
            long length,
            byte[] fileHash,
            int chunkIndex,
            int chunkCount,
            String fileName,
            byte[] iv) throws Exception {
        validateSecret(token);
        byte[] descriptor = descriptor(
                transferId, fileSize, offset, length, fileHash,
                chunkIndex, chunkCount, fileName, iv);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token, "HmacSHA256"));
        return mac.doFinal(descriptor);
    }

    public static boolean verifyAuthorizationTag(
            byte[] token,
            byte[] supplied,
            String transferId,
            long fileSize,
            long offset,
            long length,
            byte[] fileHash,
            int chunkIndex,
            int chunkCount,
            String fileName,
            byte[] iv) throws Exception {
        if (supplied == null || supplied.length != HASH_BYTES) return false;
        byte[] expected = authorizationTag(
                token, transferId, fileSize, offset, length, fileHash,
                chunkIndex, chunkCount, fileName, iv);
        return MessageDigest.isEqual(expected, supplied);
    }

    public static byte[] encrypt(
            byte[] token,
            String transferId,
            int chunkIndex,
            byte[] plaintext,
            byte[] aad,
            byte[] iv) throws Exception {
        validateIv(iv);
        validateChunkIndex(chunkIndex);
        if (!MessageDigest.isEqual(iv, deterministicIv(transferId, chunkIndex))) {
            throw new IllegalArgumentException("IV is not bound to transfer/chunk");
        }
        if (plaintext == null) throw new IllegalArgumentException("plaintext");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key(token, transferId), "AES"),
                new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    public static byte[] decrypt(
            byte[] token,
            String transferId,
            int chunkIndex,
            byte[] ciphertext,
            byte[] aad,
            byte[] iv) throws Exception {
        validateIv(iv);
        validateChunkIndex(chunkIndex);
        if (!MessageDigest.isEqual(iv, deterministicIv(transferId, chunkIndex))) {
            throw new IllegalArgumentException("IV is not bound to transfer/chunk");
        }
        if (ciphertext == null) throw new IllegalArgumentException("ciphertext");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key(token, transferId), "AES"),
                new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    public static byte[] descriptor(
            String transferId,
            long fileSize,
            long offset,
            long length,
            byte[] fileHash,
            int chunkIndex,
            int chunkCount,
            String fileName,
            byte[] iv) throws Exception {
        if (transferId == null || transferId.isEmpty()
                || fileHash == null || fileHash.length != HASH_BYTES
                || fileName == null || fileName.isEmpty()
                || fileSize < 1 || offset < 0 || length < 1
                || chunkIndex < 0 || chunkCount <= chunkIndex) {
            throw new IllegalArgumentException("Invalid transfer metadata");
        }
        validateIv(iv);

        byte[] id = transferId.getBytes(StandardCharsets.UTF_8);
        byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream(160 + id.length + name.length);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(id.length);
        out.write(id);
        out.writeLong(fileSize);
        out.writeLong(offset);
        out.writeLong(length);
        out.writeInt(chunkIndex);
        out.writeInt(chunkCount);
        out.write(fileHash);
        out.writeInt(name.length);
        out.write(name);
        out.write(iv);
        out.flush();
        return bytes.toByteArray();
    }

    /**
     * Derives a unique GCM IV from the transfer identity and chunk index.
     * The transfer id is generated with UUID.randomUUID(), while the chunk
     * index makes retries of the same chunk reuse the same nonce safely.
     */
    public static byte[] deterministicIv(String transferId, int chunkIndex) throws Exception {
        if (transferId == null || transferId.isEmpty()) {
            throw new IllegalArgumentException("transferId");
        }
        validateChunkIndex(chunkIndex);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("BCL3-GCM-IV".getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(transferId.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update((byte) (chunkIndex >>> 24));
        digest.update((byte) (chunkIndex >>> 16));
        digest.update((byte) (chunkIndex >>> 8));
        digest.update((byte) chunkIndex);
        return Arrays.copyOf(digest.digest(), GCM_IV_BYTES);
    }

    private static void validateChunkIndex(int chunkIndex) {
        if (chunkIndex < 0) throw new IllegalArgumentException("chunkIndex");
    }

    private static byte[] key(byte[] token, String transferId) throws Exception {
        validateSecret(token);
        if (transferId == null || transferId.isEmpty()) {
            throw new IllegalArgumentException("transferId");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("BCL3-AES-256".getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(token);
        digest.update((byte) 0);
        digest.update(transferId.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private static void validateSecret(byte[] token) {
        if (token == null || token.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("Invalid BCL3 secret");
        }
    }

    private static void validateIv(byte[] iv) {
        if (iv == null || iv.length != GCM_IV_BYTES) {
            throw new IllegalArgumentException("Invalid GCM IV");
        }
    }
}
