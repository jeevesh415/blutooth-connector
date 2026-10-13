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
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MultipathReceiver {
    private static final int MAGIC = 0x42434C32;
    private static final int VERSION = 3;
    private static final int RESPONSE_MAGIC = 0x4241434B;
    private static final int MAX_ID = 64;
    private static final int MAX_NAME = 512;
    private static final int MAX_CHUNKS = 1_000_000;
    private static final int MAX_CHUNK = 1024 * 1024;
    private static final long MAX_TRANSFER_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final long MAX_PARTIAL_BYTES = 16L * 1024L * 1024L * 1024L;
    private static final int MAX_STORED_TRANSFERS = 64;
    private static final Object STATE_LOCK = new Object();
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
        // Guarded by STATE_LOCK. Active chunk handlers may never be evicted.
        int activeChunks;
        volatile boolean completed;
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
                || fileSize > MAX_TRANSFER_BYTES
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
        File expectedPart = new File(
                directory,
                BulkTransferProtocol.hex(hash)
                        + "-" + transferId + ".part");
        State state;
        try {
            state = acquireState(
                    directory,
                    stateKey,
                    expectedPart,
                    safeName,
                    originalName,
                    fileSize,
                    hash,
                    chunkCount);
        } catch (java.io.IOException capacityOrStateError) {
            writeResponse(out, 7, 0);
            throw capacityOrStateError;
        }

        File completedFile = null;
        try {
            if (state.fileSize != fileSize
                    || state.chunkCount != chunkCount
                    || !state.originalName.equals(originalName)
                    || !MessageDigest.isEqual(state.hash, hash)) {
                writeResponse(out, 5, 0);
                throw new java.io.IOException(
                        "Transfer metadata conflict");
            }

            synchronized (state.lock) {
                state.lastTouchedMs =
                        System.currentTimeMillis();

                // A retry may already be queued when another path delivers the
                // final missing chunk. A completed state acknowledges that retry
                // rather than hashing or promoting the now-moved .part file again.
                if (state.completed) {
                    completedFile = state.finalFile;
                } else {
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

                    promotePartial(state.part, state.finalFile);
                    state.completed = true;
                    deleteMetadata(state);
                    completedFile = state.finalFile;
                }
                }
            }

            writeResponse(out, 0, length);
            return completedFile;
        } finally {
            releaseState(stateKey, state);
        }
    }

    private static State acquireState(
            File directory,
            String stateKey,
            File part,
            String safeName,
            String originalName,
            long fileSize,
            byte[] hash,
            int chunkCount) throws java.io.IOException {
        synchronized (STATE_LOCK) {
            State existing = STATES.get(stateKey);
            if (existing != null) {
                existing.activeChunks++;
                existing.lastTouchedMs =
                        System.currentTimeMillis();
                return existing;
            }

            long cutoff =
                    System.currentTimeMillis()
                            - 10 * 60 * 1000L;
            evictExpiredStatesLocked(cutoff);
            if (STATES.size() >= MAX_STORED_TRANSFERS) {
                throw new java.io.IOException(
                        "Too many concurrent or resumable transfers");
            }

            long reservedByOtherTransfers =
                    reservedPartialBytes(directory, part);
            if (reservedByOtherTransfers
                    > MAX_PARTIAL_BYTES - fileSize) {
                throw new java.io.IOException(
                        "Bulk partial storage budget exceeded");
            }

            long existingPartBytes =
                    part.isFile()
                            ? Math.min(part.length(), fileSize)
                            : 0L;
            long additionalBytes =
                    fileSize - existingPartBytes;
            if (directory.getUsableSpace() < additionalBytes) {
                throw new java.io.IOException(
                        "Insufficient free space for partial transfer");
            }

            File finalFile = new File(directory, safeName);
            State loaded = loadState(
                    part,
                    finalFile,
                    originalName,
                    fileSize,
                    hash,
                    chunkCount);
            loaded.activeChunks = 1;
            loaded.lastTouchedMs =
                    System.currentTimeMillis();
            STATES.put(stateKey, loaded);
            return loaded;
        }
    }

    private static void releaseState(String stateKey, State state) {
        synchronized (STATE_LOCK) {
            if (state.activeChunks > 0) {
                state.activeChunks--;
            }
            state.lastTouchedMs =
                    System.currentTimeMillis();
            if (state.completed && state.activeChunks == 0) {
                STATES.remove(stateKey, state);
            }
        }
    }

    private static long reservedPartialBytes(
            File directory,
            File excludedPart) throws java.io.IOException {
        String root = directory.getCanonicalPath();
        String excluded = excludedPart.getCanonicalPath();
        Map<String, Long> tracked = new HashMap<>();

        for (State state : STATES.values()) {
            if (!state.part.getParentFile()
                    .getCanonicalPath().equals(root)) {
                continue;
            }
            String path = state.part.getCanonicalPath();
            if (!path.equals(excluded)) {
                tracked.put(path, state.fileSize);
            }
        }

        long total = 0L;
        Set<String> seen = new HashSet<>();
        File[] parts = directory.listFiles(
                (dir, name) -> name != null && name.endsWith(".part"));
        if (parts != null) {
            for (File part : parts) {
                String path = part.getCanonicalPath();
                if (path.equals(excluded)) continue;
                seen.add(path);
                Long inMemoryReservation = tracked.remove(path);
                long declared = inMemoryReservation != null
                        ? inMemoryReservation
                        : declaredFileSize(part);
                long reserved = Math.max(part.length(), declared);
                total = saturatedAdd(total, reserved);
            }
        }

        // A newly registered state can reserve its full size before the
        // first chunk creates the partial file on disk.
        for (Map.Entry<String, Long> entry : tracked.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                total = saturatedAdd(total, entry.getValue());
            }
        }
        return total;
    }

    private static long declaredFileSize(File part) {
        File metadata = metadataFile(part);
        if (!metadata.isFile()) return part.length();
        try (DataInputStream in =
                     new DataInputStream(
                             new java.io.BufferedInputStream(
                                     new java.io.FileInputStream(metadata)))) {
            if (in.readInt() != 0x42434D31
                    || in.readInt() != VERSION) {
                return part.length();
            }
            long size = in.readLong();
            return size > 0 && size <= MAX_TRANSFER_BYTES
                    ? size
                    : part.length();
        } catch (Exception ignored) {
            return part.length();
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right < 0 || Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void promotePartial(
            File part, File destination) throws java.io.IOException {
        try {
            Files.move(
                    part.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    part.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
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
        String root;
        try {
            root = directory.getCanonicalPath();
        } catch (java.io.IOException error) {
            return;
        }

        synchronized (STATE_LOCK) {
            for (Map.Entry<String, State> entry : STATES.entrySet()) {
                State state = entry.getValue();
                try {
                    File parent = state.part.getParentFile();
                    boolean sameDirectory = parent != null
                            && parent.getCanonicalPath().equals(root);
                    if (sameDirectory
                            && state.activeChunks == 0
                            && state.lastTouchedMs < cutoff
                            && STATES.remove(entry.getKey(), state)) {
                        deletePartialState(state);
                    }
                } catch (java.io.IOException ignored) {
                    // Do not evict a state when its location cannot be checked.
                }
            }

            File[] metadataFiles = directory.listFiles(
                    (dir, name) -> name != null && name.endsWith(".meta"));
            if (metadataFiles == null) return;

            for (File metadata : metadataFiles) {
                if (metadata.lastModified() >= cutoff) continue;
                File part = new File(
                        metadata.getPath()
                                .substring(0, metadata.getPath().length() - 5));
                if (isTrackedPartLocked(part)) continue;
                try { metadata.delete(); } catch (Exception ignored) {}
                try { part.delete(); } catch (Exception ignored) {}
            }

            // A process can die after creating a partial file but before writing
            // its metadata. Retire only old, untracked orphan files.
            File[] partialFiles = directory.listFiles(
                    (dir, name) -> name != null && name.endsWith(".part"));
            if (partialFiles != null) {
                for (File part : partialFiles) {
                    if (part.lastModified() >= cutoff
                            || metadataFile(part).exists()
                            || isTrackedPartLocked(part)) {
                        continue;
                    }
                    try { part.delete(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static void evictExpiredStatesLocked(long cutoff) {
        for (Map.Entry<String, State> entry : STATES.entrySet()) {
            State state = entry.getValue();
            if (state.activeChunks == 0
                    && state.lastTouchedMs < cutoff
                    && STATES.remove(entry.getKey(), state)) {
                deletePartialState(state);
            }
        }
    }

    private static boolean isTrackedPartLocked(File part) {
        try {
            String candidate = part.getCanonicalPath();
            for (State state : STATES.values()) {
                if (state.part.getCanonicalPath().equals(candidate)) {
                    return true;
                }
            }
        } catch (java.io.IOException ignored) {}
        return false;
    }

    private static void deletePartialState(State state) {
        try {
            if (state.part.exists()) state.part.delete();
        } catch (Exception ignored) {}
        deleteMetadata(state);
    }
}
