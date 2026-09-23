package com.jeevesh415.blutoothconnector.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;

public final class ReliableFileTransfer {
    private ReliableFileTransfer() {}

    public static long send(File file, String host, int port, byte[] token) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Not a file: " + file);
        byte[] hash = BulkTransferProtocol.sha256(file);

        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(30_000);
            socket.setTcpNoDelay(false);
            socket.setKeepAlive(true);
            socket.setSendBufferSize(4 * 1024 * 1024);
            socket.setReceiveBufferSize(4 * 1024 * 1024);

            DataOutputStream out = new DataOutputStream(
                    new java.io.BufferedOutputStream(socket.getOutputStream(), BulkTransferProtocol.BUFFER_BYTES));
            DataInputStream in = new DataInputStream(
                    new java.io.BufferedInputStream(socket.getInputStream(), BulkTransferProtocol.BUFFER_BYTES));

            BulkTransferProtocol.writeRequest(out, token, file.length(), 0, hash, file.getName());
            long offset = BulkTransferProtocol.readResponse(in);

            try (java.io.InputStream source = new java.io.BufferedInputStream(
                    new java.io.FileInputStream(file), BulkTransferProtocol.BUFFER_BYTES)) {
                skipFully(source, offset);
                byte[] buffer = new byte[BulkTransferProtocol.BUFFER_BYTES];
                long remaining = file.length() - offset;
                while (remaining > 0) {
                    int wanted = (int) Math.min(buffer.length, remaining);
                    int n = source.read(buffer, 0, wanted);
                    if (n < 0) throw new java.io.EOFException("Unexpected file EOF");
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
            out.flush();

            int status = in.readInt();
            if (status != BulkTransferProtocol.STATUS_OK) {
                throw new java.io.IOException("Receiver integrity/status error: " + status);
            }
            long completed = in.readLong();
            return completed;
        }
    }

    public static long sendWithResume(File file, String host, int port, byte[] token,
                                      int maxAttempts) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            try {
                return send(file, host, port, token);
            } catch (Exception e) {
                last = e;
                try { Thread.sleep(Math.min(5000L, 250L * attempt * attempt)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        throw last;
    }

    public static File receive(Socket socket, File directory, byte[] expectedToken) throws Exception {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create transfer directory");
        }

        DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(socket.getInputStream(), BulkTransferProtocol.BUFFER_BYTES));
        DataOutputStream out = new DataOutputStream(
                new java.io.BufferedOutputStream(socket.getOutputStream(), BulkTransferProtocol.BUFFER_BYTES));

        BulkTransferProtocol.Request request = BulkTransferProtocol.readRequest(in);
        if (!MessageDigest.isEqual(expectedToken, request.token)) {
            BulkTransferProtocol.writeResponse(out, BulkTransferProtocol.STATUS_REJECTED, 0);
            throw new SecurityException("Invalid bulk token");
        }

        String safeName = request.fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String id = BulkTransferProtocol.hex(request.sha256);
        File part = new File(directory, id + ".part");
        File finalFile = new File(directory, safeName);

        long existing = part.isFile() ? part.length() : 0;
        if (existing > request.fileSize) existing = 0;
        BulkTransferProtocol.writeResponse(out, BulkTransferProtocol.STATUS_OK, existing);

        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            raf.setLength(existing);
            raf.seek(existing);

            long remaining = request.fileSize - existing;
            byte[] buffer = new byte[BulkTransferProtocol.BUFFER_BYTES];
            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                int n = in.read(buffer, 0, wanted);
                if (n < 0) throw new java.io.EOFException("Transfer disconnected");
                raf.write(buffer, 0, n);
                remaining -= n;
            }
        }

        byte[] actual = BulkTransferProtocol.sha256(part);
        if (!MessageDigest.isEqual(actual, request.sha256)) {
            BulkTransferProtocol.writeResponse(out, BulkTransferProtocol.STATUS_INTEGRITY_ERROR, part.length());
            if (!part.delete()) {
                part.deleteOnExit();
            }
            throw new java.io.IOException("SHA-256 integrity check failed");
        }

        if (finalFile.exists() && !finalFile.delete()) {
            throw new java.io.IOException("Cannot replace existing file");
        }
        if (!part.renameTo(finalFile)) {
            throw new java.io.IOException("Cannot finalize received file");
        }

        out.writeInt(BulkTransferProtocol.STATUS_OK);
        out.writeLong(finalFile.length());
        out.flush();
        return finalFile;
    }

    private static void skipFully(java.io.InputStream in, long bytes) throws java.io.IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int one = in.read();
            if (one < 0) throw new java.io.EOFException("Cannot seek source to resume offset");
            remaining--;
        }
    }
}
