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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Concurrent striped transfer using endpoint paths and Android Network-specific routing.
 */
public final class MultipathFileTransfer {
    private static final int MAGIC = 0x42434C32;
    private static final int VERSION = 2;
    private static final int MAX_NAME = 512;
    private static final int MAX_ID = 64;
    private static final int CHUNK = 1024 * 1024;
    private static final int MAX_CHUNKS = 1_000_000;

    private static final class Candidate {
        final BulkEndpointInfo endpoint;
        final Network network;
        final String pathId;

        Candidate(BulkEndpointInfo endpoint, Network network, String pathId) {
            this.endpoint = endpoint;
            this.network = network;
            this.pathId = pathId;
        }
    }

    private MultipathFileTransfer() {}

    public static long send(File file, List<BulkEndpointInfo> endpoints, int maxAttempts)
            throws Exception {
        return send(null, file, endpoints, maxAttempts);
    }

    public static long send(Context context, File file,
                            List<BulkEndpointInfo> endpoints, int maxAttempts)
            throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Not a file: " + file);
        if (file.length() == 0) throw new IllegalArgumentException("Empty files are not supported");
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("No paths");
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts");

        byte[] hash = BulkTransferProtocol.sha256(file);
        String transferId = UUID.randomUUID().toString();
        long countLong = (file.length() + CHUNK - 1L) / CHUNK;
        if (countLong > MAX_CHUNKS) {
            throw new IllegalArgumentException("File exceeds multipath transfer limit");
        }
        int chunkCount = (int) countLong;

        NetworkPathCatalog catalog =
                context == null ? null : new NetworkPathCatalog(context);
        List<NetworkPathCatalog.Path> localPaths =
                catalog == null ? Collections.emptyList() : catalog.snapshot();

        List<Candidate> candidates = new ArrayList<>();
        for (BulkEndpointInfo endpoint : endpoints) {
            if (endpoint == null || endpoint.host == null || endpoint.host.isEmpty()
                    || endpoint.port < 1) {
                continue;
            }

            boolean matched = false;
            for (NetworkPathCatalog.Path path : localPaths) {
                if (!endpoint.transport.equals(path.kind)) continue;
                candidates.add(new Candidate(
                        endpoint,
                        path.network,
                        endpoint.transport + ":" + endpoint.host + ":" + endpoint.port
                                + "@" + path.id));
                matched = true;
            }
            if (!matched) {
                candidates.add(new Candidate(
                        endpoint,
                        null,
                        endpoint.transport + ":" + endpoint.host + ":" + endpoint.port));
            }
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No usable TCP paths");
        }

        SpectralPathScheduler scheduler = new SpectralPathScheduler();
        for (Candidate candidate : candidates) {
            scheduler.path(candidate.pathId, candidate.endpoint.transport)
                    .observe(1_000_000, 10);
        }

