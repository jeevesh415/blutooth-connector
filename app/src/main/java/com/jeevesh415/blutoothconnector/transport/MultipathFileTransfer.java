package com.jeevesh415.blutoothconnector.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Stripes a file across independently reachable TCP endpoints.
 *
 * Bluetooth remains the control/discovery plane; the TCP paths carry the
 * payload concurrently.  Chunks are independently acknowledged and can be
 * retried, so path failure does not invalidate already delivered chunks.
 */
public final class MultipathFileTransfer {
    private static final int MAGIC = 0x42434C32; // BCL2
    private static final int VERSION = 2;
    private static final int MAX_NAME = 512;
    private static final int MAX_ID = 64;
    private static final int CHUNK = 1024 * 1024;

    private MultipathFileTransfer() {}

    public static long send(File file, List<BulkEndpointInfo> endpoints, int maxAttempts)
            throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Not a file: " + file);
        if (endpoints == null || endpoints.isEmpty()) throw new IllegalArgumentException("No paths");

        byte[] hash = BulkTransferProtocol.sha256(file);
        String transferId = UUID.randomUUID().toString();
        int chunkCount = (int) ((file.length() + CHUNK - 1) / CHUNK);

        SpectralPathScheduler scheduler = new SpectralPathScheduler();
        List<BulkEndpointInfo> usable = new ArrayList<>();
        for (int i = 0; i < endpoints.size(); i++) {
            BulkEndpointInfo e = endpoints.get(i);
            if (e.host == null || e.host.isEmpty() || e.port < 1) continue;
            usable.add(e);
            scheduler.path(pathId(e, i), e.transport).observe(1_000_000, 10);
        }
        if (usable.isEmpty()) throw new IllegalArgumentException("No usable TCP paths");

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(usable.size(), Math.max(1, chunkCount)));
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                final int index = chunk;
                futures.add(pool.submit(() -> {
                    BulkEndpointInfo endpoint = chooseEndpoint(scheduler, usable, index);
                    long offset = (long) index * CHUNK;
                    long length = Math.min(CHUNK, file.length() - offset);
                    long started = System.nanoTime();
                    long sent = sendChunk(file, endpoint, transferId, hash, index, chunkCount,
                            offset, length, maxAttempts);
                    double seconds = Math.max(1e-6, (System.nanoTime() - started) / 1e9);
                    scheduler.path(pathId(endpoint, usable.indexOf(endpoint)), endpoint.transport)
                            .observe(sent / seconds, 1);
                    return sent;
                }));
            }

            long total = 0;
            for (Future<Long> f : futures) total += f.get();
            if (total != file.length()) throw new java.io.IOException(
                    "Multipath transfer byte count mismatch: " + total + "/" + file.length());
            return total;
        } finally {
            pool.shutdownNow();
        }
    }

    private static BulkEndpointInfo chooseEndpoint(
            SpectralPathScheduler scheduler, List<BulkEndpointInfo> endpoints, int chunk) {
        List<SpectralPathScheduler.Allocation> allocations = scheduler.allocate();
        if (allocations.isEmpty()) return endpoints.get(chunk % endpoints.size());

        double target = ((chunk * 0.6180339887498949) % 1.0);
        double cumulative = 0;
        String selected = allocations.get(allocations.size() - 1).pathId;
        for (SpectralPathScheduler.Allocation a : allocations) {
            cumulative += a.fraction;
            if (target <= cumulative) {
                selected = a.pathId;
                break;
            }
        }
        for (int i = 0; i < endpoints.size(); i++) {
            if (pathId(endpoints.get(i), i).equals(selected)) return endpoints.get(i);
        }
        return endpoints.get(chunk % endpoints.size());
    }

    private static long sendChunk(File file, BulkEndpointInfo endpoint, String transferId,
                                  byte[] hash, int chunkIndex, int chunkCount,
                                  long offset, long length, int maxAttempts) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                return sendChunkOnce(file, endpoint, transferId, hash, chunkIndex, chunkCount,
                        offset, length);
            } catch (Exception e) {
                last = e;
                try { Thread.sleep(Math.min(2000L, 100L * attempt * attempt)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw last;
    }

    private static long sendChunkOnce(File file, BulkEndpointInfo endpoint, String transferId,
                                      byte[] hash, int chunkIndex, int chunkCount,
                                      long offset, long length) throws Exception {
        byte[] token = BulkTransferProtocol.decodeToken(endpoint.tokenBase64);
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSendBufferSize(4 * 1024 * 1024);
            socket.setReceiveBufferSize(4 * 1024 * 1024);
            socket.connect(new InetSocketAddress(endpoint.host, endpoint.port), 5000);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            writeRequest(out, token, transferId, file.length(), offset, length, hash,
                    file.getName(), chunkIndex, chunkCount);

            try (InputStream source = new java.io.BufferedInputStream(
                    new java.io.FileInputStream(file), CHUNK)) {
                skipFully(source, offset);
                byte[] buffer = new byte[CHUNK];
                long remaining = length;
                while (remaining > 0) {
                    int wanted = (int) Math.min(buffer.length, remaining);
                    int n = source.read(buffer, 0, wanted);
                    if (n < 0) throw new EOFException("Unexpected file EOF");
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
            out.flush();

            int magic = in.readInt();
            int version = in.readInt();
            int status = in.readInt();
            long acknowledged = in.readLong();
            if (magic != 0x4241434B || version != VERSION || status != 0
                    || acknowledged != length) {
                throw new java.io.IOException("Chunk rejected: status=" + status
                        + " ack=" + acknowledged);
            }
            return length;
        }
    }

    private static void writeRequest(DataOutputStream out, byte[] token, String transferId,
                                     long fileSize, long offset, long length, byte[] hash,
                                     String name, int chunkIndex, int chunkCount) throws Exception {
        byte[] id = transferId.getBytes(StandardCharsets.UTF_8);
        byte[] filename = name.getBytes(StandardCharsets.UTF_8);
        if (token.length != 32 || hash.length != 32 || id.length == 0 || id.length > MAX_ID
                || filename.length == 0 || filename.length > MAX_NAME
                || offset < 0 || length <= 0 || chunkIndex < 0 || chunkCount <= chunkIndex) {
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

    private static String pathId(BulkEndpointInfo e, int index) {
        return e.transport + ":" + e.host + ":" + e.port + ":" + index;
    }

    private static void skipFully(InputStream in, long bytes) throws java.io.IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) { remaining -= skipped; continue; }
            if (in.read() < 0) throw new EOFException("Cannot seek source");
            remaining--;
        }
    }

    static String decodeBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
