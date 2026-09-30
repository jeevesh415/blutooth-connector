package com.jeevesh415.blutoothconnector.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Native Bluetooth LE L2CAP CoC bulk transport.
 *
 * RFCOMM remains the authenticated control plane.  This channel is opened only
 * after the peer has received the dynamic PSM over the authenticated control
 * session.  Android's secure L2CAP CoC supplies link authentication/encryption;
 * BCL3 AES-GCM/HMAC remains the application-level transfer protection.
 *
 * The implementation deliberately adapts to the controller's packet size rather
 * than inventing a larger radio packet.  This removes avoidable host-side
 * fragmentation while preserving the Bluetooth controller's actual limits.
 */
public final class BluetoothL2capBulkTransport implements AutoCloseable {
    public interface Listener {
        void onTransferComplete(File file);
        void onError(Exception error);
    }

    public static final String TRANSPORT = "bluetooth-le-l2cap";
    private static final int MAGIC = 0x424C5434; // BLT4
    private static final int VERSION = 1;
    private static final int CHUNK_BYTES = 512 * 1024;
    private static final int MAX_NAME_BYTES = 512;
    private static final int MAX_ID_BYTES = 64;
    private static final int MAX_CHUNKS = 1_000_000;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int IO_TIMEOUT_MS = 30_000;
    private static final int WATCHDOG_PERIOD_MS = 5_000;

