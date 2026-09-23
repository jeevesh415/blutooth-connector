package com.jeevesh415.blutoothconnector.transport;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class DeviceSession {
    public enum State {
        CONNECTING, CONNECTED, RECONNECTING, CLOSED
    }

    public final BluetoothDevice device;
    public final BluetoothSocket socket;
    public final FramedConnection connection;
    public final ConnectionMetrics metrics = new ConnectionMetrics();
    public final List<BulkEndpointInfo> bulkEndpoints = new CopyOnWriteArrayList<>();

    private final AtomicLong txSequence = new AtomicLong(0);

    public volatile State state = State.CONNECTED;
    public volatile long connectedAtMs;
    public volatile long lastRxMs;
    public volatile long lastTxMs;
    public volatile long lastPingSentNs;
    public volatile long lastRxSequence = Long.MIN_VALUE;
    public volatile int reconnectAttempt;

    public DeviceSession(BluetoothDevice device, BluetoothSocket socket,
                          FramedConnection connection) {
        this.device = device;
        this.socket = socket;
        this.connection = connection;
        this.connectedAtMs = System.currentTimeMillis();
        this.lastRxMs = this.connectedAtMs;
        this.lastTxMs = this.connectedAtMs;
    }

    public long nextSequence() {
        return txSequence.incrementAndGet();
    }

    public String address() { return device.getAddress(); }
}
