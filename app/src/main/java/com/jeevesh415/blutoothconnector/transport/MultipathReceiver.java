package com.jeevesh415.blutoothconnector.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receiver for striped BCL2 transfers.  Chunks may arrive concurrently and
 * in arbitrary order.  Completion is determined by the chunk bitmap followed
 * by one end-to-end SHA-256 verification.
 */
public final class MultipathReceiver {
    private static final int MAGIC = 0x42434C32;
    private static final int VERSION = 2;
    private static final int RESPONSE_MAGIC = 0x4241434B;
    private static final int MAX_ID = 64;
    private static final int MAX_NAME = 512;
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private MultipathReceiver() {}

    private static final class State {
        final Object lock = new Object();
        final File part;
        final File finalFile;
        final long fileSize;
        final byte[] hash;
        final int chunkCount;
        final BitSet complete;
        State(File part, File finalFile, long fileSize, byte[] hash, int chunkCount) {
            this.part = part;
            this.finalFile = finalFile;
            this.fileSize = fileSize;
            this.hash = hash;
            this.chunkCount = chunkCount;
            this.complete = new BitSet(chunkCount);
        }
    }

    public static File receive(Socket socket, File directory, byte[] expectedToken) throws Exception {
        DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(socket.getInputStream(), 1024 * 1024));
        DataOutputStream out = new DataOutputStream(
                new java.io.BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        return receive(in, out, directory, expectedToken);
    }

    public static File receive(DataInputStream in, DataOutputStream out,
                               File directory, byte[] expectedToken) throws Exception {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create transfer directory");
        }

        if (in.readInt() != MAGIC) throw new java.io.IOException("Bad BCL2 magic");
        if (in.readInt() != VERSION) throw new java.io.IOException("Unsupported BCL2 version");

        int tokenLength = in.readInt();
        if (tokenLength != 32) throw new java.io.IOException("Bad token length");
        byte[] token = new byte[tokenLength];
        in.readFully(token);
        if (!MessageDigest.isEqual(expectedToken, token)) {
            writeResponse(out, 1, 0);
            throw new SecurityException("Invalid bulk token");
        }

        int idLength = in.readInt();
        if (idLength <= 0 || idLength > MAX_ID) throw new java.io.IOException("Invalid transfer id");
        byte[] idBytes = new byte[idLength];
        in.readFully(idBytes);
        String transferId = new String(idBytes, StandardCharsets.UTF_8);

        long fileSize = in.readLong();
        long offset = in.readLong();
        long length = in.readLong();
        int chunkIndex = in.readInt();
        int chunkCount = in.readInt();
        if (fileSize < 0 || offset < 0 || length <= 0 || offset + length > fileSize
                || chunkIndex < 0 || chunkCount <= chunkIndex || chunkCount > 1_000_000) {
            throw new java.io.IOException("Invalid chunk range");
        }

        byte[] hash = new byte[32];
        in.readFully(hash);
        int nameLength = in.readInt();
        if (nameLength <= 0 || nameLength > MAX_NAME) throw new java.io.IOException("Invalid file name");
        byte[] name = new byte[nameLength];
        in.readFully(name);
        String safeName = new String(name, StandardCharsets.UTF_8)
                .replaceAll("[^a-zA-Z0-9._-]", "_");

        String stateKey = transferId + ":" + BulkTransferProtocol.hex(hash);
        State state = STATES.computeIfAbsent(stateKey, ignored -> {
            File part = new File(directory, BulkTransferProtocol.hex(hash) + "-" + transferId + ".part");
            File finalFile = new File(directory, safeName);
            return new State(part, finalFile, fileSize, hash, chunkCount);
        });

        if (state.fileSize != fileSize || state.chunkCount != chunkCount
                || !MessageDigest.isEqual(state.hash, hash)) {
            writeResponse(out, 2, 0);
            throw new java.io.IOException("Transfer metadata conflict");
        }

        byte[] buffer = new byte[(int) Math.min(1024 * 1024, length)];
        synchronized (state.lock) {
            try (RandomAccessFile raf = new RandomAccessFile(state.part, "rw")) {
                raf.seek(offset);
                long remaining = length;
                while (remaining > 0) {
                    int wanted = (int) Math.min(buffer.length, remaining);
                    int n = in.read(buffer, 0, wanted);
                    if (n < 0) throw new java.io.EOFException("Chunk disconnected");
                    raf.write(buffer, 0, n);
                    remaining -= n;
                }
            }
            state.complete.set(chunkIndex);
            boolean finished = state.complete.cardinality() == state.chunkCount;
            if (finished) {
                byte[] actual = BulkTransferProtocol.sha256(state.part);
                if (!MessageDigest.isEqual(actual, state.hash)) {
                    state.complete.clear(chunkIndex);
                    writeResponse(out, 3, length);
                    throw new java.io.IOException("Final SHA-256 integrity check failed");
                }
                if (state.finalFile.exists() && !state.finalFile.delete()) {
                    throw new java.io.IOException("Cannot replace existing file");
                }
                if (!state.part.renameTo(state.finalFile)) {
                    throw new java.io.IOException("Cannot finalize received file");
                }
                STATES.remove(stateKey);
            }
        }

        writeResponse(out, 0, length);
        return state.finalFile;
    }

    private static void writeResponse(DataOutputStream out, int status, long acknowledged)
            throws java.io.IOException {
        out.writeInt(RESPONSE_MAGIC);
        out.writeInt(VERSION);
        out.writeInt(status);
        out.writeLong(acknowledged);
        out.flush();
    }
}
