package com.jeevesh415.blutoothconnector.transport;

import com.jeevesh415.blutoothconnector.protocol.Frame;
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
        this.input = new DataInputStream(input);
        this.output = new DataOutputStream(output);
        this.listener = listener;
    }

    public void startReader() {
        if (running) return;
        running = true;
        Thread reader = new Thread(() -> {
            Exception failure = null;
            try {
                while (running) {
                    int length = input.readInt();
                    if (length <= 0 || length > 64 * 1024) {
                        throw new IOException("Invalid frame length: " + length);
                    }
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    listener.onFrame(Frame.fromBytes(bytes));
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
        reader.start();
    }

    public synchronized void send(Frame frame) throws Exception {
        byte[] bytes = frame.toBytes();
        if (bytes.length > 64 * 1024) throw new IOException("Frame too large");
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    @Override
    public void close() throws IOException {
        running = false;
        input.close();
        output.close();
    }
}
