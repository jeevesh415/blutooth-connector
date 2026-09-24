package com.jeevesh415.blutoothconnector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
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
import com.jeevesh415.blutoothconnector.control.RemoteControlAuthorization;
import com.jeevesh415.blutoothconnector.control.RemoteInputAccessibilityService;
import com.jeevesh415.blutoothconnector.media.RtcPeerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class ConnectionService extends Service {
    private static final String CHANNEL = "connection";
    private static final int NOTIFICATION_ID = 7;

    private final IBinder binder =
            new LocalBinder();
    private final CapabilityRegistry registry =
            new CapabilityRegistry();
    private final CommandRouter router =
            new CommandRouter(registry);

    private MultiDeviceManager peers;
    private TcpBulkEndpoint bulk;
    private WifiDirectPathManager wifiDirect;
    private RtcPeerManager rtc;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final java.util.concurrent.ExecutorService
            commandExecutor =
            Executors.newFixedThreadPool(4);

    public final class LocalBinder extends Binder {
        public ConnectionService service() {
            return ConnectionService.this;
        }
    }

    public MultiDeviceManager peers() {
        return peers;
    }

    public synchronized RtcPeerManager rtc() {
        if (rtc == null) {
            rtc = new RtcPeerManager(
                    this,
                    new RtcPeerManager.Listener() {
                        @Override public void onRemoteVideo(
                                String peerAddress,
                                org.webrtc.VideoTrack track) {}

                        @Override public void onState(
                                String peerAddress,
                                String state) {}

                        @Override public void onError(
                                String peerAddress,
                                Exception error) {}
                    });
        }
        return rtc;
    }

    /**
     * Starts a user-consented screen + microphone publisher toward one
     * already authenticated Bluetooth peer. The Bluetooth channel carries
     * only SDP/ICE; audio/video stay on WebRTC SRTP.
     */
    public synchronized void startScreenShare(
            String peerAddress,
            int resultCode,
            Intent projectionData) {
        if (peers == null) throw new IllegalStateException("Connection service not ready");
        if (projectionData == null) throw new IllegalArgumentException("projectionData");

        DeviceSession target = null;
        for (DeviceSession candidate : peers.sessions()) {
            if (candidate.address().equals(peerAddress)) {
                target = candidate;
                break;
            }
        }
        if (target == null) throw new IllegalArgumentException("Peer is not connected");

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        }

        rtc().startPublisher(target, projectionData, 1280, 720, 30);
    }

    public synchronized void startWifiDirect() {
        if (wifiDirect != null) return;

        try {
            wifiDirect =
                    new WifiDirectPathManager(
                            this,
                            new WifiDirectPathManager.Listener() {
                                @Override public void onPeerDiscovered(
                                        android.net.wifi.p2p.WifiP2pDevice device) {}

                                @Override public void onConnected(
                                        android.net.wifi.p2p.WifiP2pInfo info,
                                        android.net.Network network,
                                        String ipv4) {
                                    refreshCapabilities();
                                }

                                @Override public void onDisconnected() {
                                    refreshCapabilities();
                                }

                                @Override public void onError(
                                        Exception error) {}
                            });
            wifiDirect.start();
        } catch (Exception error) {
            if (wifiDirect != null) {
                try { wifiDirect.close(); }
                catch (Exception ignored) {}
            }
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
            startForeground(
                    NOTIFICATION_ID,
                    notification());
        }

        registry.register(
                new DeviceInfoCapability());

        BluetoothAdapter adapter =
                BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        ensureBulkEndpoint();

        peers = new MultiDeviceManager(
                this,
                adapter,
                new MultiDeviceManager.Listener() {
                    @Override public void onConnected(
                            DeviceSession session) {
                        sendCapabilities(session);
                    }

                    @Override public void onFrame(
                            DeviceSession session,
                            Frame frame) {
                        handleFrame(session, frame);
                    }

                    @Override public void onDisconnected(
                            DeviceSession session,
                            Exception error) {}

                    @Override public void onConnectError(
                            BluetoothDevice device,
                            Exception error) {}
                });

        try {
            peers.startReceiver();
        } catch (Exception ignored) {}

        registerNetworkTopologyMonitor();
    }

    @SuppressWarnings("MissingPermission")
    private synchronized void registerNetworkTopologyMonitor() {
        if (networkCallback != null) return;

        connectivityManager =
                (ConnectivityManager) getSystemService(
                        ConnectivityManager.class);
        if (connectivityManager == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                refreshCapabilities();
            }

            @Override public void onLost(Network network) {
                refreshCapabilities();
            }

            @Override public void onLinkPropertiesChanged(
                    Network network,
                    android.net.LinkProperties properties) {
                refreshCapabilities();
            }
        };

        try {
            connectivityManager.registerNetworkCallback(
                    new NetworkRequest.Builder().build(),
                    networkCallback);
        } catch (Exception error) {
            networkCallback = null;
        }
    }

    private synchronized void unregisterNetworkTopologyMonitor() {
        if (connectivityManager == null || networkCallback == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(
                    networkCallback);
        } catch (Exception ignored) {}
        networkCallback = null;
        connectivityManager = null;
    }

    public synchronized void ensureBulkEndpoint() {
        if (bulk != null) return;

        TcpBulkEndpoint candidate =
                new TcpBulkEndpoint();

        try {
            candidate.start(
                    new File(
                            getFilesDir(),
                            "transfers"),
                    new TcpBulkEndpoint.Listener() {
                        @Override public void onTransferComplete(
                                File file) {}

                        @Override public void onError(
                                Exception error) {}
                    });

            bulk = candidate;
            refreshCapabilities();
        } catch (Exception error) {
            try { candidate.close(); }
            catch (Exception ignored) {}
            bulk = null;
        }
    }

    public void refreshCapabilities() {
        MultiDeviceManager manager = peers;
        if (manager == null) return;

        for (DeviceSession session :
                manager.sessions()) {
            sendCapabilities(session);
        }
    }

    private void handleFrame(
            DeviceSession session,
            Frame frame) {
        try {
            if (Protocol.HELLO.equals(frame.type)) {
                sendCapabilities(session);
                return;
            }

            if (Protocol.PING.equals(frame.type)) {
                session.connection.send(
                        new Frame(
                                Protocol.VERSION,
                                Protocol.PONG,
                                session.nextSequence(),
                                System.currentTimeMillis(),
                                new JSONObject().put(
                                        "t0",
                                        frame.payload.optLong(
                                                "t0",
                                                0))));
                session.lastTxMs =
                        System.currentTimeMillis();
                return;
            }

            if (Protocol.RTC_OFFER.equals(frame.type)
                    || Protocol.RTC_ANSWER.equals(frame.type)
                    || Protocol.RTC_ICE.equals(frame.type)
                    || Protocol.RTC_CONTROL.equals(frame.type)
                    || Protocol.RTC_STOP.equals(frame.type)) {
                rtc().handle(session, frame);
                return;
            }

            if (Protocol.COMMAND.equals(frame.type)) {
                commandExecutor.execute(
                        () -> handleCommand(
                                session, frame));
            }
        } catch (Exception error) {
            sendProtocolError(
                    session, frame, error);
        }
    }

    private void sendProtocolError(
            DeviceSession session,
            Frame frame,
            Exception error) {
        try {
            session.connection.send(
                    new Frame(
                            Protocol.VERSION,
                            Protocol.ERROR,
                            session.nextSequence(),
                            System.currentTimeMillis(),
                            new JSONObject()
                                    .put(
                                            "requestId",
                                            frame.payload.optString(
                                                    "requestId",
                                                    ""))
                                    .put(
                                            "status",
                                            "error")
                                    .put(
                                            "code",
                                            "ROUTER_ERROR")
                                    .put(
                                            "message",
                                            error.getMessage() == null
                                                    ? error.getClass()
                                                            .getSimpleName()
                                                    : error.getMessage())));
            session.lastTxMs =
                    System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    private void handleCommand(
            DeviceSession session,
            Frame frame) {
        synchronized (session.commandLock) {
            try {
                Frame result =
                        router.route(
                                frame,
                                session.address());
                session.connection.send(
                        new Frame(
                                result.version,
                                result.type,
                                session.nextSequence(),
                                result.timestampMs,
                                result.payload));
                session.lastTxMs =
                        System.currentTimeMillis();
            } catch (Exception error) {
                sendProtocolError(
                        session,
                        frame,
                        error);
            }
        }
    }

    private void sendCapabilities(
            DeviceSession session) {
        try {
            JSONArray transports =
                    new JSONArray()
                            .put("bluetooth-rfcomm")
                            .put("tcp-local");
            if (wifiDirect != null) {
                transports.put("wifi-direct");
            }

            JSONArray capabilities = new JSONArray()
                    .put("transport.ping")
                    .put("device.info")
                    .put("bulk.file-transfer");
            if (RemoteInputAccessibilityService.instance() != null
                    && RemoteControlAuthorization.isAuthorized(
                            this, session.address())) {
                capabilities.put("remote.control");
            }

            JSONObject payload =
                    new JSONObject()
                            .put(
                                    "protocol",
                                    Protocol.VERSION)
                            .put(
                                    "maxBluetoothPeers",
                                    MultiDeviceManager.MAX_CLASSIC_PEERS)
                            .put(
                                    "transports",
                                    transports)
                            .put(
                                    "capabilities",
                                    capabilities);

            JSONArray endpoints =
                    new JSONArray();
            if (bulk != null) {
                for (TcpBulkEndpoint.Endpoint endpoint :
                        bulk.endpoints()) {
                    endpoints.put(
                            new JSONObject()
                                    .put(
                                            "host",
                                            endpoint.host)
                                    .put(
                                            "port",
                                            endpoint.port)
                                    .put(
                                            "token",
                                            endpoint.tokenBase64)
                                    .put(
                                            "transport",
                                            endpoint.transport));
                }
            }

            payload.put(
                    "bulkEndpoints",
                    endpoints);

            session.connection.send(
                    new Frame(
                            Protocol.VERSION,
                            Protocol.CAPABILITIES,
                            session.nextSequence(),
                            System.currentTimeMillis(),
                            payload));
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        NotificationManager nm =
                getSystemService(
                        NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL,
                        "Blutooth connection",
                        NotificationManager.IMPORTANCE_LOW));
    }

    private Notification notification() {
        return new Notification.Builder(
                this,
                CHANNEL)
                .setContentTitle(
                        "Blutooth Connector")
                .setContentText(
                        "Multi-device connection service is active")
                .setSmallIcon(
                        android.R.drawable
                                .stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    @Override public int onStartCommand(
            Intent intent,
            int flags,
            int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        commandExecutor.shutdownNow();
        if (rtc != null) rtc.close();
        unregisterNetworkTopologyMonitor();

        if (wifiDirect != null) {
            wifiDirect.close();
        }
        if (peers != null) {
            peers.close();
        }
        if (bulk != null) {
            bulk.close();
        }

        super.onDestroy();
    }

    @Override public IBinder onBind(
            Intent intent) {
        return binder;
    }
}
