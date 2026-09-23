package com.jeevesh415.blutoothconnector.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class BulkTransferProtocol {
    private BulkTransferProtocol() {}

    public static final int MAGIC = 0x42434C31; // BCL1
    public static final int RESPONSE_MAGIC = 0x4241434B; // BACK
    public static final int VERSION = 1;
    public static final int TOKEN_BYTES = 32;
    public static final int HASH_BYTES = 32;
    public static final int MAX_NAME_BYTES = 512;
    public static final int BUFFER_BYTES = 1024 * 1024;

    public static final int STATUS_OK = 0;
    public static final int STATUS_REJECTED = 1;
    public static final int STATUS_INTEGRITY_ERROR = 2;
    public static final int STATUS_IO_ERROR = 3;

    public static final class Request {
        public final byte[] token;
        public final long fileSize;
        public final long offset;
        public final byte[] sha256;
        public final String fileName;

        Request(byte[] token, long fileSize, long offset, byte[] sha256, String fileName) {
            this.token = token;
            this.fileSize = fileSize;
            this.offset = offset;
            this.sha256 = sha256;
            this.fileName = fileName;
        }
    }

    public static void writeRequest(DataOutputStream out, byte[] token, long fileSize,
                                    long offset, byte[] sha256, String fileName) throws IOException {
        if (token == null || token.length != TOKEN_BYTES) throw new IOException("Invalid transfer token");
        if (sha256 == null || sha256.length != HASH_BYTES) throw new IOException("Invalid SHA-256");
        byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > MAX_NAME_BYTES) throw new IOException("Invalid file name");

        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(token.length);
        out.write(token);
        out.writeLong(fileSize);
        out.writeLong(offset);
        out.write(sha256);
        out.writeInt(name.length);
        out.write(name);
        out.flush();
    }

    public static Request readRequest(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Bad bulk magic");
        if (in.readInt() != VERSION) throw new IOException("Unsupported bulk version");

        int tokenLength = in.readInt();
        if (tokenLength != TOKEN_BYTES) throw new IOException("Bad token length");
        byte[] token = new byte[tokenLength];
        in.readFully(token);

        long fileSize = in.readLong();
        long offset = in.readLong();
        if (fileSize < 0 || offset < 0 || offset > fileSize) {
            throw new IOException("Invalid transfer offsets");
        }

        byte[] sha = new byte[HASH_BYTES];
        in.readFully(sha);

        int nameLength = in.readInt();
        if (nameLength <= 0 || nameLength > MAX_NAME_BYTES) {
            throw new IOException("Invalid file name length");
        }
        byte[] name = new byte[nameLength];
        in.readFully(name);

        return new Request(
                token, fileSize, offset, sha,
                new String(name, StandardCharsets.UTF_8));
    }

    public static void writeResponse(DataOutputStream out, int status, long offset)
            throws IOException {
        out.writeInt(RESPONSE_MAGIC);
        out.writeInt(VERSION);
        out.writeInt(status);
        out.writeLong(offset);
        out.flush();
    }

    public static long readResponse(DataInputStream in) throws IOException {
        if (in.readInt() != RESPONSE_MAGIC) throw new IOException("Bad bulk response");
        if (in.readInt() != VERSION) throw new IOException("Unsupported bulk response version");
        int status = in.readInt();
        long offset = in.readLong();
        if (status != STATUS_OK) throw new IOException("Bulk receiver rejected transfer: " + status);
        return offset;
    }

    public static byte[] sha256(java.io.File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = new java.io.BufferedInputStream(
                new java.io.FileInputStream(file), BUFFER_BYTES)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b));
        return out.toString();
    }

    public static byte[] decodeToken(String token) {
        return Base64.getDecoder().decode(token);
    }

    public static String encodeToken(byte[] token) {
        return Base64.getEncoder().encodeToString(token);
    }
}
