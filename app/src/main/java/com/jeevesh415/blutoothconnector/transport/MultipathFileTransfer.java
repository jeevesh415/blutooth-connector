package com.jeevesh415.blutoothconnector.transport;

import android.content.Context;
import android.net.Network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MultipathFileTransfer {
    private static final int MAGIC = 0x42434C32;
    private static final int VERSION = 3;
    private static final int MAX_NAME = 512;
    private static final int MAX_ID = 64;
    private static final int CHUNK = 1024 * 1024;
    private static final int MAX_CHUNKS = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Candidate {
        final BulkEndpointInfo endpoint;
        final Network network;
        final String pathId;

        Candidate(
                BulkEndpointInfo endpoint,
                Network network,
                String pathId) {
            this.endpoint = endpoint;
            this.network = network;
            this.pathId = pathId;
        }
    }

    private static final class ChunkTransferResult {
        final Candidate candidate;
        final long bytes;

        ChunkTransferResult(
                Candidate candidate,
                long bytes) {
            this.candidate = candidate;
            this.bytes = bytes;
        }
    }

    private static final class ChunkOutcome {
        final Candidate candidate;
        final long bytes;
        final long durationNanos;
        final Exception error;

        ChunkOutcome(
                Candidate candidate,
                long bytes,
                long durationNanos,
                Exception error) {
            this.candidate = candidate;
            this.bytes = bytes;
            this.durationNanos = durationNanos;
            this.error = error;
        }
    }

    private MultipathFileTransfer() {}

    public static long send(
            File file,
            List<BulkEndpointInfo> endpoints,
            int maxAttempts) throws Exception {
        return send(null, file, endpoints, maxAttempts);
    }

    public static long send(
            Context context,
            File file,
            List<BulkEndpointInfo> endpoints,
            int maxAttempts) throws Exception {
        if (!file.isFile()) {
            throw new IllegalArgumentException(
                    "Not a file: " + file);
        }
        if (file.length() == 0) {
            throw new IllegalArgumentException(
                    "Empty files are not supported");
        }
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("No paths");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts");
        }

        byte[] hash =
                BulkTransferProtocol.sha256(file);
        String transferId =
                UUID.randomUUID().toString();
        long countLong =
                (file.length() + CHUNK - 1L) / CHUNK;

        if (countLong > MAX_CHUNKS) {
            throw new IllegalArgumentException(
                    "File exceeds multipath transfer limit");
        }

        int chunkCount = (int) countLong;
        NetworkPathCatalog catalog =
                context == null
                        ? null
                        : new NetworkPathCatalog(context);
        List<NetworkPathCatalog.Path> localPaths =
                catalog == null
                        ? Collections.emptyList()
                        : catalog.snapshot();

        List<Candidate> candidates =
                new ArrayList<>();

        for (BulkEndpointInfo endpoint : endpoints) {
            if (endpoint == null
                    || endpoint.host == null
                    || endpoint.host.isEmpty()
                    || endpoint.port < 1) {
                continue;
            }

            Network selectedNetwork = null;
            String networkId = null;

            for (NetworkPathCatalog.Path path
                    : localPaths) {
                if (endpoint.transport.equals(
                        path.kind)) {
                    selectedNetwork = path.network;
                    networkId = path.id;
                    break;
                }
            }

            String pathId = endpoint.transport
                    + ":" + endpoint.host
                    + ":" + endpoint.port
                    + (networkId == null
                            ? ""
                            : "@" + networkId);

            candidates.add(new Candidate(
                    endpoint,
                    selectedNetwork,
                    pathId));
        }

        // Remove duplicate endpoint/network tuples before scheduling.
        List<Candidate> unique = new ArrayList<>();
        Set<String> seenPathIds = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (seenPathIds.add(candidate.pathId)) {
                unique.add(candidate);
            }
        }
        candidates = unique;

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "No usable TCP paths");
        }

        SpectralPathScheduler scheduler =
                new SpectralPathScheduler();

        for (Candidate candidate : candidates) {
            scheduler.path(
                    candidate.pathId,
                    candidate.endpoint.transport)
                    .observe(1_000_000, 10);
        }

        int workers = Math.min(
                Math.max(1, candidates.size() * 2),
                Math.max(1, chunkCount));

        ExecutorService pool =
                Executors.newFixedThreadPool(workers);
        CompletionService<ChunkOutcome> completion =
                new ExecutorCompletionService<>(pool);

        int nextChunk = 0;
        int inFlight = 0;
        long total = 0;

        try {
            while (nextChunk < chunkCount
                    && inFlight < workers) {
                submitChunk(
                        completion,
                        scheduler,
                        candidates,
                        file,
                        transferId,
                        hash,
                        nextChunk++,
                        chunkCount,
                        maxAttempts);
                inFlight++;
            }

            while (inFlight > 0) {
                ChunkOutcome outcome =
                        completion.take().get();
                inFlight--;

                SpectralPathScheduler.Path path =
                        scheduler.path(
                                outcome.candidate.pathId,
                                outcome.candidate.endpoint.transport);

                if (outcome.error != null) {
                    path.observeFailure();
                    throw outcome.error;
                }

                double seconds =
                        Math.max(
                                1e-6,
                                outcome.durationNanos / 1e9);

                path.observe(
                        outcome.bytes / seconds,
                        -1);
                total += outcome.bytes;

                if (nextChunk < chunkCount) {
                    submitChunk(
                            completion,
                            scheduler,
                            candidates,
                            file,
                            transferId,
                            hash,
                            nextChunk++,
                            chunkCount,
                            maxAttempts);
                    inFlight++;
                }
            }

            if (total != file.length()) {
                throw new java.io.IOException(
                        "Multipath byte count mismatch: "
                                + total + "/"
                                + file.length());
            }

            return total;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void submitChunk(
            CompletionService<ChunkOutcome> completion,
            SpectralPathScheduler scheduler,
            List<Candidate> candidates,
            File file,
            String transferId,
            byte[] hash,
            int index,
            int chunkCount,
            int maxAttempts) {
        completion.submit(() -> {
            Candidate candidate =
                    chooseCandidate(
                            scheduler,
                            candidates,
                            index);
            long offset =
                    (long) index * CHUNK;
            long length =
                    Math.min(
                            CHUNK,
                            file.length() - offset);
            long started =
                    System.nanoTime();

            try {
                ChunkTransferResult sent =
                        sendChunk(
                                file,
                                candidate,
                                scheduler,
                                candidates,
                                transferId,
                                hash,
                                index,
                                chunkCount,
                                offset,
                                length,
                                maxAttempts);

                return new ChunkOutcome(
                        sent.candidate,
                        sent.bytes,
                        System.nanoTime() - started,
                        null);
            } catch (Exception error) {
                return new ChunkOutcome(
                        candidate,
                        0,
                        System.nanoTime() - started,
                        error);
            }
        });
    }

    private static Candidate chooseCandidate(
            SpectralPathScheduler scheduler,
            List<Candidate> candidates,
            int chunk) {
        List<SpectralPathScheduler.Allocation>
                allocations = scheduler.allocate();

        if (allocations.isEmpty()) {
            return candidates.get(
                    chunk % candidates.size());
        }

        double target =
                (chunk * 0.6180339887498949) % 1.0;
        double cumulative = 0.0;
        String selected =
                allocations.get(
                        allocations.size() - 1)
                        .pathId;

        for (SpectralPathScheduler.Allocation allocation
                : allocations) {
            cumulative += allocation.fraction;
            if (target <= cumulative) {
                selected = allocation.pathId;
                break;
            }
        }

        for (Candidate candidate : candidates) {
            if (candidate.pathId.equals(selected)) {
                return candidate;
            }
        }

        return candidates.get(
                chunk % candidates.size());
    }

    private static ChunkTransferResult sendChunk(
            File file,
            Candidate initialCandidate,
            SpectralPathScheduler scheduler,
            List<Candidate> candidates,
            String transferId,
            byte[] hash,
            int chunkIndex,
            int chunkCount,
            long offset,
            long length,
            int maxAttempts) throws Exception {
        Exception last = null;
        Candidate candidate = initialCandidate;
        Set<String> failedPaths = new HashSet<>();

        for (int attempt = 1;
                attempt <= maxAttempts;
                attempt++) {
            try {
                long sent =
                        sendChunkOnce(
                                file,
                                candidate,
                                transferId,
                                hash,
                                chunkIndex,
                                chunkCount,
                                offset,
                                length);

                return new ChunkTransferResult(
                        candidate, sent);
            } catch (Exception error) {
                last = error;

                scheduler.path(
                        candidate.pathId,
                        candidate.endpoint.transport)
                        .observeFailure();
                failedPaths.add(candidate.pathId);

                if (attempt == maxAttempts) {
                    break;
                }

                candidate = chooseAlternative(
                        scheduler,
                        candidates,
                        failedPaths,
                        chunkIndex,
                        attempt);

                try {
                    Thread.sleep(
                            Math.min(
                                    2000L,
                                    100L * attempt * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }

        throw last == null
                ? new java.io.IOException(
                        "Chunk transfer failed")
                : last;
    }

    private static Candidate chooseAlternative(
            SpectralPathScheduler scheduler,
            List<Candidate> candidates,
            Set<String> failedPaths,
            int chunk,
            int attempt) {
        if (failedPaths.size()
                >= candidates.size()) {
            failedPaths.clear();
            return chooseCandidate(
                    scheduler,
                    candidates,
                    chunk + attempt * 7919);
        }

        List<SpectralPathScheduler.Allocation>
                allocations =
                scheduler.allocate();

        if (!allocations.isEmpty()) {
            double total = 0;

            for (SpectralPathScheduler.Allocation allocation
                    : allocations) {
                if (!failedPaths.contains(
                        allocation.pathId)) {
                    total += allocation.fraction;
                }
            }

            if (total > 0) {
                double target =
                        ((chunk + attempt * 0.5)
                                * 0.6180339887498949)
                                % 1.0;
                double cumulative = 0;

                for (SpectralPathScheduler.Allocation allocation
                        : allocations) {
                    if (failedPaths.contains(
                            allocation.pathId)) {
                        continue;
                    }

                    cumulative +=
                            allocation.fraction / total;

                    if (target <= cumulative) {
                        for (Candidate candidate
                                : candidates) {
                            if (candidate.pathId.equals(
                                    allocation.pathId)) {
                                return candidate;
                            }
                        }
                    }
                }
            }
        }

        for (Candidate candidate : candidates) {
            if (!failedPaths.contains(
                    candidate.pathId)) {
                return candidate;
            }
        }

        return candidates.get(
                chunk % candidates.size());
    }

    private static long sendChunkOnce(
            File file,
            Candidate candidate,
            String transferId,
            byte[] hash,
            int chunkIndex,
            int chunkCount,
            long offset,
            long length) throws Exception {
        byte[] token =
                BulkTransferProtocol.decodeToken(
                        candidate.endpoint.tokenBase64);

        if (token.length
                != MultipathCrypto.TOKEN_BYTES) {
            throw new java.io.IOException(
                    "Invalid endpoint token");
        }

        byte[] iv =
                new byte[
                        MultipathCrypto.GCM_IV_BYTES];
        RANDOM.nextBytes(iv);

        byte[] aad =
                MultipathCrypto.descriptor(
                        transferId,
                        file.length(),
                        offset,
                        length,
                        hash,
                        chunkIndex,
                        chunkCount,
                        file.getName(),
                        iv);

        byte[] authorizationTag =
                MultipathCrypto.authorizationTag(
                        token,
                        transferId,
                        file.length(),
                        offset,
                        length,
                        hash,
                        chunkIndex,
                        chunkCount,
                        file.getName(),
                        iv);

        Socket socket =
                candidate.network == null
                        ? new Socket()
                        : candidate.network
                                .getSocketFactory()
                                .createSocket();

        try (Socket closeable = socket) {
            closeable.setTcpNoDelay(true);
            closeable.setKeepAlive(true);
            closeable.setSoTimeout(15_000);
            closeable.setSendBufferSize(
                    4 * 1024 * 1024);
            closeable.setReceiveBufferSize(
                    4 * 1024 * 1024);

            closeable.connect(
                    new InetSocketAddress(
                            candidate.endpoint.host,
                            candidate.endpoint.port),
                    5000);

            DataOutputStream out =
                    new DataOutputStream(
                            closeable.getOutputStream());
            DataInputStream in =
                    new DataInputStream(
                            closeable.getInputStream());

            writeRequest(
                    out,
                    authorizationTag,
                    transferId,
                    file.length(),
                    offset,
                    length,
                    hash,
                    file.getName(),
                    chunkIndex,
                    chunkCount,
                    iv);

            byte[] plaintext =
                    new byte[(int) length];
            try (RandomAccessFile source =
                         new RandomAccessFile(
                                 file, "r");
                 FileChannel channel =
                         source.getChannel()) {
                ByteBuffer buffer =
                        ByteBuffer.wrap(plaintext);
                long position = offset;

                while (buffer.hasRemaining()) {
                    int n = channel.read(
                            buffer, position);
                    if (n < 0) {
                        throw new EOFException(
                                "Unexpected file EOF");
                    }
                    if (n == 0) continue;
                    position += n;
                }
            }

            byte[] ciphertext =
                    MultipathCrypto.encrypt(
                            token,
                            transferId,
                            chunkIndex,
                            plaintext,
                            aad,
                            iv);

            out.writeInt(ciphertext.length);
            out.write(ciphertext);
            out.flush();

            int responseMagic =
                    in.readInt();
            int version =
                    in.readInt();
            int status =
                    in.readInt();
            long acknowledged =
                    in.readLong();

            if (responseMagic
                        != 0x4241434B
                    || version != VERSION
                    || status != 0
                    || acknowledged != length) {
                throw new java.io.IOException(
                        "Chunk rejected: status="
                                + status
                                + " ack="
                                + acknowledged);
            }

            return length;
        }
    }

    private static void writeRequest(
            DataOutputStream out,
            byte[] authorizationTag,
            String transferId,
            long fileSize,
            long offset,
            long length,
            byte[] hash,
            String name,
            int chunkIndex,
            int chunkCount,
            byte[] iv) throws Exception {
        byte[] id =
                transferId.getBytes(
                        StandardCharsets.UTF_8);
        byte[] filename =
                name.getBytes(
                        StandardCharsets.UTF_8);

        if (authorizationTag.length
                    != MultipathCrypto.HASH_BYTES
                || hash.length
                    != MultipathCrypto.HASH_BYTES
                || iv.length
                    != MultipathCrypto.GCM_IV_BYTES
                || id.length == 0
                || id.length > MAX_ID
                || filename.length == 0
                || filename.length > MAX_NAME
                || fileSize < 1
                || offset < 0
                || length <= 0
                || length > CHUNK
                || offset > fileSize - length
                || chunkIndex < 0
                || chunkIndex >= chunkCount
                || chunkCount > MAX_CHUNKS) {
            throw new java.io.IOException(
                    "Invalid chunk request");
        }

        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(
                authorizationTag.length);
        out.write(
                authorizationTag);
        out.writeInt(id.length);
        out.write(id);
        out.writeLong(fileSize);
        out.writeLong(offset);
        out.writeLong(length);
        out.writeInt(chunkIndex);
        out.writeInt(chunkCount);
        out.write(hash);
        out.writeInt(filename.length);
        out.write(filename);
        out.write(iv);
        out.flush();
    }
}
