package com.jeevesh415.blutoothconnector.transport;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Concurrent striped file transfer over the independent TCP endpoints
 * advertised by a peer. The control channel remains Bluetooth RFCOMM.
 */
public final class MultipathFileTransfer {
    private static final int MAGIC = 0x42434C32; // BCL2
    private static final int VERSION = 2;
    private static final int MAX_NAME = 512;
    private static final int MAX_ID = 64;
    private static final int CHUNK = 1024 * 1024;
    private static final int MAX_CHUNKS = 1_000_000;

    private MultipathFileTransfer() {}

    public static long send(File file, List<BulkEndpointInfo> endpoints, int maxAttempts)
            throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Not a file: " + file);
        if (file.length() == 0) throw new IllegalArgumentException("Empty files are not supported");
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("No paths");
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts");

        byte[] hash = BulkTransferProtocol.sha256(file);
        String transferId = UUID.randomUUID().toString();
        long chunkCountLong = (file.length() + CHUNK - 1L) / CHUNK;
        if (chunkCountLong > MAX_CHUNKS) {
            throw new IllegalArgumentException("File exceeds multipath transfer limit");
        }
        int chunkCount = (int) chunkCountLong;

        SpectralPathScheduler scheduler = new SpectralPathScheduler();
        List<BulkEndpointInfo> usable = new ArrayList<>();
        for (BulkEndpointInfo endpoint : endpoints) {
            if (endpoint == null || endpoint.host == null || endpoint.host.isEmpty()
                    || endpoint.port < 1) continue;
            String id = pathId(endpoint);
            scheduler.path(id, endpoint.transport).observe(1_000_000, 10);
            if (usable.stream().noneMatch(e -> pathId(e).equals(id))) {
                usable.add(endpoint);
            }
        }

        if (usable.isEmpty()) throw new IllegalArgumentException("No usable TCP paths");

        int workers = Math.min(usable.size() * 2, Math.max(1, chunkCount));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Long>> futures = new ArrayList<>(chunkCount);
            for (int chunk = 0; chunk < chunkCount; chunk++) {
                final int index = chunk;
                futures.add(pool.submit(() -> {
                    BulkEndpointInfo endpoint = chooseEndpoint(scheduler, usable, index);
                    long offset = (long) index * CHUNK;
                    long length = Math.min(CHUNK, file.length() - offset);
                    long started = System.nanoTime();

                    long sent = sendChunk(file, endpoint, transferId, hash,
                            index, chunkCount, offset, length, maxAttempts);

                    double seconds = Math.max(1e-6,
                            (System.nanoTime() - started) / 1e9);
                    scheduler.path(pathId(endpoint), endpoint.transport)
                            .observe(sent / seconds, -1);
                    return sent;
                }));
            }

            long total = 0;
            for (Future<Long> future : futures) total += future.get();
            if (total != file.length()) {
                throw new java.io.IOException(
                        "Multipath transfer byte count mismatch: "
                                + total + "/" + file.length());
            }
            return total;
        } finally {
            pool.shutdownNow();
        }
    }

    private static BulkEndpointInfo chooseEndpoint(
            SpectralPathScheduler scheduler,
            List<BulkEndpointInfo> endpoints,
            int chunk) {
        List<SpectralPathScheduler.Allocation> allocations = scheduler.allocate();
        if (allocations.isEmpty()) return endpoints.get(chunk % endpoints.size());

        double target = (chunk * 0.6180339887498949) % 1.0;
        double cumulative = 0;
        String selected = allocations.get(allocations.size() - 1).pathId;
        for (SpectralPathScheduler.Allocation allocation : allocations) {
            cumulative += allocation.fraction;
            if (target <= cumulative) {
                selected = allocation.pathId;
                break;
            }
        }

        for (BulkEndpointInfo endpoint : endpoints) {
            if (pathId(endpoint).equals(selected)) return endpoint;
        }
        return endpoints.get(chunk % endpoints.size());
    }

    private static long sendChunk(File file, BulkEndpointInfo endpoint,
                                  String transferId, byte[] hash,
                                  int chunkIndex, int chunkCount,
                                  long offset, long length, int maxAttempts)
            throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return sendChunkOnce(file, endpoint, transferId, hash,
                        chunkIndex, chunkCount, offset, length);
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(Math.min(2000L, 100L * attempt * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw last == null ? new java.io.IOException("Chunk transfer failed") : last;
    }

    private static long sendChunkOnce(File file, BulkEndpointInfo endpoint,
                                      String transferId, byte[] hash,
                                      int chunkIndex, int chunkCount,
                                      long offset, long length)
            throws Exception {
        byte[] token = BulkTransferProtocol.decodeToken(endpoint.tokenBase64);
        if (token.length != BulkTransferProtocol.TOKEN_BYTES) {
            throw new java.io.IOException("Invalid endpoint token");
        }

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

            try (RandomAccessFile source = new RandomAccessFile(file, "r");
                 FileChannel channel = source.getChannel()) {
                ByteBuffer buffer = ByteBuffer.allocate(CHUNK);
                long position = offset;
                long remaining = length;

                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int n = channel.read(buffer, position);
                    if (n < 0) throw new EOFException("Unexpected file EOF");
                    if (n == 0) continue;
                    out.write(buffer.array(), 0, n);
                    position += n;
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
                throw new java.io.IOException(
                        "Chunk rejected: status=" + status + " ack=" + acknowledged);
            }
            return length;
        }
    }

    private static void writeRequest(DataOutputStream out, byte[] token,
                                      String transferId, long fileSize, long offset,
                                      long length, byte[] hash, String name,
                                      int chunkIndex, int chunkCount) throws Exception {
        byte[] id = transferId.getBytes(StandardCharsets.UTF_8);
        byte[] filename = name.getBytes(StandardCharsets.UTF_8);
        if (token.length != 32 || hash.length != 32 || id.length == 0 || id.length > MAX_ID
                || filename.length == 0 || filename.length > MAX_NAME
                || fileSize < 1 || offset < 0 || length <= 0
                || length > CHUNK || offset > fileSize - length
                || chunkIndex < 0 || chunkCount <= chunkIndex || chunkCount > MAX_CHUNKS) {
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

    private static String pathId(BulkEndpointInfo endpoint) {
        return endpoint.transport + ":" + endpoint.host + ":" + endpoint.port;
    }
}
