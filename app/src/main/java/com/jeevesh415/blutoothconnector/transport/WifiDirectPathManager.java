package com.jeevesh415.blutoothconnector.transport;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Network;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.WifiP2pDnsSdServiceRequest;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional Wi-Fi Direct rendezvous and connection manager.
 *
 * It advertises/discovers a small Bonjour service and exposes the active
 * P2P Network after association. Bulk authorization remains BCL3.
 */
public final class WifiDirectPathManager implements AutoCloseable {
    public static final String SERVICE_INSTANCE = "BlutoothConnector";
    public static final String SERVICE_TYPE = "_bcl._tcp";

    public interface Listener {
        void onPeerDiscovered(WifiP2pDevice device);
        void onConnected(WifiP2pInfo info, Network network, String ipv4);
        void onDisconnected();
        void onError(Exception error);
    }

    private final Context context;
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final Listener listener;
    private final Map<String, WifiP2pDevice> peers = new HashMap<>();

    private WifiP2pDnsSdServiceInfo localService;
    private WifiP2pDnsSdServiceRequest serviceRequest;
    private BroadcastReceiver receiver;
    private volatile boolean started;
    private volatile boolean closed;
    private Network currentNetwork;
    private String currentPeerIpv4;

    @SuppressLint("MissingPermission")
    public WifiDirectPathManager(Context context, Listener listener) {
        if (context == null) throw new IllegalArgumentException("context");
        this.context = context.getApplicationContext();
        this.listener = listener;

        manager = (WifiP2pManager)
                this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("Wi-Fi Direct unavailable");
        }

        channel = manager.initialize(
                this.context,
                Looper.getMainLooper(),
                () -> notifyError(new IllegalStateException(
                        "Wi-Fi Direct framework channel lost")));
    }

    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (closed || started) return;

        installReceiver();

        Map<String, String> txt = new HashMap<>();
        txt.put("proto", "3");
        txt.put("service", "blutooth-connector");

        localService = WifiP2pDnsSdServiceInfo.newInstance(
                SERVICE_INSTANCE, SERVICE_TYPE, txt);
        manager.addLocalService(
                channel, localService, actionListener("addLocalService"));

        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(
                null, SERVICE_TYPE);
        manager.addServiceRequest(
                channel, serviceRequest, actionListener("addServiceRequest"));

        manager.setDnsSdResponseListeners(
                channel,
                (instanceName, registrationType, device) -> {
                    if (!SERVICE_INSTANCE.equals(instanceName)) return;
                    rememberPeer(device);
                },
                (fullDomainName, record, device) -> {
                    if (!"3".equals(record.get("proto"))) return;
                    rememberPeer(device);
                });

        manager.discoverServices(
                channel, actionListener("discoverServices"));
        manager.discoverPeers(
                channel, actionListener("discoverPeers"));

        started = true;
    }

    @SuppressLint("MissingPermission")
    public synchronized void refreshPeers() {
        if (!started || closed) return;
        manager.requestPeers(
                channel,
                (WifiP2pDeviceList list) -> {
                    synchronized (WifiDirectPathManager.this) {
                        peers.clear();
                        for (WifiP2pDevice device : list.getDeviceList()) {
                            peers.put(device.deviceAddress, device);
                        }
                    }
                    if (listener != null) {
                        for (WifiP2pDevice device : list.getDeviceList()) {
                            listener.onPeerDiscovered(device);
                        }
                    }
                });
    }

    @SuppressLint("MissingPermission")
    private void rememberPeer(WifiP2pDevice device) {
        if (device == null || device.deviceAddress == null) return;
        synchronized (this) {
            peers.put(device.deviceAddress, device);
        }
        if (listener != null) listener.onPeerDiscovered(device);
    }

    @SuppressLint("MissingPermission")
    public synchronized List<WifiP2pDevice> peers() {
        return Collections.unmodifiableList(
                new ArrayList<>(peers.values()));
    }

    @SuppressLint("MissingPermission")
    public synchronized void connect(String deviceAddress) {
        if (!started || closed) return;
        if (deviceAddress == null || deviceAddress.isEmpty()) {
            notifyError(new IllegalArgumentException(
                    "Wi-Fi Direct device address is required"));
            return;
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = deviceAddress;
        manager.connect(channel, config, actionListener("connect"));
    }

    public synchronized Network network() {
        return currentNetwork;
    }

    public synchronized String peerIpv4() {
        return currentPeerIpv4;
    }

    @SuppressLint("MissingPermission")
    private void installReceiver() {
        if (receiver != null) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    refreshPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    requestConnectionInfo();
                } else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        clearCurrentPath();
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                    receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestConnectionInfo() {
        if (!started || closed) return;

        manager.requestConnectionInfo(channel, (WifiP2pInfo info) -> {
            if (!info.groupFormed || info.groupOwnerAddress == null) {
                clearCurrentPath();
                return;
            }

            InetAddress owner = info.groupOwnerAddress;
            String ipv4 = owner instanceof Inet4Address
                    ? owner.getHostAddress() : null;
            Network selected = null;

            try {
                NetworkPathCatalog catalog =
                        new NetworkPathCatalog(context);
                for (NetworkPathCatalog.Path path : catalog.snapshot()) {
                    if ("wifi-direct".equals(path.kind)) {
                        selected = path.network;
                        break;
                    }
                }
            } catch (Exception ignored) {}

            synchronized (WifiDirectPathManager.this) {
                currentNetwork = selected;
                currentPeerIpv4 = ipv4;
            }

            if (listener != null) {
                listener.onConnected(info, selected, ipv4);
            }
        });
    }

    private void clearCurrentPath() {
        synchronized (this) {
            currentNetwork = null;
            currentPeerIpv4 = null;
        }
        if (listener != null) listener.onDisconnected();
    }

    private WifiP2pManager.ActionListener actionListener(
            final String operation) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {}

            @Override public void onFailure(int reason) {
                notifyError(new IllegalStateException(
                        operation + " failed, reason=" + reason));
            }
        };
    }

    private void notifyError(Exception error) {
        if (listener != null) listener.onError(error);
    }

    @SuppressLint("MissingPermission")
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        started = false;

        try {
            if (serviceRequest != null) {
                manager.removeServiceRequest(
                        channel, serviceRequest, null);
            }
            manager.clearServiceRequests(channel, null);
            manager.clearLocalServices(channel, null);
        } catch (Exception ignored) {}

        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {}
            receiver = null;
        }

        peers.clear();
        currentNetwork = null;
        currentPeerIpv4 = null;
    }
}
