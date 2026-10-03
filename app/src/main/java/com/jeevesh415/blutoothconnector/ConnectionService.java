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
import com.jeevesh415.blutoothconnector.capability.CapabilityManifest;
import com.jeevesh415.blutoothconnector.capability.DeviceInfoCapability;
import com.jeevesh415.blutoothconnector.capability.DeviceStateCapability;
import com.jeevesh415.blutoothconnector.capability.RemoteControlCapability;
import com.jeevesh415.blutoothconnector.capability.AppControlCapability;
import com.jeevesh415.blutoothconnector.capability.DevicePolicyCapability;
import com.jeevesh415.blutoothconnector.capability.UiInspectCapability;
import com.jeevesh415.blutoothconnector.capability.SensorControlCapability;
import com.jeevesh415.blutoothconnector.protocol.CommandRouter;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.security.SessionAuthenticator;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.BulkTransferProtocol;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;
import com.jeevesh415.blutoothconnector.transport.TcpBulkEndpoint;
import com.jeevesh415.blutoothconnector.transport.BluetoothL2capBulkTransport;
import com.jeevesh415.blutoothconnector.transport.WifiDirectPathManager;
import com.jeevesh415.blutoothconnector.transport.WifiAwarePathManager;
import com.jeevesh415.blutoothconnector.control.BulkTransferAuthorization;
import com.jeevesh415.blutoothconnector.control.RemoteControlAuthorization;
import com.jeevesh415.blutoothconnector.control.RemoteInputAccessibilityService;
import com.jeevesh415.blutoothconnector.media.RtcPeerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executors;

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
    private BluetoothL2capBulkTransport bluetoothBulk;
    private WifiDirectPathManager wifiDirect;
    private WifiAwarePathManager wifiAware;
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

    public synchronized void startScreenShare(
            String peerAddress,
            int resultCode,
            Intent projectionData) {
        if (peers == null) {
            throw new IllegalStateException("Connection service not ready");
        }
        if (projectionData == null) {
            throw new IllegalArgumentException("projectionData");
        }

        DeviceSession target = peers.session(peerAddress);
        if (target == null) {
            throw new IllegalArgumentException("Peer is not connected");
        }
        if (!target.authenticated) {
            throw new SecurityException("Peer session is not authenticated");
        }

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

    public synchronized WifiAwarePathManager wifiAware() {
        return wifiAware;
    }

    public synchronized void startWifiAware() {
        if (wifiAware != null) return;
        ensureBulkEndpoint();

        if (bulk == null || bulk.port() <= 0) {
            throw new IllegalStateException("Bulk server is not available");
        }
        byte[] token = bulk.authorizationToken();
        if (token == null) {
            throw new IllegalStateException("Bulk authorization key is not available");
        }

        try {
            wifiAware = new WifiAwarePathManager(
                    this,
                    bulk.port(),
                    token,
                    new WifiAwarePathManager.Listener() {
                        @Override public void onPathAvailable(
                                android.net.Network network,
                                String localIpv6,
                                int peerPort) {
                            refreshCapabilities();
                        }

                        @Override public void onPathLost() {
                            refreshCapabilities();
                        }

                        @Override public void onError(Exception error) {}
                    });
            wifiAware.start();
        } catch (Exception error) {
            if (wifiAware != null) {
                try { wifiAware.close(); }
                catch (Exception ignored) {}
            }
            wifiAware = null;
            throw new IllegalStateException(
                    "Could not start Wi-Fi Aware transport", error);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
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

        registry.register(new DeviceInfoCapability());
        registry.register(new DeviceStateCapability(this));
        registry.register(new RemoteControlCapability(this));
        registry.register(new AppControlCapability(this));
        registry.register(new DevicePolicyCapability(this));
        registry.register(new UiInspectCapability());
        registry.register(new SensorControlCapability(this));

        BluetoothAdapter adapter =
                BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return;

        ensureBulkEndpoint();

        bluetoothBulk = new BluetoothL2capBulkTransport(adapter);
        bluetoothBulk.start(
                new File(getFilesDir(), "transfers"),
                new BluetoothL2capBulkTransport.Listener() {
                    @Override public void onTransferComplete(File file) {}

                    @Override public void onError(Exception error) {}
                });

        peers = new MultiDeviceManager(
                this,
                adapter,
                new MultiDeviceManager.Listener() {
                    @Override public void onConnected(
                            DeviceSession session) {
                        // HELLO carries the identity key and nonce. Capabilities
                        // are withheld until the signed handshake completes.
                    }

                    @Override public void onFrame(
                            DeviceSession session,
                            Frame frame) {
                        handleFrame(session, frame);
                    }

                    @Override public void onDisconnected(
                            DeviceSession session,
                            Exception error) {
                        if (rtc != null) {
                            rtc.stop(session.address());
                        }
                    }

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
        refreshCapabilities(false);
    }

    public void refreshCapabilitiesAndRotateBulkToken() {
        refreshCapabilities(true);
    }

    private void refreshCapabilities(boolean rotateBulkToken) {
        MultiDeviceManager manager = peers;
        if (manager == null) return;

        if (rotateBulkToken && bluetoothBulk != null) {
            bluetoothBulk.rotateAuthorizationToken();
        }

        for (DeviceSession session :
                manager.sessions()) {
            if (session.authenticated) {
                sendCapabilities(session);
            }
        }
    }

    private void handleFrame(
            DeviceSession session,
            Frame frame) {
        try {
            if (Protocol.HELLO.equals(frame.type)) {
                handleHello(session, frame);
                return;
            }

            if (Protocol.AUTH.equals(frame.type)) {
                handleAuth(session, frame);
                return;
            }

            if (Protocol.AUTH_OK.equals(frame.type)) {
                handleAuthOk(session, frame);
                return;
            }

            if (!session.authenticated) {
                throw new SecurityException("Session is not authenticated");
            }

            if (!session.replayGuard.accept(frame.sequence)) {
                throw new SecurityException("Replay or out-of-order frame rejected");
            }

            if (Protocol.CAPABILITIES.equals(frame.type)) {
                syncAwarePeerKey(session, frame);
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

    private void handleHello(
            DeviceSession session,
            Frame frame) throws Exception {
        String encodedKey =
                frame.payload.optString("identityKey", "");
        String encodedNonce =
                frame.payload.optString("authNonce", "");

        if (encodedKey.isEmpty()
                || encodedNonce.isEmpty()) {
            throw new SecurityException(
                    "Peer did not present application identity");
        }

        byte[] remoteKey =
                java.util.Base64.getDecoder().decode(encodedKey);
        byte[] remoteNonce =
                java.util.Base64.getDecoder().decode(encodedNonce);

        if (remoteKey.length == 0
                || remoteKey.length > SessionAuthenticator.MAX_PUBLIC_KEY_BYTES
                || remoteNonce.length != SessionAuthenticator.NONCE_BYTES) {
            throw new SecurityException("Invalid peer identity material");
        }

        if (session.remoteIdentityKey != null
                || session.remoteAuthNonce != null
                || session.localAuthSent) {
            if (!java.security.MessageDigest.isEqual(
                        session.remoteIdentityKey, remoteKey)
                    || !java.security.MessageDigest.isEqual(
                        session.remoteAuthNonce, remoteNonce)) {
                throw new SecurityException(
                        "Peer attempted to renegotiate an authenticated identity");
            }
            if (session.authenticated) {
                return;
            }
        }

        byte[] pinned =
                SessionAuthenticator.pinnedPeer(
                        this,
                        session.address());
        if (pinned != null && !java.security.MessageDigest.isEqual(
                pinned, remoteKey)) {
            throw new SecurityException(
                    "Peer application identity changed");
        }

        session.remoteIdentityKey = remoteKey;
        session.remoteAuthNonce = remoteNonce;
        sendAuth(session);
    }

    private void sendAuth(DeviceSession session) throws Exception {
        byte[] remoteKey = session.remoteIdentityKey;
        byte[] remoteNonce = session.remoteAuthNonce;
        if (remoteKey == null || remoteNonce == null) return;

        byte[] localKey = SessionAuthenticator.publicKey(this);
        byte[] transcript = SessionAuthenticator.transcript(
                localKey,
                session.localAuthNonce,
                remoteKey,
                remoteNonce);

        byte[] signature =
                SessionAuthenticator.sign(this, transcript);

        session.connection.send(
                new Frame(
                        Protocol.VERSION,
                        Protocol.AUTH,
                        session.nextSequence(),
                        System.currentTimeMillis(),
                        new JSONObject()
                                .put(
                                        "identityKey",
                                        java.util.Base64.getEncoder()
                                                .encodeToString(localKey))
                                .put(
                                        "signature",
                                        java.util.Base64.getEncoder()
                                                .encodeToString(signature))));
        session.localAuthSent = true;
        session.lastTxMs = System.currentTimeMillis();
        Arrays.fill(localKey, (byte) 0);
        Arrays.fill(transcript, (byte) 0);
        Arrays.fill(signature, (byte) 0);
    }

    private void handleAuth(
            DeviceSession session,
            Frame frame) throws Exception {
        if (session.remoteIdentityKey == null
                || session.remoteAuthNonce == null) {
            throw new SecurityException("AUTH before HELLO");
        }

        byte[] presentedKey =
                java.util.Base64.getDecoder().decode(
                        frame.payload.optString("identityKey", ""));
        byte[] signature =
                java.util.Base64.getDecoder().decode(
                        frame.payload.optString("signature", ""));

        if (!java.security.MessageDigest.isEqual(
                presentedKey, session.remoteIdentityKey)) {
            throw new SecurityException(
                    "AUTH identity does not match HELLO");
        }

        byte[] localKey = SessionAuthenticator.publicKey(this);
        byte[] transcript = SessionAuthenticator.transcript(
                localKey,
                session.localAuthNonce,
                session.remoteIdentityKey,
                session.remoteAuthNonce);

        if (!SessionAuthenticator.verify(
                presentedKey,
                transcript,
                signature)) {
            throw new SecurityException("Peer identity signature invalid");
        }

        SessionAuthenticator.pinPeer(
                this,
                session.address(),
                presentedKey);
        session.remoteAuthVerified = true;

        session.connection.send(
                new Frame(
                        Protocol.VERSION,
                        Protocol.AUTH_OK,
                        session.nextSequence(),
                        System.currentTimeMillis(),
                        new JSONObject()
                                .put(
                                        "fingerprint",
                                        SessionAuthenticator.publicKeyFingerprint(
                                                localKey))));
        session.lastTxMs = System.currentTimeMillis();
        maybeAuthenticate(session);

        Arrays.fill(localKey, (byte) 0);
        Arrays.fill(transcript, (byte) 0);
        Arrays.fill(signature, (byte) 0);
    }

    private void handleAuthOk(
            DeviceSession session,
            Frame frame) {
        if (!session.remoteAuthVerified) {
            // Receiving AUTH_OK before validating AUTH is not sufficient.
            throw new SecurityException("AUTH_OK before authenticated peer");
        }
        session.authOkReceived = true;
        maybeAuthenticate(session);
    }

    private void maybeAuthenticate(DeviceSession session) {
        if (!session.authenticated
                && session.localAuthSent
                && session.remoteAuthVerified
                && session.authOkReceived) {
            session.authenticated = true;
            sendCapabilities(session);
        }
    }

    private void syncAwarePeerKey(
            DeviceSession session,
            Frame frame) {
        if (wifiAware == null) return;
        JSONArray endpoints =
                frame.payload.optJSONArray("bulkEndpoints");
        if (endpoints == null) return;

        for (int i = 0; i < endpoints.length(); i++) {
            JSONObject item = endpoints.optJSONObject(i);
            if (item == null
                    || !"wifi-aware".equals(
                            item.optString("transport", ""))) {
                continue;
            }
            String encoded =
                    item.optString("token", "");
            if (encoded.isEmpty()) return;
            try {
                byte[] remoteToken =
                        BulkTransferProtocol.decodeToken(encoded);
                wifiAware.setPeerBulkToken(remoteToken);
                Arrays.fill(remoteToken, (byte) 0);
            } catch (Exception ignored) {}
            return;
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
                                session.address(),
                                this);
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
            if (wifiAware != null
                    && wifiAware.localIpv6() != null) {
                transports.put("wifi-aware");
            }

            JSONArray capabilities =
                    new JSONArray()
                            .put("transport.ping")
                            .put("device.info")
                            .put("device.state");

            boolean authorized =
                    RemoteControlAuthorization.isAuthorized(
                            this, session.address());
            boolean bulkAuthorized =
                    BulkTransferAuthorization.isAuthorized(
                            this, session.address());

            if (bulkAuthorized) {
                capabilities.put("bulk.file-transfer");
            }

            if (authorized) {
                capabilities.put("app.control");
                capabilities.put("device.policy");
                capabilities.put("sensor.control");
            }

            if (authorized
                    && RemoteInputAccessibilityService.instance() != null) {
                capabilities.put("remote.control");
                capabilities.put("ui.inspect");
            }

            JSONArray features =
                    new JSONArray()
                            .put(Protocol.FEATURE_CAPABILITY_MANIFEST_V1)
                            .put(Protocol.FEATURE_MULTIPATH_PATH_ID_V1)
                            .put(Protocol.FEATURE_RTC_CONTROL_V3);

            JSONArray capabilityManifest =
                    new JSONArray();
            for (CapabilityManifest.Entry entry :
                    CapabilityManifest.from(registry)) {
                capabilityManifest.put(
                        new JSONObject()
                                .put("id", entry.id)
                                .put("version", entry.version)
                                .put(
                                        "requiresExplicitAuthorization",
                                        entry.requiresExplicitAuthorization));
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
                                    "features",
                                    features)
                            .put(
                                    "capabilities",
                                    capabilities)
                            .put(
                                    "capabilityManifest",
                                    capabilityManifest);

            JSONArray endpoints =
                    new JSONArray();

            if (bulkAuthorized
                    && bluetoothBulk != null
                    && bluetoothBulk.available()) {
                byte[] bluetoothToken = bluetoothBulk.authorizationToken();
                if (bluetoothToken != null) {
                    endpoints.put(
                            new JSONObject()
                                    .put("host", "")
                                    .put("port", -1)
                                    .put("psm", bluetoothBulk.psm())
                                    .put("token",
                                            BulkTransferProtocol.encodeToken(
                                                    bluetoothToken))
                                    .put("transport",
                                            BluetoothL2capBulkTransport.TRANSPORT));
                    Arrays.fill(bluetoothToken, (byte) 0);
                }
            }

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

                if (wifiAware != null) {
                    String localIpv6 = wifiAware.localIpv6();
                    if (localIpv6 != null) {
                        endpoints.put(
                                new JSONObject()
                                        .put("host", localIpv6)
                                        .put("port", bulk.port())
                                        .put("token",
                                                BulkTransferProtocol.encodeToken(
                                                        bulk.authorizationToken()))
                                        .put("transport", "wifi-aware"));
                    }
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
        if (wifiAware != null) {
            wifiAware.close();
        }
        if (peers != null) {
            peers.close();
        }
        if (bulk != null) {
            bulk.close();
        }
        if (bluetoothBulk != null) {
            bluetoothBulk.close();
        }

        super.onDestroy();
    }

    @Override public IBinder onBind(
            Intent intent) {
        return binder;
    }
}
