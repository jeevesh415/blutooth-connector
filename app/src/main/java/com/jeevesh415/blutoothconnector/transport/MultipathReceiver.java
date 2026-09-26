package com.jeevesh415.blutoothconnector.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MultipathReceiver {
    private static final int MAGIC = 0x42434C32;
    private static final int VERSION = 3;
    private static final int RESPONSE_MAGIC = 0x4241434B;
    private static final int MAX_ID = 64;
    private static final int MAX_NAME = 512;
    private static final int MAX_CHUNKS = 1_000_000;
    private static final int MAX_CHUNK = 1024 * 1024;
    private static final int MAX_STORED_TRANSFERS = 64;
    private static final Map<String, State> STATES =
            new ConcurrentHashMap<>();

    private MultipathReceiver() {}

    private static final class State {
        final Object lock = new Object();
        final File part;
        final File finalFile;
        final String originalName;
        final long fileSize;
        final byte[] hash;
        final int chunkCount;
        final BitSet complete;
        volatile long lastTouchedMs;

        State(File part, File finalFile, String originalName,
              long fileSize, byte[] hash, int chunkCount) {
            this.part = part;
            this.finalFile = finalFile;
            this.originalName = originalName;
            this.fileSize = fileSize;
            this.hash = hash.clone();
            this.chunkCount = chunkCount;
            this.complete = new BitSet(chunkCount);
            this.lastTouchedMs = System.currentTimeMillis();
        }
    }

    public static File receive(
            Socket socket, File directory, byte[] expectedToken)
            throws Exception {
        DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(
                        socket.getInputStream(), MAX_CHUNK));
        DataOutputStream out = new DataOutputStream(
                new java.io.BufferedOutputStream(
                        socket.getOutputStream(), 64 * 1024));
        return receive(in, out, directory, expectedToken);
    }

    public static File receive(
            DataInputStream in,
            DataOutputStream out,
            File directory,
            byte[] expectedToken) throws Exception {
        if (expectedToken == null
                || expectedToken.length != MultipathCrypto.TOKEN_BYTES) {
            throw new SecurityException("Invalid expected token");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create transfer directory");
        }

        cleanupStaleStates(directory);

        if (in.readInt() != MAGIC) {
            throw new java.io.IOException("Bad BCL3 magic");
        }
        if (in.readInt() != VERSION) {
            throw new java.io.IOException("Unsupported BCL3 version");
        }

        int authLength = in.readInt();
        if (authLength != MultipathCrypto.HASH_BYTES) {
            throw new java.io.IOException("Bad authorization tag length");
        }
        byte[] authorizationTag = new byte[authLength];
        in.readFully(authorizationTag);

        int idLength = in.readInt();
        if (idLength <= 0 || idLength > MAX_ID) {
            throw new java.io.IOException("Invalid transfer id");
        }
        byte[] idBytes = new byte[idLength];
        in.readFully(idBytes);
        String transferId =
                new String(idBytes, StandardCharsets.UTF_8);
        if (!transferId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new java.io.IOException("Invalid transfer id");
        }

        long fileSize = in.readLong();
        long offset = in.readLong();
        long length = in.readLong();
        int chunkIndex = in.readInt();
        int chunkCount = in.readInt();

        long expectedChunkCount =
                (fileSize - 1) / MAX_CHUNK + 1;
        long expectedOffset =
                (long) chunkIndex * MAX_CHUNK;
        if (fileSize <= 0
                || expectedChunkCount > MAX_CHUNKS
                || chunkCount != expectedChunkCount
                || offset < 0
                || length <= 0
                || length > MAX_CHUNK
                || chunkIndex < 0
                || chunkIndex >= chunkCount
                || offset != expectedOffset
                || offset > fileSize - length) {
            throw new java.io.IOException("Invalid chunk range");
        }

        long expectedLength =
                Math.min((long) MAX_CHUNK, fileSize - offset);
        if (length != expectedLength) {
            throw new java.io.IOException("Invalid chunk length");
        }

        byte[] hash = new byte[MultipathCrypto.HASH_BYTES];
        in.readFully(hash);

        int nameLength = in.readInt();
        if (nameLength <= 0 || nameLength > MAX_NAME) {
            throw new java.io.IOException("Invalid file name");
        }
        byte[] name = new byte[nameLength];
        in.readFully(name);
        String originalName =
                new String(name, StandardCharsets.UTF_8);
        String safeName =
                originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeName.isEmpty()
                || ".".equals(safeName)
                || "..".equals(safeName)) {
            throw new java.io.IOException("Invalid sanitized file name");
        }

        byte[] iv =
                new byte[MultipathCrypto.GCM_IV_BYTES];
        in.readFully(iv);

        byte[] aad = MultipathCrypto.descriptor(
                transferId,
                fileSize,
                offset,
                length,
                hash,
                chunkIndex,
                chunkCount,
                originalName,
                iv);

        if (!MultipathCrypto.verifyAuthorizationTag(
                expectedToken,
                authorizationTag,
                transferId,
                fileSize,
                offset,
                length,
                hash,
                chunkIndex,
                chunkCount,
                originalName,
                iv)) {
            writeResponse(out, 1, 0);
            throw new SecurityException(
                    "Invalid BCL3 authorization proof");
        }

        long ciphertextLengthLong =
                length + MultipathCrypto.GCM_TAG_BYTES;
        if (ciphertextLengthLong > Integer.MAX_VALUE) {
            throw new java.io.IOException(
                    "Encrypted chunk is too large");
        }
        int ciphertextLength =
                (int) ciphertextLengthLong;
        if (in.readInt() != ciphertextLength) {
            writeResponse(out, 2, 0);
            throw new java.io.IOException(
                    "Invalid encrypted chunk length");
        }

        byte[] ciphertext = new byte[ciphertextLength];
        in.readFully(ciphertext);

        byte[] plaintext;
        try {
            plaintext = MultipathCrypto.decrypt(
                    expectedToken,
                    transferId,
                    chunkIndex,
                    ciphertext,
                    aad,
                    iv);
        } catch (Exception error) {
            writeResponse(out, 3, 0);
            throw new SecurityException(
                    "Chunk authentication/decryption failed",
                    error);
        }

        if (plaintext.length != length) {
            writeResponse(out, 4, 0);
            throw new java.io.IOException(
                    "Decrypted chunk length mismatch");
        }

        File finalCandidate =
                new File(directory, safeName);
        if (finalCandidate.isFile()
                && finalCandidate.length() == fileSize) {
            byte[] existingHash =
                    BulkTransferProtocol.sha256(finalCandidate);
            if (MessageDigest.isEqual(existingHash, hash)) {
                writeResponse(out, 0, length);
                return finalCandidate;
            }
        }

        String stateKey =
                directory.getCanonicalPath()
                        + "|" + transferId
                        + ":" + BulkTransferProtocol.hex(hash);
        State state = getOrCreateState(
                directory,
                stateKey,
                transferId,
                safeName,
                originalName,
                fileSize,
                hash,
                chunkCount);

        if (state.fileSize != fileSize
                || state.chunkCount != chunkCount
                || !state.originalName.equals(originalName)
                || !MessageDigest.isEqual(state.hash, hash)) {
            writeResponse(out, 5, 0);
            throw new java.io.IOException(
                    "Transfer metadata conflict");
        }

        File completedFile = null;

        synchronized (state.lock) {
            state.lastTouchedMs =
                    System.currentTimeMillis();

            if (!state.complete.get(chunkIndex)) {
                try (RandomAccessFile raf =
                             new RandomAccessFile(
                                     state.part, "rw");
                     FileChannel channel =
                             raf.getChannel()) {
                    ByteBuffer data =
                            ByteBuffer.wrap(plaintext);
                    long written = 0;
                    while (data.hasRemaining()) {
                        int writtenNow =
                                channel.write(
                                        data, offset + written);
                        if (writtenNow <= 0) {
                            throw new java.io.IOException(
                                    "Short chunk write");
                        }
                        written += writtenNow;
                    }
                }
                state.complete.set(chunkIndex);
                persistState(state);
            }

            boolean finished =
                    state.complete.cardinality()
                            == state.chunkCount;

            if (finished) {
                byte[] actual =
                        BulkTransferProtocol.sha256(
                                state.part);
                if (!MessageDigest.isEqual(
                        actual, state.hash)) {
                    state.complete.clear(chunkIndex);
                    writeResponse(out, 6, length);
                    throw new java.io.IOException(
                            "Final SHA-256 integrity check failed");
                }

                if (state.finalFile.exists()
                        && !state.finalFile.delete()) {
                    throw new java.io.IOException(
                            "Cannot replace existing file");
                }
                if (!state.part.renameTo(
                        state.finalFile)) {
                    throw new java.io.IOException(
                            "Cannot finalize received file");
                }
                STATES.remove(stateKey);
                deleteMetadata(state);
                completedFile = state.finalFile;
            }
        }

        writeResponse(out, 0, length);
        return completedFile;
    }

    private static State getOrCreateState(
            File directory,
            String stateKey,
            String transferId,
            String safeName,
            String originalName,
            long fileSize,
            byte[] hash,
            int chunkCount) throws java.io.IOException {
        State existing = STATES.get(stateKey);
        if (existing != null) {
            return existing;
        }

        File part = new File(
                directory,
                BulkTransferProtocol.hex(hash)
                        + "-" + transferId + ".part");
        File finalFile = new File(directory, safeName);

        State loaded = loadState(
                part,
                finalFile,
                originalName,
                fileSize,
                hash,
                chunkCount);

        State raced = STATES.putIfAbsent(stateKey, loaded);
        return raced == null ? loaded : raced;
    }

    private static State loadState(
            File part,
            File finalFile,
            String originalName,
            long fileSize,
            byte[] hash,
            int chunkCount) throws java.io.IOException {
        State state = new State(
                part,
                finalFile,
                originalName,
                fileSize,
                hash,
                chunkCount);

        File metadata = metadataFile(part);
        if (!metadata.isFile()) {
            return state;
        }

        try (DataInputStream in =
                     new DataInputStream(
                             new java.io.BufferedInputStream(
                                     new java.io.FileInputStream(
                                             metadata),
                                     16 * 1024))) {
            if (in.readInt() != 0x42434D31
                    || in.readInt() != VERSION
                    || in.readLong() != fileSize
                    || in.readInt() != chunkCount) {
                throw new java.io.IOException(
                        "Invalid transfer metadata");
            }

            int hashLength = in.readInt();
            if (hashLength != hash.length) {
                throw new java.io.IOException(
                        "Transfer metadata hash mismatch");
            }

            byte[] storedHash = new byte[hashLength];
            in.readFully(storedHash);
            if (!MessageDigest.isEqual(storedHash, hash)) {
                throw new java.io.IOException(
                        "Transfer metadata belongs to another file");
            }

            int nameLength = in.readInt();
            if (nameLength <= 0 || nameLength > MAX_NAME) {
                throw new java.io.IOException(
                        "Invalid transfer metadata name");
            }

            byte[] storedName = new byte[nameLength];
            in.readFully(storedName);
            String storedOriginalName =
                    new String(storedName, StandardCharsets.UTF_8);
            if (!storedOriginalName.equals(originalName)) {
                throw new java.io.IOException(
                        "Transfer metadata name mismatch");
            }

            int bitmapLength = in.readInt();
            if (bitmapLength < 0
                    || bitmapLength > ((chunkCount + 7) / 8) + 1024) {
                throw new java.io.IOException(
                        "Invalid transfer bitmap");
            }

            byte[] bitmap = new byte[bitmapLength];
            in.readFully(bitmap);
            state.complete.or(BitSet.valueOf(bitmap));

            if (part.exists() && part.length() > fileSize) {
                throw new java.io.IOException(
                        "Partial file exceeds declared size");
            }

            state.lastTouchedMs =
                    Math.max(
                            state.lastTouchedMs,
                            metadata.lastModified());
            return state;
        } catch (Exception error) {
            // Corrupt metadata must never cause acceptance of stale
            // completion state. Start safely from an empty bitmap.
            try { metadata.delete(); } catch (Exception ignored) {}
            state.complete.clear();
            return state;
        }
    }

    private static void persistState(State state)
            throws java.io.IOException {
        File metadata = metadataFile(state.part);
        File temp = new File(
                metadata.getPath() + ".tmp");

        byte[] bitmap = state.complete.toByteArray();
        byte[] name =
                state.originalName.getBytes(StandardCharsets.UTF_8);

        try (DataOutputStream out =
                     new DataOutputStream(
                             new java.io.BufferedOutputStream(
                                     new java.io.FileOutputStream(
                                             temp),
                                     16 * 1024))) {
            out.writeInt(0x42434D31);
            out.writeInt(VERSION);
            out.writeLong(state.fileSize);
            out.writeInt(state.chunkCount);
            out.writeInt(state.hash.length);
            out.write(state.hash);
            out.writeInt(name.length);
            out.write(name);
            out.writeInt(bitmap.length);
            out.write(bitmap);
            out.flush();
        }

        try {
            Files.move(
                    temp.toPath(),
                    metadata.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    temp.toPath(),
                    metadata.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File metadataFile(State state) {
        return metadataFile(state.part);
    }

    private static File metadataFile(File part) {
        return new File(part.getPath() + ".meta");
    }

    private static void deleteMetadata(State state) {
        try {
            metadataFile(state).delete();
        } catch (Exception ignored) {}
    }

    private static void writeResponse(
            DataOutputStream out,
            int status,
            long acknowledged) throws java.io.IOException {
        out.writeInt(RESPONSE_MAGIC);
        out.writeInt(VERSION);
        out.writeInt(status);
        out.writeLong(acknowledged);
        out.flush();
    }

    private static void cleanupStaleStates(File directory) {
        long cutoff =
                System.currentTimeMillis()
                        - 10 * 60 * 1000L;

        for (Map.Entry<String, State> entry
                : STATES.entrySet()) {
            State state = entry.getValue();
            boolean expired =
                    state.lastTouchedMs < cutoff;
            boolean overLimit =
                    STATES.size() > MAX_STORED_TRANSFERS;

            if (expired || overLimit) {
                if (STATES.remove(entry.getKey(), state)) {
                    deletePartialState(state);
                }
            }
        }

        File[] metadataFiles = directory.listFiles(
                (dir, name) -> name.endsWith(".meta"));
        if (metadataFiles == null) return;

        for (File metadata : metadataFiles) {
            if (metadata.lastModified() >= cutoff) continue;
            File part = new File(
                    metadata.getPath()
                            .substring(0, metadata.getPath().length() - 5));
            try { metadata.delete(); } catch (Exception ignored) {}
            try { part.delete(); } catch (Exception ignored) {}
        }
    }

    private static void deletePartialState(State state) {
        try {
            if (state.part.exists()) state.part.delete();
        } catch (Exception ignored) {}
        deleteMetadata(state);
    }
}
