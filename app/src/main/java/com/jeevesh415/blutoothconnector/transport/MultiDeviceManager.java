package com.jeevesh415.blutoothconnector.transport;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.security.SessionAuthenticator;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final BluetoothCapabilityProfile bluetoothCapabilities;
    private final Context bulkContext;
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, BluetoothDevice> knownDevices = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryAttempts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> connecting = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2);
    private final java.util.concurrent.ExecutorService bulkExecutor =
            Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public MultiDeviceManager(BluetoothAdapter adapter, Listener listener) {
        this(null, adapter, listener);
    }

    public MultiDeviceManager(Context context, BluetoothAdapter adapter, Listener listener) {
        if (adapter == null) throw new IllegalArgumentException("Bluetooth adapter");
        this.bluetoothCapabilities = BluetoothCapabilityProfile.fromAdapter(adapter);
        if (listener != null) listeners.add(listener);
        this.bulkContext = context == null ? null : context.getApplicationContext();

        this.transport = new BluetoothTransport(adapter, new BluetoothTransport.Listener() {
            @Override public void onConnected(BluetoothDevice device, BluetoothSocket socket,
                                              boolean incoming) {
                attach(device, socket, incoming);
            }

            @Override public void onError(BluetoothDevice device, Exception error) {
                if (device != null) {
                    connecting.remove(device.getAddress());
                    retryLater(device);
                }
                notifyConnectError(device, error);
            }
        });

        scheduler.scheduleAtFixedRate(
                this::healthTick, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setListener(Listener listener) {
        listeners.clear();
        if (listener != null) listeners.add(listener);
    }

    public void startReceiver() throws Exception {
        transport.listen();
    }

    public void connect(BluetoothDevice device) {
        if (closed || device == null) return;
        if (sessions.size() >= MAX_CLASSIC_PEERS
                && !sessions.containsKey(device.getAddress())) {
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
        if (devices == null) return;

        for (BluetoothDevice device : devices) {
            if (started >= budget) break;
            if (device != null && !sessions.containsKey(device.getAddress())) {
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

    public void transferFile(String address, File file, TransferListener listener) {
        if (file == null || !file.isFile()) {
            if (listener != null) {
                listener.onError(sessions.get(address),
                        new IllegalArgumentException("Not a readable file"));
            }
            return;
        }

        DeviceSession session = sessions.get(address);
        if (session == null) {
            if (listener != null) {
                listener.onError(null,
                        new IllegalStateException("Device not connected: " + address));
            }
            return;
        }

        bulkExecutor.execute(() -> {
            try {
                List<BulkEndpointInfo> endpoints =
                        new ArrayList<>(session.bulkEndpoints);
                if (endpoints.isEmpty()) {
                    throw new IllegalStateException("No bulk endpoint advertised");
                }

                // Prefer the native Bluetooth LE L2CAP CoC bulk plane. This keeps
                // large transfers on Bluetooth instead of serializing them through
                // the RFCOMM control stream. TCP/Wi-Fi paths remain an explicit
                // fallback/aggregation path when L2CAP is unavailable.
                BulkEndpointInfo bluetooth = null;
                for (BulkEndpointInfo endpoint : endpoints) {
                    if (BluetoothL2capBulkTransport.TRANSPORT.equals(
                            endpoint.transport)
                            && endpoint.psm > 0
                            && endpoint.tokenBase64 != null
                            && !endpoint.tokenBase64.isEmpty()) {
                        bluetooth = endpoint;
                        break;
                    }
                }

                if (bluetooth != null) {
                    byte[] token = BulkTransferProtocol.decodeToken(
                            bluetooth.tokenBase64);
                    try {
                        long bytes = BluetoothL2capBulkTransport.send(
                                session.device,
                                bluetooth.psm,
                                token,
                                file);
                        if (listener != null) listener.onComplete(session, bytes);
                        return;
                    } catch (Exception bluetoothError) {
                        // Fall through to the existing adaptive local-network
                        // multipath scheduler instead of losing the transfer.
                    } finally {
                        java.util.Arrays.fill(token, (byte) 0);
                    }
                }

                long bytes = MultipathFileTransfer.send(
                        bulkContext, file, endpoints, 4);
                if (listener != null) listener.onComplete(session, bytes);
            } catch (Exception error) {
                if (listener != null) listener.onError(session, error);
            }
        });
    }

    public void send(String address, Frame frame) throws Exception {
        DeviceSession session = sessions.get(address);
        if (session == null) {
            throw new IllegalStateException("Device not connected: " + address);
        }
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

    @SuppressLint("MissingPermission")
    private void attach(BluetoothDevice device, BluetoothSocket socket, boolean incoming) {
        final String address = device.getAddress();
        knownDevices.put(address, device);
        connecting.remove(address);

        if (incoming && device.getBondState() != BluetoothDevice.BOND_BONDED) {
            try { socket.close(); } catch (Exception ignored) {}
            notifyConnectError(
                    device,
                    new SecurityException("Unpaired incoming Bluetooth device rejected"));
            return;
        }

        if (sessions.size() >= MAX_CLASSIC_PEERS
                && !sessions.containsKey(address)) {
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
            final DeviceSession[] sessionHolder = new DeviceSession[1];
            holder[0] = new FramedConnection(
                    socket.getInputStream(),
                    socket.getOutputStream(),
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
                                        org.json.JSONObject item =
                                                endpoints.optJSONObject(i);
                                        if (item == null) continue;
                                        try {
                                            current.bulkEndpoints.add(
                                                    BulkEndpointInfo.fromJson(item));
                                        } catch (Exception ignored) {
                                            // Ignore one malformed endpoint.
                                        }
                                    }
                                }
                            }

                            if (Protocol.PONG.equals(frame.type)) {
                                long sent = frame.payload.optLong("t0", 0);
                                if (sent == current.lastPingSentNs && sent != 0) {
                                    long rttMs =
                                            (System.nanoTime() - sent) / 1_000_000L;
                                    if (rttMs >= 0 && rttMs < 300_000) {
                                        current.metrics.observe(rttMs);
                                        BluetoothL2capBulkTransport.observeRtt(
                                                address,
                                                System.nanoTime() - sent);
                                    }
                                }
                            }

                            notifyFrame(current, frame);
                        }

                        @Override public void onClosed(Exception error) {
                            DeviceSession current = sessionHolder[0];
                            if (current != null
                                    && sessions.remove(address, current)) {
                                BluetoothL2capBulkTransport.forgetPeer(address);
                                transport.forgetSocket(address, socket);
                                current.state =
                                        DeviceSession.State.RECONNECTING;
                                notifyDisconnected(current, error);
                                retryLater(device);
                            }
                        }
                    });

            DeviceSession session =
                    new DeviceSession(device, socket, holder[0]);
            sessionHolder[0] = session;
            sessions.put(address, session);
            retryAttempts.remove(address);
            holder[0].startReader();

            JSONObject hello = new JSONObject()
                    .put("role", "peer")
                    .put("bluetooth", bluetoothCapabilities.toJson())
                    .put("maxPeers", MAX_CLASSIC_PEERS)
                    .put("device", safeName(device));

            if (bulkContext != null) {
                hello.put(
                        "identityKey",
                        SessionAuthenticator.publicKeyBase64(bulkContext));
                hello.put(
                        "authNonce",
                        java.util.Base64.getEncoder()
                                .encodeToString(session.localAuthNonce));
            }

            holder[0].send(new Frame(
                    Protocol.VERSION,
                    Protocol.HELLO,
                    session.nextSequence(),
                    System.currentTimeMillis(),
                    hello));

            notifyConnected(session);
        } catch (Exception e) {
            try { socket.close(); } catch (Exception ignored) {}
            transport.forgetSocket(address, socket);
            connecting.remove(address);
            retryLater(device);
            notifyConnectError(device, e);
        }
    }

    private void healthTick() {
        if (closed) return;
        long now = System.currentTimeMillis();

        for (DeviceSession session : sessions.values()) {
            if (now - session.lastRxMs > DEAD_AFTER_MS) {
                closeSession(
                        session,
                        new java.io.IOException("Peer heartbeat timeout"));
                continue;
            }

            if (!session.authenticated) continue;

            try {
                long t0 = System.nanoTime();
                session.lastPingSentNs = t0;
                session.connection.send(new Frame(
                        Protocol.VERSION,
                        Protocol.PING,
                        session.nextSequence(),
                        now,
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
            BluetoothL2capBulkTransport.forgetPeer(address);
            session.state = DeviceSession.State.RECONNECTING;
            try { session.connection.close(); } catch (Exception ignored) {}
            transport.forgetSocket(address, session.socket);
            notifyDisconnected(session, error);
            retryLater(session.device);
        }
    }

    private void retryLater(BluetoothDevice device) {
        if (closed || device == null) return;
        String address = device.getAddress();
        int attempt = retryAttempts.merge(address, 1, Integer::sum);
        long delay = Math.min(30L, 1L << Math.min(attempt - 1, 4));

        scheduler.schedule(() -> {
            if (!closed
                    && !sessions.containsKey(address)
                    && connecting.putIfAbsent(address, Boolean.TRUE) == null) {
                transport.connect(device);
            }
        }, delay, TimeUnit.SECONDS);
    }

    private void notifyConnected(DeviceSession session) {
        for (Listener l : listeners) {
            try { l.onConnected(session); } catch (Exception ignored) {}
        }
    }

    private void notifyFrame(DeviceSession session, Frame frame) {
        for (Listener l : listeners) {
            try { l.onFrame(session, frame); } catch (Exception ignored) {}
        }
    }

    private void notifyDisconnected(DeviceSession session, Exception error) {
        for (Listener l : listeners) {
            try { l.onDisconnected(session, error); } catch (Exception ignored) {}
        }
    }

    private void notifyConnectError(BluetoothDevice device, Exception error) {
        for (Listener l : listeners) {
            try { l.onConnectError(device, error); } catch (Exception ignored) {}
        }
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "unknown" : name;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        bulkExecutor.shutdownNow();
        transport.close();
        sessions.clear();
        knownDevices.clear();
        retryAttempts.clear();
        connecting.clear();
        listeners.clear();
    }
}
