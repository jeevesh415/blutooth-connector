package com.jeevesh415.blutoothconnector.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public final class BluetoothTransport implements AutoCloseable {
    public interface Listener {
        void onConnected(BluetoothSocket socket);
        void onError(Exception error);
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket socket;

    public BluetoothTransport(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void listen() throws IOException {
        closeServer();
        serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                "Blutooth Connector", Protocol.RFCOMM_UUID);
        Thread t = new Thread(() -> {
            try {
                BluetoothSocket accepted = serverSocket.accept();
                socket = accepted;
                listener.onConnected(accepted);
            } catch (Exception e) {
                listener.onError(e);
            }
        }, "bluetooth-rfcomm-server");
        t.start();
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        Thread t = new Thread(() -> {
            try {
                closeServer();
                BluetoothSocket outgoing =
                        device.createRfcommSocketToServiceRecord(Protocol.RFCOMM_UUID);
                outgoing.connect();
                socket = outgoing;
                listener.onConnected(outgoing);
            } catch (Exception e) {
                listener.onError(e);
            }
        }, "bluetooth-rfcomm-client");
        t.start();
    }

    @SuppressLint("MissingPermission")
    public Set<BluetoothDevice> pairedDevices() {
        return adapter.getBondedDevices();
    }

    private void closeServer() {
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    @Override
    public void close() {
        closeServer();
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
    }
}
