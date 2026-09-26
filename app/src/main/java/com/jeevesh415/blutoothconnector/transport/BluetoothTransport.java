package com.jeevesh415.blutoothconnector.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BluetoothTransport implements AutoCloseable {
    public interface Listener {
        void onConnected(BluetoothDevice device, BluetoothSocket socket, boolean incoming);
        void onError(BluetoothDevice device, Exception error);
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Map<String, BluetoothSocket> sockets = new ConcurrentHashMap<>();
    private final ExecutorService connectExecutor = Executors.newCachedThreadPool();
    private volatile BluetoothServerSocket serverSocket;
    private volatile boolean running;

    public BluetoothTransport(BluetoothAdapter adapter, Listener listener) {
        if (adapter == null) throw new IllegalArgumentException("Bluetooth adapter is null");
        this.adapter = adapter;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public synchronized void listen() throws IOException {
        if (running) return;
        serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                "Blutooth Connector", Protocol.RFCOMM_UUID);
        running = true;

        connectExecutor.execute(() -> {
            while (running) {
                try {
                    BluetoothSocket accepted = serverSocket.accept();
                    BluetoothDevice device = accepted.getRemoteDevice();
                    sockets.put(device.getAddress(), accepted);
                    listener.onConnected(device, accepted, true);
                } catch (Exception e) {
                    if (running) listener.onError(null, e);
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        if (device == null) return;
        connectExecutor.execute(() -> {
            BluetoothSocket previous = sockets.get(device.getAddress());
            if (previous != null && previous.isConnected()) return;

            BluetoothSocket socket = null;
            try {
                adapter.cancelDiscovery();
                socket = device.createRfcommSocketToServiceRecord(Protocol.RFCOMM_UUID);
                socket.connect();
                sockets.put(device.getAddress(), socket);
                listener.onConnected(device, socket, false);
            } catch (Exception e) {
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
                listener.onError(device, e);
            }
        });
    }

    @SuppressLint("MissingPermission")
    public Set<BluetoothDevice> pairedDevices() {
        return adapter.getBondedDevices();
    }

    public int connectedCount() {
        return sockets.size();
    }

    public void disconnect(String address) {
        BluetoothSocket socket = sockets.remove(address);
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void forgetSocket(String address, BluetoothSocket socket) {
        sockets.remove(address, socket);
    }

    private synchronized void closeServer() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    @Override
    public void close() {
        closeServer();
        for (BluetoothSocket socket : sockets.values()) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        sockets.clear();
        connectExecutor.shutdownNow();
    }
}