        int workers = Math.min(candidates.size() * 2, Math.max(1, chunkCount));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Long>> futures = new ArrayList<>(chunkCount);
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                final int index = chunk;
                futures.add(pool.submit(() -> {
                    Candidate candidate =
                            chooseCandidate(scheduler, candidates, index);
                    long offset = (long) index * CHUNK;
                    long length = Math.min(CHUNK, file.length() - offset);
                    long started = System.nanoTime();

                    long sent = sendChunk(
                            file, candidate, transferId, hash,
                            index, chunkCount, offset, length, maxAttempts);

                    double seconds = Math.max(
                            1e-6, (System.nanoTime() - started) / 1e9);
                    scheduler.path(candidate.pathId, candidate.endpoint.transport)
                            .observe(sent / seconds, -1);
                    return sent;
                }));
            }

            long total = 0;
            for (Future<Long> future : futures) total += future.get();
            if (total != file.length()) {
                throw new java.io.IOException(
                        "Multipath byte count mismatch: "
                                + total + "/" + file.length());
            }
            return total;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Candidate chooseCandidate(
            SpectralPathScheduler scheduler,
            List<Candidate> candidates,
            int chunk) {
        List<SpectralPathScheduler.Allocation> allocations =
                scheduler.allocate();
        if (allocations.isEmpty()) {
            return candidates.get(chunk % candidates.size());
        }

        double target = (chunk * 0.6180339887498949) % 1.0;
        double cumulative = 0.0;
        String selected = allocations.get(allocations.size() - 1).pathId;
        for (SpectralPathScheduler.Allocation allocation : allocations) {
            cumulative += allocation.fraction;
            if (target <= cumulative) {
                selected = allocation.pathId;
                break;
            }
        }

        for (Candidate candidate : candidates) {
            if (candidate.pathId.equals(selected)) return candidate;
        }
        return candidates.get(chunk % candidates.size());
    }

    private static long sendChunk(
            File file, Candidate candidate, String transferId, byte[] hash,
            int chunkIndex, int chunkCount, long offset, long length,
            int maxAttempts) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return sendChunkOnce(
                        file, candidate, transferId, hash,
                        chunkIndex, chunkCount, offset, length);
            } catch (Exception error) {
                last = error;
                if (attempt == maxAttempts) break;
                try {
                    Thread.sleep(Math.min(
                            2000L, 100L * attempt * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw last == null
                ? new java.io.IOException("Chunk transfer failed")
                : last;
    }

    private static long sendChunkOnce(
            File file, Candidate candidate, String transferId, byte[] hash,
            int chunkIndex, int chunkCount, long offset, long length)
            throws Exception {
        byte[] token =
                BulkTransferProtocol.decodeToken(candidate.endpoint.tokenBase64);
        if (token.length != BulkTransferProtocol.TOKEN_BYTES) {
            throw new java.io.IOException("Invalid endpoint token");
        }

        Socket socket = candidate.network == null
                ? new Socket()
                : candidate.network.getSocketFactory().createSocket();

        try (Socket closeable = socket) {
            closeable.setTcpNoDelay(true);
            closeable.setKeepAlive(true);
            closeable.setSoTimeout(15_000);
            closeable.setSendBufferSize(4 * 1024 * 1024);
            closeable.setReceiveBufferSize(4 * 1024 * 1024);
            closeable.connect(
                    new InetSocketAddress(
                            candidate.endpoint.host,
                            candidate.endpoint.port),
                    5000);

            DataOutputStream out =
                    new DataOutputStream(closeable.getOutputStream());
            DataInputStream in =
                    new DataInputStream(closeable.getInputStream());

            writeRequest(
                    out, token, transferId, file.length(),
                    offset, length, hash, file.getName(),
                    chunkIndex, chunkCount);

            try (RandomAccessFile source =
                         new RandomAccessFile(file, "r");
                 FileChannel channel = source.getChannel()) {
                ByteBuffer buffer = ByteBuffer.allocate(CHUNK);
                long position = offset;
                long remaining = length;
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit(
                            (int) Math.min(
                                    buffer.capacity(), remaining));
                    int n = channel.read(buffer, position);
                    if (n < 0) {
                        throw new EOFException("Unexpected file EOF");
                    }
                    if (n == 0) continue;
                    out.write(buffer.array(), 0, n);
                    position += n;
                    remaining -= n;
                }
            }
            out.flush();

            int responseMagic = in.readInt();
            int version = in.readInt();
            int status = in.readInt();
            long acknowledged = in.readLong();
            if (responseMagic != 0x4241434B
                    || version != VERSION
                    || status != 0
                    || acknowledged != length) {
                throw new java.io.IOException(
                        "Chunk rejected: status="
                                + status + " ack=" + acknowledged);
            }
            return length;
        }
    }

    private static void writeRequest(
            DataOutputStream out, byte[] token,
            String transferId, long fileSize, long offset,
            long length, byte[] hash, String name,
            int chunkIndex, int chunkCount) throws Exception {
        byte[] id = transferId.getBytes(StandardCharsets.UTF_8);
        byte[] filename = name.getBytes(StandardCharsets.UTF_8);

        if (token.length != 32
                || hash.length != 32
                || id.length == 0 || id.length > MAX_ID
                || filename.length == 0 || filename.length > MAX_NAME
                || fileSize < 1
                || offset < 0
                || length <= 0 || length > CHUNK
                || offset > fileSize - length
                || chunkIndex < 0 || chunkIndex >= chunkCount
                || chunkCount > MAX_CHUNKS) {
            throw new java.io.IOException("Invalid chunk request");
        }

        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(token.length);
        out.write(token);
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
        out.flush();
    }
}