    private static final ExecutorService CONNECT_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "bluetooth-l2cap-connect");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "bluetooth-l2cap-watchdog");
                t.setDaemon(true);
                return t;
            });

    private static final Map<String, BluetoothThroughputOptimizer> OPTIMIZERS =
            new ConcurrentHashMap<>();

    private final BluetoothAdapter adapter;
    private final ExecutorService acceptExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService transferExecutor =
            Executors.newCachedThreadPool();
    private final SecureRandom random = new SecureRandom();
    private final BluetoothThroughputOptimizer throughputOptimizer =
            new BluetoothThroughputOptimizer();

    private volatile BluetoothServerSocket server;
    private volatile byte[] token;
    private volatile int psm = -1;
    private volatile File directory;
    private volatile Listener listener;

    public BluetoothL2capBulkTransport(BluetoothAdapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("Bluetooth adapter");
        this.adapter = adapter;
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean start(File directory, Listener listener) {
        if (Build.VERSION.SDK_INT < 29 || server != null) {
            return server != null;
        }
        if (directory == null) throw new IllegalArgumentException("directory");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create Bluetooth bulk directory");
        }

        try {
            BluetoothServerSocket candidate =
                    adapter.listenUsingL2capChannel();
            this.server = candidate;
            this.psm = candidate.getPsm();
            this.directory = directory;
            this.listener = listener;
            this.token = new byte[MultipathCrypto.TOKEN_BYTES];
            random.nextBytes(this.token);

            acceptExecutor.execute(() -> acceptLoop(candidate));
            return true;
        } catch (Exception error) {
            close();
            if (listener != null) listener.onError(error);
            return false;
        }
    }

    public boolean available() {
        return Build.VERSION.SDK_INT >= 29 && server != null && psm > 0;
    }

    public int psm() {
        return psm;
    }

    public byte[] authorizationToken() {
        byte[] value = token;
        return value == null ? null : value.clone();
    }

    private void acceptLoop(BluetoothServerSocket expected) {
        while (server == expected) {
            try {
                BluetoothSocket socket = expected.accept();
                transferExecutor.execute(() -> receive(socket));
            } catch (Exception error) {
                if (server == expected && listener != null) {
                    listener.onError(error);
                }
                return;
            }
        }
    }

    private void receive(BluetoothSocket socket) {
        try (BluetoothSocket closeable = socket) {
            int packet = safePacketSize(
                    closeable.getMaxReceivePacketSize());
            int pipeBuffer = throughputOptimizer.bufferBytes(packet);
            AtomicLong lastActivity = new AtomicLong(System.nanoTime());
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(
                            new ActivityInputStream(closeable.getInputStream(), lastActivity),
                            pipeBuffer));
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(
                            new ActivityOutputStream(closeable.getOutputStream(), lastActivity),
                            pipeBuffer));
            java.util.concurrent.ScheduledFuture<?> watchdog =
                    startWatchdog(closeable, lastActivity);

            if (in.readInt() != MAGIC
                    || in.readInt() != VERSION) {
                throw new IOException("Unsupported Bluetooth bulk protocol");
            }

            String transferId = readString(in, MAX_ID_BYTES);
            long fileSize = in.readLong();
            int chunkSize = in.readInt();
            int chunkCount = in.readInt();
            byte[] expectedHash = new byte[MultipathCrypto.HASH_BYTES];
            in.readFully(expectedHash);
            String name = readString(in, MAX_NAME_BYTES);

            if (fileSize < 1
                    || chunkSize < 16 * 1024
                    || chunkSize > CHUNK_BYTES
                    || chunkCount < 1
                    || chunkCount > MAX_CHUNKS) {
                throw new IOException("Invalid Bluetooth transfer metadata");
            }

            File destination = safeDestination(directory, name);
            File partial = new File(
                    destination.getParentFile(),
                    "." + destination.getName() + "." + transferId + ".part");

            try (RandomAccessFile output =
                         new RandomAccessFile(partial, "rw")) {
                output.setLength(fileSize);

                for (int expectedIndex = 0;
                        expectedIndex < chunkCount;
                        expectedIndex++) {
                    int index = in.readInt();
                    int plaintextLength = in.readInt();
                    int ciphertextLength = in.readInt();

                    if (index != expectedIndex
                            || plaintextLength < 1
                            || plaintextLength > chunkSize
                            || ciphertextLength != plaintextLength
                                    + MultipathCrypto.GCM_TAG_BYTES) {
                        throw new IOException("Invalid Bluetooth chunk");
                    }

                    byte[] iv = new byte[MultipathCrypto.GCM_IV_BYTES];
                    byte[] authorization = new byte[MultipathCrypto.HASH_BYTES];
                    in.readFully(iv);
                    in.readFully(authorization);

                    byte[] ciphertext = new byte[ciphertextLength];
                    in.readFully(ciphertext);

                    long offset = (long) index * chunkSize;
                    long expectedLength = Math.min(
                            chunkSize, fileSize - offset);
                    if (offset < 0
                            || offset >= fileSize
                            || plaintextLength != expectedLength) {
                        throw new IOException("Invalid Bluetooth chunk offset");
                    }

                    if (!MultipathCrypto.verifyAuthorizationTag(
                            token,
                            authorization,
                            transferId,
                            fileSize,
                            offset,
                            plaintextLength,
                            expectedHash,
                            index,
                            chunkCount,
                            name,
                            iv)) {
                        throw new SecurityException(
                                "Bluetooth bulk authorization failed");
                    }

                    byte[] aad = MultipathCrypto.descriptor(
                            transferId,
                            fileSize,
                            offset,
                            plaintextLength,
                            expectedHash,
                            index,
                            chunkCount,
                            name,
                            iv);
                    byte[] plaintext = MultipathCrypto.decrypt(
                            token,
                            transferId,
                            index,
                            ciphertext,
                            aad,
                            iv);

                    output.seek(offset);
                    output.write(plaintext);
                    java.util.Arrays.fill(plaintext, (byte) 0);
                    java.util.Arrays.fill(ciphertext, (byte) 0);
                }
            }

            byte[] actualHash = BulkTransferProtocol.sha256(partial);
            if (!MessageDigest.isEqual(expectedHash, actualHash)) {
                //noinspection ResultOfMethodCallIgnored
                partial.delete();
                out.writeInt(2);
                out.flush();
                throw new IOException("Bluetooth bulk SHA-256 mismatch");
            }

            if (destination.exists() && !destination.delete()) {
                throw new IOException("Cannot replace destination");
            }
            if (!partial.renameTo(destination)) {
                throw new IOException("Cannot promote Bluetooth transfer");
            }

            out.writeInt(0);
            out.flush();
            if (listener != null) listener.onTransferComplete(destination);
        } catch (Exception error) {
            if (listener != null) listener.onError(error);
        }
    }

    @SuppressLint("MissingPermission")
    public static long send(
            BluetoothDevice device,
            int remotePsm,
            byte[] token,
            File file) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            throw new UnsupportedOperationException("Bluetooth LE L2CAP requires API 29+");
        }
        if (device == null || remotePsm <= 0) {
            throw new IllegalArgumentException("Bluetooth L2CAP endpoint");
        }
        if (token == null || token.length != MultipathCrypto.TOKEN_BYTES) {
            throw new IllegalArgumentException("Bluetooth bulk token");
        }
        if (file == null || !file.isFile() || file.length() < 1) {
            throw new IllegalArgumentException("Not a non-empty file");
        }

        byte[] hash = BulkTransferProtocol.sha256(file);
        String transferId = UUID.randomUUID().toString();
        String name = file.getName();
        int chunkSize = CHUNK_BYTES;
        int chunkCount = (int) ((file.length() + chunkSize - 1L) / chunkSize);

        BluetoothSocket socket = device.createL2capChannel(remotePsm);
        final long transferStartedNanos = System.nanoTime();
        final String deviceAddress = device.getAddress();
        final BluetoothThroughputOptimizer transferOptimizer =
                OPTIMIZERS.computeIfAbsent(
                        deviceAddress == null ? "unknown" : deviceAddress,
                        ignored -> new BluetoothThroughputOptimizer());
        try (BluetoothSocket closeable = socket) {
            connectWithTimeout(closeable, CONNECT_TIMEOUT_MS);

            int packet = safePacketSize(
                    closeable.getMaxTransmitPacketSize());
            int pipeBuffer = transferOptimizer.bufferBytes(packet);
            int writeQuantum = Math.max(16 * 1024, packet);

            AtomicLong lastActivity = new AtomicLong(System.nanoTime());
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(
                            new ActivityInputStream(closeable.getInputStream(), lastActivity),
                            pipeBuffer));
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(
                            new ActivityOutputStream(closeable.getOutputStream(), lastActivity),
                            pipeBuffer));
            java.util.concurrent.ScheduledFuture<?> watchdog =
                    startWatchdog(closeable, lastActivity);

            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeString(out, transferId, MAX_ID_BYTES);
            out.writeLong(file.length());
            out.writeInt(chunkSize);
            out.writeInt(chunkCount);
            out.write(hash);
            writeString(out, name, MAX_NAME_BYTES);
            out.flush();

            byte[] plaintext = new byte[chunkSize];
            try (FileInputStream input = new FileInputStream(file)) {
                for (int index = 0; index < chunkCount; index++) {
                    long offset = (long) index * chunkSize;
                    int expected = (int) Math.min(
                            chunkSize, file.length() - offset);

                    int read = 0;
                    while (read < expected) {
                        int n = input.read(
                                plaintext,
                                read,
                                expected - read);
                        if (n < 0) throw new EOFException("Unexpected file EOF");
                        read += n;
                    }

                    byte[] iv = MultipathCrypto.deterministicIv(
                            transferId, index);
                    byte[] aad = MultipathCrypto.descriptor(
                            transferId,
                            file.length(),
                            offset,
                            expected,
                            hash,
                            index,
                            chunkCount,
                            name,
                            iv);
                    byte[] authorization =
                            MultipathCrypto.authorizationTag(
                                    token,
                                    transferId,
                                    file.length(),
                                    offset,
                                    expected,
                                    hash,
                                    index,
                                    chunkCount,
                                    name,
                                    iv);
                    byte[] ciphertext = MultipathCrypto.encrypt(
                            token,
                            transferId,
                            index,
                            java.util.Arrays.copyOf(plaintext, expected),
                            aad,
                            iv);

                    out.writeInt(index);
                    out.writeInt(expected);
                    out.writeInt(ciphertext.length);
                    out.write(iv);
                    out.write(authorization);
                    writePacketAligned(out, ciphertext, writeQuantum);
                    java.util.Arrays.fill(ciphertext, (byte) 0);
                }
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }

            out.flush();
            transferOptimizer.observeThroughput(
                    file.length(),
                    Math.max(1L, System.nanoTime() - transferStartedNanos));
            int status = in.readInt();
            if (status != 0) {
                throw new IOException(
                        "Bluetooth bulk receiver rejected transfer: " + status);
            }
            return file.length();
        }
    }

    private static void connectWithTimeout(
            BluetoothSocket socket, long timeoutMs) throws Exception {
        Future<?> future = CONNECT_EXECUTOR.submit(socket::connect);
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            try { socket.close(); } catch (Exception ignored) {}
            throw new IOException("Bluetooth L2CAP connect timed out", timeout);
        } catch (java.util.concurrent.ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Bluetooth L2CAP connect failed", cause);
        } finally {
            future.cancel(true);
        }
    }

    private static java.util.concurrent.ScheduledFuture<?> startWatchdog(
            BluetoothSocket socket, AtomicLong lastActivity) {
        final java.util.concurrent.ScheduledFuture<?>[] holder =
                new java.util.concurrent.ScheduledFuture<?>[1];
        holder[0] = WATCHDOG.scheduleAtFixedRate(() -> {
            if (!socket.isConnected()) {
                holder[0].cancel(false);
                return;
            }
            long idleMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - lastActivity.get());
            if (idleMs > IO_TIMEOUT_MS) {
                try { socket.close(); } catch (Exception ignored) {}
                holder[0].cancel(false);
            }
        }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
        return holder[0];
    }

    private static final class ActivityInputStream extends java.io.FilterInputStream {
        private final AtomicLong activity;
        ActivityInputStream(InputStream in, AtomicLong activity) {
            super(in);
            this.activity = activity;
        }
        @Override public int read() throws IOException {
            int value = super.read();
            activity.set(System.nanoTime());
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int value = super.read(b, off, len);
            activity.set(System.nanoTime());
            return value;
        }
    }

    private static final class ActivityOutputStream extends java.io.FilterOutputStream {
        private final AtomicLong activity;
        ActivityOutputStream(OutputStream out, AtomicLong activity) {
            super(out);
            this.activity = activity;
        }
        @Override public void write(int b) throws IOException {
            super.write(b);
            activity.set(System.nanoTime());
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            super.write(b, off, len);
            activity.set(System.nanoTime());
        }
    }

    private static void writePacketAligned(
            DataOutputStream out,
            byte[] data,
            int quantum) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int length = Math.min(
                    data.length - offset,
                    Math.max(quantum, 64 * 1024));
            out.write(data, offset, length);
            offset += length;
        }
    }

    private static int safePacketSize(int value) {
        return value > 0 ? Math.min(value, 64 * 1024) : 1024;
    }

    private static String readString(
            DataInputStream in,
            int maxBytes) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > maxBytes) {
            throw new IOException("Invalid string length");
        }
        byte[] value = new byte[length];
        in.readFully(value);
        return new String(value, StandardCharsets.UTF_8);
    }

    private static void writeString(
            DataOutputStream out,
            String value,
            int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 0 || bytes.length > maxBytes) {
            throw new IOException("Invalid string");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static File safeDestination(
            File directory,
            String name) throws IOException {
        String safe = new File(name).getName();
        if (!safe.equals(name)
                || safe.isEmpty()
                || ".".equals(safe)
                || "..".equals(safe)) {
            throw new IOException("Unsafe Bluetooth destination");
        }
        return new File(directory, safe);
    }

    @Override
    public synchronized void close() {
        BluetoothServerSocket current = server;
        server = null;
        psm = -1;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) {}
        }
        byte[] old = token;
        token = null;
        if (old != null) java.util.Arrays.fill(old, (byte) 0);
        acceptExecutor.shutdownNow();
        transferExecutor.shutdownNow();
    }
}
