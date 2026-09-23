package com.jeevesh415.blutoothconnector.transport;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class FramedConnection implements AutoCloseable {
    public interface Listener {
        void onFrame(Frame frame);
        void onClosed(Exception error);
    }

    private final DataInputStream input;
    private final DataOutputStream output;
    private final Listener listener;
    private volatile boolean running;

    public FramedConnection(InputStream input, OutputStream output, Listener listener) {
        if (input == null || output == null || listener == null) {
            throw new IllegalArgumentException("Streams and listener are required");
        }
        this.input = new DataInputStream(input);
        this.output = new DataOutputStream(output);
        this.listener = listener;
    }

    public synchronized void startReader() {
        if (running) return;
        running = true;
        Thread reader = new Thread(() -> {
            Exception failure = null;
            try {
                while (running) {
                    int length = input.readInt();
                    if (length <= 0 || length > Protocol.MAX_FRAME_BYTES) {
                        throw new IOException("Invalid frame length: " + length);
                    }
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    Frame frame = Frame.fromBytes(bytes);
                    if (frame.version != Protocol.VERSION) {
                        throw new IOException("Unsupported protocol version: " + frame.version);
                    }
                    if (frame.type.length() > 32) {
                        throw new IOException("Frame type too long");
                    }
                    listener.onFrame(frame);
                }
            } catch (EOFException e) {
                failure = e;
            } catch (Exception e) {
                failure = e;
            } finally {
                running = false;
                listener.onClosed(failure);
            }
        }, "bluetooth-frame-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized void send(Frame frame) throws Exception {
        if (frame == null) throw new IllegalArgumentException("frame");
        if (frame.version != Protocol.VERSION) {
            throw new IOException("Unsupported protocol version: " + frame.version);
        }
        byte[] bytes = frame.toBytes();
        if (bytes.length <= 0 || bytes.length > Protocol.MAX_FRAME_BYTES) {
            throw new IOException("Frame too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!running && input == null) return;
        running = false;
        input.close();
        output.close();
    }
}
