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
import java.io.File;
import java.util.concurrent.TimeUnit;

public final class MultiDeviceManager implements AutoCloseable {
    public static final int MAX_CLASSIC_PEERS = 7;
    private static final long HEARTBEAT_MS = 5000;
    private static final long DEAD_AFTER_MS = 15000;

    public interface Listener {
        void onConnected(DeviceSession session);
        void onFrame(DeviceSession session, Frame frame);
        void onDisconnected(DeviceSession session, Exception error);
        void onConnectError(BluetoothDevice device, Exception error);
    }

    public interface TransferListener {
        void onComplete(DeviceSession session, long bytes);
        void onError(DeviceSession session, Exception error);
    }

    private final BluetoothTransport transport;
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, BluetoothDevice> knownDevices = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryAttempts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> connecting = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);
    private final java.util.concurrent.ExecutorService bulkExecutor =
            Executors.newCachedThreadPool();
    private volatile Listener listener;
    private volatile boolean closed;

    public MultiDeviceManager(BluetoothAdapter adapter, Listener listener) {
        this.listener = listener;
        this.transport = new BluetoothTransport(adapter, new BluetoothTransport.Listener() {
            @Override public void onConnected(BluetoothDevice device, BluetoothSocket socket,
                                              boolean incoming) {
                attach(device, socket);
            }

            @Override public void onError(BluetoothDevice device, Exception error) {
                if (device != null) {
                    connecting.remove(device.getAddress());
                    retryLater(device);
                    if (MultiDeviceManager.this.listener != null) {
                        MultiDeviceManager.this.listener.onConnectError(device, error);
                    }
                }
            }
        });
        scheduler.scheduleAtFixedRate(this::healthTick,
                HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public void startReceiver() throws Exception { transport.listen(); }

    public void connect(BluetoothDevice device) {
        if (closed || device == null) return;
        if (sessions.size() >= MAX_CLASSIC_PEERS &&
                !sessions.containsKey(device.getAddress())) {
            throw new IllegalStateException(
                    "Application peer limit reached: " + MAX_CLASSIC_PEERS);
        }
        String address = device.getAddress();
        knownDevices.put(address, device);
        if (connecting.putIfAbsent(address, Boolean.TRUE) == null) {
            transport.connect(device);
        }
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

    public DeviceSession session(String address) { return sessions.get(address); }

    public void transferFile(String address, File file, TransferListener listener) {
        DeviceSession session = sessions.get(address);
        if (session == null) {
            if (listener != null) listener.onError(null,
                    new IllegalStateException("Device not connected: " + address));
            return;
        }

        bulkExecutor.execute(() -> {
            Exception last = null;
            for (BulkEndpointInfo endpoint : session.bulkEndpoints) {
                try {
                    if (endpoint.host.isEmpty() || endpoint.port < 1) continue;
                    byte[] token = BulkTransferProtocol.decodeToken(endpoint.tokenBase64);
                    long bytes = ReliableFileTransfer.sendWithResume(
                            file, endpoint.host, endpoint.port, token, 5);
                    if (listener != null) listener.onComplete(session, bytes);
                    return;
                } catch (Exception error) {
                    last = error;
                }
            }
            if (listener != null) {
                listener.onError(session, last == null
                        ? new IllegalStateException("No bulk endpoint advertised")
                        : last);
            }
        });
    }

    public void send(String address, Frame frame) throws Exception {
        DeviceSession session = sessions.get(address);
        if (session == null) throw new IllegalStateException(
                "Device not connected: " + address);
        session.connection.send(frame);
        session.lastTxMs = System.currentTimeMillis();
    }

    public void broadcast(Frame frame) {
        for (DeviceSession session : sessions.values()) {
            try {
                Frame perSession = new Frame(
                        frame.version,
                        frame.type,
                        session.nextSequence(),
                        frame.timestampMs,
                        frame.payload);
                session.connection.send(perSession);
                session.lastTxMs = System.currentTimeMillis();
            } catch (Exception e) {
                closeSession(session, e);
            }
        }
    }

    private void attach(BluetoothDevice device, BluetoothSocket socket) {
        final String address = device.getAddress();
        knownDevices.put(address, device);
        connecting.remove(address);

        if (sessions.size() >= MAX_CLASSIC_PEERS && !sessions.containsKey(address)) {
            try { socket.close(); } catch (Exception ignored) {}
            retryLater(device);
            return;
        }

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
                            if (current == null) return;
                            if (frame.sequence < current.lastRxSequence) return;
                            if (frame.sequence > current.lastRxSequence) {
                                current.lastRxSequence = frame.sequence;
                            }
                            current.lastRxMs = System.currentTimeMillis();

                            if (Protocol.CAPABILITIES.equals(frame.type)) {
                                current.bulkEndpoints.clear();
                                org.json.JSONArray endpoints =
                                        frame.payload.optJSONArray("bulkEndpoints");
                                if (endpoints != null) {
                                    for (int i = 0; i < endpoints.length(); i++) {
                                        org.json.JSONObject item = endpoints.optJSONObject(i);
                                        if (item != null) {
                                            current.bulkEndpoints.add(
                                                    BulkEndpointInfo.fromJson(item));
                                        }
                                    }
                                }
                            }

                            if (Protocol.PONG.equals(frame.type)) {
                                long sent = frame.payload.optLong("t0", 0);
                                if (sent == current.lastPingSentNs && sent != 0) {
                                    long rttMs = (System.nanoTime() - sent) / 1_000_000L;
                                    current.metrics.observe(rttMs);
                                }
                            }

                            if (listener != null) listener.onFrame(current, frame);
                        }

                        @Override public void onClosed(Exception error) {
                            DeviceSession current = sessions.remove(address);
                            transport.forgetSocket(address, socket);
                            if (current != null) {
                                current.state = DeviceSession.State.RECONNECTING;
                                if (listener != null) listener.onDisconnected(current, error);
                                retryLater(device);
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
                    session.nextSequence(), System.currentTimeMillis(), hello));

            if (listener != null) listener.onConnected(session);
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
            transport.forgetSocket(address, socket);
            connecting.remove(address);
            retryLater(device);
            if (listener != null) listener.onConnectError(device, e);
        }
    }

    private void healthTick() {
        if (closed) return;
        long now = System.currentTimeMillis();

        for (DeviceSession session : sessions.values()) {
            if (now - session.lastRxMs > DEAD_AFTER_MS) {
                closeSession(session, new java.io.IOException("Peer heartbeat timeout"));
                continue;
            }

            try {
                long t0 = System.nanoTime();
                session.lastPingSentNs = t0;
                session.connection.send(new Frame(
                        Protocol.VERSION, Protocol.PING,
                        session.nextSequence(), now,
                        new JSONObject().put("t0", t0)));
                session.lastTxMs = now;
            } catch (Exception e) {
                closeSession(session, e);
            }
        }
    }

    private void closeSession(DeviceSession session, Exception error) {
        String address = session.address();
        if (sessions.remove(address, session)) {
            session.state = DeviceSession.State.RECONNECTING;
            try { session.connection.close(); } catch (Exception ignored) {}
            transport.forgetSocket(address, session.socket);
            if (listener != null) listener.onDisconnected(session, error);
            retryLater(session.device);
        }
    }

    private void retryLater(BluetoothDevice device) {
        if (closed || device == null) return;
        String address = device.getAddress();
        int attempt = retryAttempts.merge(address, 1, Integer::sum);
        long delay = Math.min(30, 1L << Math.min(attempt - 1, 4));
        scheduler.schedule(() -> {
            if (!closed && !sessions.containsKey(address)
                    && connecting.putIfAbsent(address, Boolean.TRUE) == null) {
                transport.connect(device);
            }
        }, delay, TimeUnit.SECONDS);
    }

    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "unknown" : name;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override public void close() {
        closed = true;
        scheduler.shutdownNow();
        bulkExecutor.shutdownNow();
        transport.close();
        sessions.clear();
        knownDevices.clear();
        retryAttempts.clear();
        connecting.clear();
    }
}
