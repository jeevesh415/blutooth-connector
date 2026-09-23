package com.jeevesh415.blutoothconnector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Binder;
import android.os.IBinder;
import android.content.Intent;
import android.content.pm.ServiceInfo;

import com.jeevesh415.blutoothconnector.capability.CapabilityRegistry;
import com.jeevesh415.blutoothconnector.capability.DeviceInfoCapability;
import com.jeevesh415.blutoothconnector.protocol.CommandRouter;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;
import com.jeevesh415.blutoothconnector.transport.TcpBulkEndpoint;
import com.jeevesh415.blutoothconnector.transport.WifiDirectPathManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ConnectionService extends Service {
    private static final String CHANNEL = "connection";
    private static final int NOTIFICATION_ID = 7;

    private final IBinder binder = new LocalBinder();
    private final CapabilityRegistry registry = new CapabilityRegistry();
    private final CommandRouter router = new CommandRouter(registry);

    private MultiDeviceManager peers;
    private TcpBulkEndpoint bulk;
    private WifiDirectPathManager wifiDirect;
    private final ScheduledExecutorService capabilityRefresh =
            Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ExecutorService commandExecutor =
            Executors.newFixedThreadPool(4);

    public final class LocalBinder extends Binder {
        public ConnectionService service() {
            return ConnectionService.this;
        }
    }

    public MultiDeviceManager peers() {
        return peers;
    }

    public synchronized void startWifiDirect() {
        if (wifiDirect != null) return;
        try {
            wifiDirect = new WifiDirectPathManager(
                    this,
                    new WifiDirectPathManager.Listener() {
                        @Override public void onPeerDiscovered(
                                android.net.wifi.p2p.WifiP2pDevice device) {}

                        @Override public void onConnected(
                                android.net.wifi.p2p.WifiP2pInfo info,
                                android.net.Network network,
                                String ipv4) {
                            // Refresh capabilities so a new P2P endpoint is advertised.
                            MultiDeviceManager manager = peers;
                            if (manager != null) {
                                for (DeviceSession session : manager.sessions()) {
                                    sendCapabilities(session);
                                }
                            }
                        }

                        @Override public void onDisconnected() {
                            MultiDeviceManager manager = peers;
                            if (manager != null) {
                                for (DeviceSession session : manager.sessions()) {
                                    sendCapabilities(session);
                                }
                            }
                        }

                        @Override public void onError(Exception error) {}
                    });
            wifiDirect.start();
        } catch (Exception error) {
            wifiDirect = null;
        }
    }

    public synchronized WifiDirectPathManager wifiDirect() {
        return wifiDirect;
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification());
        }

        registry.register(new DeviceInfoCapability());

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        ensureBulkEndpoint();

        peers = new MultiDeviceManager(
                this,
                adapter,
                new MultiDeviceManager.Listener() {
                    @Override public void onConnected(DeviceSession session) {
                        sendCapabilities(session);
                    }

                    @Override public void onFrame(
                            DeviceSession session, Frame frame) {
                        handleFrame(session, frame);
                    }

                    @Override public void onDisconnected(
                            DeviceSession session, Exception error) {}

                    @Override public void onConnectError(
                            BluetoothDevice device, Exception error) {}
                });

        try {
            peers.startReceiver();
        } catch (Exception ignored) {}
    }

    public synchronized void ensureBulkEndpoint() {
        if (bulk != null) return;

        TcpBulkEndpoint candidate = new TcpBulkEndpoint();
        try {
            candidate.start(
                    new File(getFilesDir(), "transfers"),
                    new TcpBulkEndpoint.Listener() {
                        @Override public void onTransferComplete(File file) {}

                        @Override public void onError(Exception error) {}
                    });
            bulk = candidate;

            MultiDeviceManager manager = peers;
            if (manager != null) {
                for (DeviceSession session : manager.sessions()) {
                    sendCapabilities(session);
                }
            }
        } catch (Exception error) {
            try { candidate.close(); } catch (Exception ignored) {}
            bulk = null;
        }
    }

    private void handleFrame(DeviceSession session, Frame frame) {
        try {
            if (Protocol.HELLO.equals(frame.type)) {
                sendCapabilities(session);
                return;
            }

            if (Protocol.PING.equals(frame.type)) {
                session.connection.send(new Frame(
                        Protocol.VERSION,
                        Protocol.PONG,
                        session.nextSequence(),
                        System.currentTimeMillis(),
                        new JSONObject().put(
                                "t0", frame.payload.optLong("t0", 0))));
                session.lastTxMs = System.currentTimeMillis();
                return;
            }

            if (Protocol.COMMAND.equals(frame.type)) {
                commandExecutor.execute(() -> handleCommand(session, frame));
            }
        } catch (Exception error) {
            sendProtocolError(session, frame, error);
        }
    }

    private void sendProtocolError(
            DeviceSession session,
            Frame frame,
            Exception error) {
        try {
            session.connection.send(new Frame(
                    Protocol.VERSION,
                    Protocol.ERROR,
                    session.nextSequence(),
                    System.currentTimeMillis(),
                    new JSONObject()
                            .put("requestId",
                                    frame.payload.optString(
                                            "requestId", ""))
                            .put("status", "error")
                            .put("code", "ROUTER_ERROR")
                            .put("message", error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage())));
            session.lastTxMs = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    private void handleCommand(
            DeviceSession session,
            Frame frame) {
        synchronized (session.commandLock) {
            try {
                Frame result = router.route(
                        frame,
                        session.address());
                session.connection.send(new Frame(
                        result.version,
                        result.type,
                        session.nextSequence(),
                        result.timestampMs,
                        result.payload));
                session.lastTxMs = System.currentTimeMillis();
            } catch (Exception error) {
                sendProtocolError(session, frame, error);
            }
        }
    }

    private void sendCapabilities(DeviceSession session) {
        try {
            JSONObject payload = new JSONObject()
                    .put("protocol", Protocol.VERSION)
                    .put("maxBluetoothPeers",
                            MultiDeviceManager.MAX_CLASSIC_PEERS)
                    .put("transports", new JSONArray()
                            .put("bluetooth-rfcomm")
                            .put("tcp-local")
                            .put("wifi-direct"))
                    .put("capabilities", new JSONArray()
                            .put("transport.ping")
                            .put("device.info")
                            .put("bulk.file-transfer"));

            JSONArray endpoints = new JSONArray();
            if (bulk != null) {
                for (TcpBulkEndpoint.Endpoint endpoint : bulk.endpoints()) {
                    endpoints.put(new JSONObject()
                            .put("host", endpoint.host)
                            .put("port", endpoint.port)
                            .put("token", endpoint.tokenBase64)
                            .put("transport", endpoint.transport));
                }
            }
            payload.put("bulkEndpoints", endpoints);

            session.connection.send(new Frame(
                    Protocol.VERSION,
                    Protocol.CAPABILITIES,
                    session.nextSequence(),
                    System.currentTimeMillis(),
                    payload));
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        NotificationManager nm =
                getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL,
                "Blutooth connection",
                NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification() {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Blutooth Connector")
                .setContentText(
                        "Multi-device connection service is active")
                .setSmallIcon(
                        android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    @Override public int onStartCommand(
            Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        capabilityRefresh.shutdownNow();
        commandExecutor.shutdownNow();
        if (wifiDirect != null) wifiDirect.close();
        if (peers != null) peers.close();
        if (bulk != null) bulk.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }
}
