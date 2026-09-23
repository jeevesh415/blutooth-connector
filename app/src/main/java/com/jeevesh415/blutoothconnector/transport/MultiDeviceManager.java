package com.jeevesh415.blutoothconnector.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MultiDeviceManager implements AutoCloseable {
    public static final int MAX_CLASSIC_PEERS = 7;

    public interface Listener {
        void onConnected(DeviceSession session);
        void onFrame(DeviceSession session, Frame frame);
        void onDisconnected(DeviceSession session, Exception error);
        void onConnectError(BluetoothDevice device, Exception error);
    }

    private final BluetoothTransport transport;
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, BluetoothDevice> knownDevices = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryAttempts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);
    private volatile Listener listener;

    public MultiDeviceManager(BluetoothAdapter adapter, Listener listener) {
        this.listener = listener;
        this.transport = new BluetoothTransport(adapter, new BluetoothTransport.Listener() {
            @Override public void onConnected(BluetoothDevice device, BluetoothSocket socket,
                                              boolean incoming) {
                attach(device, socket);
            }

            @Override public void onError(BluetoothDevice device, Exception error) {
                if (device != null) {
                    retryLater(device, error);
                    if (MultiDeviceManager.this.listener != null) {
                        MultiDeviceManager.this.listener.onConnectError(device, error);
                    }
                }
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void startReceiver() throws Exception {
        transport.listen();
    }

    public void connect(BluetoothDevice device) {
        if (device == null) return;
        if (sessions.size() >= MAX_CLASSIC_PEERS &&
                !sessions.containsKey(device.getAddress())) {
            throw new IllegalStateException("Application peer limit reached: " + MAX_CLASSIC_PEERS);
        }
        knownDevices.put(device.getAddress(), device);
        retryAttempts.remove(device.getAddress());
        transport.connect(device);
    }

    public void connectAll(Collection<BluetoothDevice> devices) {
        int budget = MAX_CLASSIC_PEERS - sessions.size();
        int started = 0;
        for (BluetoothDevice device : devices) {
            if (started >= budget) break;
            if (!sessions.containsKey(device.getAddress())) {
                connect(device);
                started++;
            }
        }
    }

    public List<DeviceSession> sessions() {
        return Collections.unmodifiableList(new ArrayList<>(sessions.values()));
    }

    public DeviceSession session(String address) {
        return sessions.get(address);
    }

    public void send(String address, Frame frame) throws Exception {
        DeviceSession session = sessions.get(address);
        if (session == null) throw new IllegalStateException("Device not connected: " + address);
        session.connection.send(frame);
        session.lastTxMs = System.currentTimeMillis();
    }

    public void broadcast(Frame frame) {
        for (DeviceSession session : sessions.values()) {
            try {
                session.connection.send(frame);
                session.lastTxMs = System.currentTimeMillis();
            } catch (Exception e) {
                listener.onDisconnected(session, e);
            }
        }
    }

    private void attach(BluetoothDevice device, BluetoothSocket socket) {
        final String address = device.getAddress();
        knownDevices.put(address, device);

        DeviceSession old = sessions.remove(address);
        if (old != null) {
            try { old.connection.close(); } catch (Exception ignored) {}
        }

        try {
            final FramedConnection[] holder = new FramedConnection[1];
            holder[0] = new FramedConnection(
                    socket.getInputStream(), socket.getOutputStream(),
                    new FramedConnection.Listener() {
                        @Override public void onFrame(Frame frame) {
                            DeviceSession current = sessions.get(address);
                            if (current != null) {
                                current.lastRxMs = System.currentTimeMillis();
                                if (listener != null) listener.onFrame(current, frame);
                            }
                        }

                        @Override public void onClosed(Exception error) {
                            DeviceSession current = sessions.remove(address);
                            transport.forgetSocket(address, socket);
                            if (current != null) {
                                current.state = DeviceSession.State.RECONNECTING;
                                if (listener != null) listener.onDisconnected(current, error);
                                retryLater(device, error);
                            }
                        }
                    });

            DeviceSession session = new DeviceSession(device, socket, holder[0]);
            sessions.put(address, session);
            retryAttempts.remove(address);
            holder[0].startReader();

            JSONObject hello = new JSONObject()
                    .put("role", "peer")
                    .put("maxPeers", MAX_CLASSIC_PEERS)
                    .put("device", safeName(device));
            holder[0].send(new Frame(
                    Protocol.VERSION, Protocol.HELLO,
                    System.nanoTime(), System.currentTimeMillis(), hello));

            if (listener != null) listener.onConnected(session);
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
            transport.forgetSocket(address, socket);
            retryLater(device, e);
            if (listener != null) listener.onConnectError(device, e);
        }
    }

    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "unknown" : name;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void retryLater(BluetoothDevice device, Exception error) {
        if (device == null) return;
        String address = device.getAddress();
        int attempt = retryAttempts.merge(address, 1, Integer::sum);
        long delay = Math.min(30, 1L << Math.min(attempt - 1, 4));
        scheduler.schedule(() -> {
            if (!sessions.containsKey(address)) transport.connect(device);
        }, delay, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        transport.close();
        sessions.clear();
        knownDevices.clear();
        retryAttempts.clear();
    }
}
