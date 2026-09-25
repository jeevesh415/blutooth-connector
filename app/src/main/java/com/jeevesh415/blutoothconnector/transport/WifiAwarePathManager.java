package com.jeevesh415.blutoothconnector.transport;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet6Address;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional Wi-Fi Aware transport path.
 *
 * Both devices advertise the BCL service. The publisher includes its TCP
 * bulk-server port in discovery information. The subscriber creates a secure
 * Aware Network request for that publisher. The publisher also requests an
 * Aware Network using its publish session so both peers obtain a usable
 * Network object.
 *
 * The existing BCL3 AES-GCM/token layer remains the application-level
 * authorization boundary. Wi-Fi Aware derives a symmetric PMK from both
 * peers' authenticated BCL3 bulk tokens; the raw tokens are not advertised
 * in Aware discovery metadata.
 */
public final class WifiAwarePathManager implements AutoCloseable {
    public static final String SERVICE_NAME = "bcl-connector";
    private static final byte[] SERVICE_INFO_PREFIX =
            "BCL-AWARE-1:".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public interface Listener {
        void onPathAvailable(Network network, String localIpv6, int peerPort);
        void onPathLost();
        void onError(Exception error);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger messageIds = new AtomicInteger();

    private final WifiAwareManager awareManager;
    private final ConnectivityManager connectivityManager;

    private WifiAwareSession awareSession;
    private PublishDiscoverySession publishSession;
    private SubscribeDiscoverySession subscribeSession;

    private PeerHandle peerHandle;
    private int peerPort = -1;
    private Network network;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final int bulkPort;
    private final byte[] localBulkToken;
    private volatile byte[] peerPmk;

    private volatile boolean started;
    private volatile boolean closed;

    @SuppressLint("MissingPermission")
    public WifiAwarePathManager(
            Context context,
            int bulkPort,
            byte[] localBulkToken,
            Listener listener) {
        if (context == null) throw new IllegalArgumentException("context");
        if (bulkPort <= 0 || bulkPort > 65535) {
            throw new IllegalArgumentException("bulkPort");
        }
        if (localBulkToken == null || localBulkToken.length != 32) {
            throw new IllegalArgumentException("Bulk token must be 32 bytes");
        }

        this.context = context.getApplicationContext();
        this.listener = listener;
        this.bulkPort = bulkPort;
        this.localBulkToken = localBulkToken.clone();

        awareManager = (WifiAwareManager)
                this.context.getSystemService(Context.WIFI_AWARE_SERVICE);
        connectivityManager = (ConnectivityManager)
                this.context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (awareManager == null || connectivityManager == null) {
            throw new IllegalStateException("Wi-Fi Aware networking unavailable");
        }
    }

    public static boolean isSupported(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 31) return false;
        return context.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
                && context.getSystemService(Context.WIFI_AWARE_SERVICE)
                        instanceof WifiAwareManager;
    }

    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (started || closed) return;
        if (!isSupported(context)) {
            notifyError(new UnsupportedOperationException(
                    "Secure Wi-Fi Aware transport requires Android 12+ and device support"));
            return;
        }
        if (!awareManager.isAvailable()) {
            notifyError(new IllegalStateException(
                    "Wi-Fi Aware is not currently available"));
            return;
        }

        started = true;
        awareManager.attach(new AttachCallback() {
            @Override public void onAttached(WifiAwareSession session) {
                synchronized (WifiAwarePathManager.this) {
                    if (closed) {
                        session.close();
                        return;
                    }
                    awareSession = session;
                }
                startDiscovery(session);
            }

            @Override public void onAttachFailed() {
                synchronized (WifiAwarePathManager.this) {
                    started = false;
                }
                notifyError(new IllegalStateException(
                        "Wi-Fi Aware attach failed"));
            }

            @Override public void onAwareSessionTerminated() {
                synchronized (WifiAwarePathManager.this) {
                    awareSession = null;
                    publishSession = null;
                    subscribeSession = null;
                }
                clearPath();
                if (!closed) {
                    notifyError(new IllegalStateException(
                            "Wi-Fi Aware session terminated"));
                }
            }
        }, handler);
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery(WifiAwareSession session) {
        byte[] info = serviceInfo();

        session.publish(
                new PublishConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .setPublishType(
                                PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                        .setServiceSpecificInfo(info)
                        .build(),
                new DiscoverySessionCallback() {
                    @Override public void onPublishStarted(
                            PublishDiscoverySession session) {
                        synchronized (WifiAwarePathManager.this) {
                            publishSession = session;
                        }
                        requestPublisherNetworkIfReady(session);
                    }

                    @Override public void onSessionConfigFailed() {
                        notifyError(new IllegalStateException(
                                "Wi-Fi Aware publish failed"));
                    }

                    @Override public void onSessionTerminated() {
                        synchronized (WifiAwarePathManager.this) {
                            publishSession = null;
                        }
                        clearPath();
                    }

                    @Override public void onServiceLost(
                            PeerHandle peerHandle, int reason) {
                        clearPath();
                    }
                },
                handler);

        session.subscribe(
                new SubscribeConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .setSubscribeType(
                                SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                        .build(),
                new DiscoverySessionCallback() {
                    @Override public void onSubscribeStarted(
                            SubscribeDiscoverySession session) {
                        synchronized (WifiAwarePathManager.this) {
                            subscribeSession = session;
                        }
                    }

                    @Override public void onServiceDiscovered(
                            PeerHandle handle,
                            byte[] serviceSpecificInfo,
                            java.util.List<byte[]> matchFilter) {
                        int remotePort =
                                parsePort(serviceSpecificInfo);
                        if (remotePort <= 0) return;

                        synchronized (WifiAwarePathManager.this) {
                            peerHandle = handle;
                            peerPort = remotePort;
                        }

                        SubscribeDiscoverySession discoverySession;
                        synchronized (WifiAwarePathManager.this) {
                            discoverySession = subscribeSession;
                        }
                        if (discoverySession != null) {
                            requestSubscriberNetworkIfReady(
                                    discoverySession,
                                    handle,
                                    remotePort);
                        }
                    }

                    @Override public void onServiceLost(
                            PeerHandle handle, int reason) {
                        synchronized (WifiAwarePathManager.this) {
                            if (peerHandle == handle
                                    || (peerHandle != null
                                    && peerHandle.equals(handle))) {
                                peerHandle = null;
                                peerPort = -1;
                            }
                        }
                        clearPath();
                    }

                    @Override public void onSessionConfigFailed() {
                        notifyError(new IllegalStateException(
                                "Wi-Fi Aware subscribe failed"));
                    }

                    @Override public void onSessionTerminated() {
                        synchronized (WifiAwarePathManager.this) {
                            subscribeSession = null;
                        }
                        clearPath();
                    }
                },
                handler);
    }

    @SuppressLint("MissingPermission")
    private void requestPublisherNetworkIfReady(
            PublishDiscoverySession session) {
        byte[] key = peerPmkSnapshot();
        if (Build.VERSION.SDK_INT < 31 || key == null) return;

        try {
            WifiAwareNetworkSpecifier specifier =
                    new WifiAwareNetworkSpecifier.Builder(session)
                            .setPmk(key)
                            .setPort(bulkPort)
                            .setTransportProtocol(6)
                            .build();

            requestNetwork(
                    new NetworkRequest.Builder()
                            .addTransportType(
                                    NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                            .setNetworkSpecifier(specifier)
                            .build());
        } catch (Exception error) {
            notifyError(error);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestSubscriberNetworkIfReady(
            SubscribeDiscoverySession session,
            PeerHandle peer,
            int remotePort) {
        byte[] key = peerPmkSnapshot();
        if (Build.VERSION.SDK_INT < 31 || key == null) return;

        try {
            WifiAwareNetworkSpecifier specifier =
                    new WifiAwareNetworkSpecifier.Builder(
                            session, peer)
                            .setPmk(key)
                            .build();

            requestNetwork(
                    new NetworkRequest.Builder()
                            .addTransportType(
                                    NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                            .setNetworkSpecifier(specifier)
                            .build());
        } catch (Exception error) {
            notifyError(error);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Called after the remote bulk token has arrived over the authenticated
     * Bluetooth channel. The PMK is derived symmetrically from both tokens,
     * so neither side has to expose its token over Wi-Fi Aware discovery.
     */
    public synchronized void setPeerBulkToken(byte[] remoteToken) {
        if (remoteToken == null || remoteToken.length != 32) {
            throw new IllegalArgumentException("Invalid peer bulk token");
        }
        byte[] derived = derivePmk(localBulkToken, remoteToken);
        if (peerPmk != null) Arrays.fill(peerPmk, (byte) 0);
        peerPmk = derived;

        if (publishSession != null && network == null) {
            requestPublisherNetworkIfReady(publishSession);
        }
        if (subscribeSession != null && peerHandle != null) {
            requestSubscriberNetworkIfReady(
                    subscribeSession,
                    peerHandle,
                    peerPort);
        }
    }

    static byte[] derivePmk(
            byte[] tokenA,
            byte[] tokenB) {
        if (tokenA == null || tokenA.length != 32
                || tokenB == null || tokenB.length != 32) {
            throw new IllegalArgumentException("Tokens must be 32 bytes");
        }
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            digest.update("BCL-AWARE-PMK-1".getBytes(StandardCharsets.US_ASCII));
            if (compare(tokenA, tokenB) <= 0) {
                digest.update(tokenA);
                digest.update(tokenB);
            } else {
                digest.update(tokenB);
                digest.update(tokenA);
            }
            return digest.digest();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static int compare(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) {
            int left = a[i] & 0xff;
            int right = b[i] & 0xff;
            if (left != right) return Integer.compare(left, right);
        }
        return 0;
    }

    private synchronized byte[] peerPmkSnapshot() {
        return peerPmk == null ? null : peerPmk.clone();
    }

    @SuppressLint("MissingPermission")
    private synchronized void requestNetwork(
            NetworkRequest request) {
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(
                        networkCallback);
            } catch (Exception ignored) {}
            networkCallback = null;
        }

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network candidate) {
                synchronized (WifiAwarePathManager.this) {
                    network = candidate;
                }
                publishLocalAddress(candidate);
            }

            @Override public void onLinkPropertiesChanged(
                    Network candidate,
                    LinkProperties properties) {
                if (candidate.equals(network)) {
                    publishLocalAddress(candidate, properties);
                }
            }

            @Override public void onLost(Network candidate) {
                synchronized (WifiAwarePathManager.this) {
                    if (candidate.equals(network)) {
                        network = null;
                    }
                }
                clearPath();
            }
        };

        try {
            connectivityManager.requestNetwork(
                    request,
                    networkCallback);
        } catch (Exception error) {
            networkCallback = null;
            notifyError(error);
        }
    }

    private void publishLocalAddress(Network candidate) {
        try {
            LinkProperties properties =
                    connectivityManager.getLinkProperties(candidate);
            publishLocalAddress(candidate, properties);
        } catch (Exception error) {
            notifyError(error);
        }
    }

    private void publishLocalAddress(
            Network candidate,
            LinkProperties properties) {
        if (properties == null) return;

        String local = null;
        for (LinkAddress linkAddress :
                properties.getLinkAddresses()) {
            if (linkAddress.getAddress() instanceof Inet6Address) {
                Inet6Address address =
                        (Inet6Address) linkAddress.getAddress();
                if (!address.isLoopbackAddress()
                        && !address.isMulticastAddress()) {
                    local = address.getHostAddress();
                    break;
                }
            }
        }

        if (local != null && listener != null) {
            listener.onPathAvailable(
                    candidate,
                    local,
                    peerPort > 0 ? peerPort : bulkPort);
        }
    }

    private byte[] serviceInfo() {
        byte[] portBytes = Integer.toString(bulkPort)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] output =
                new byte[SERVICE_INFO_PREFIX.length + portBytes.length];
        System.arraycopy(
                SERVICE_INFO_PREFIX, 0,
                output, 0,
                SERVICE_INFO_PREFIX.length);
        System.arraycopy(
                portBytes, 0,
                output, SERVICE_INFO_PREFIX.length,
                portBytes.length);
        return output;
    }

    static int parsePortForTest(byte[] info) {
        return parsePort(info);
    }

    static int parsePort(byte[] info) {
        if (info == null
                || info.length <= SERVICE_INFO_PREFIX.length) {
            return -1;
        }

        for (int i = 0; i < SERVICE_INFO_PREFIX.length; i++) {
            if (info[i] != SERVICE_INFO_PREFIX[i]) return -1;
        }

        String value =
                new String(
                        info,
                        SERVICE_INFO_PREFIX.length,
                        info.length - SERVICE_INFO_PREFIX.length,
                        java.nio.charset.StandardCharsets.US_ASCII);
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private void clearPath() {
        Network previous;
        synchronized (this) {
            previous = network;
            network = null;
        }
        if (listener != null && previous != null) {
            listener.onPathLost();
        }
    }

    public synchronized Network network() {
        return network;
    }

    public synchronized String localIpv6() {
        if (network == null) return null;
        try {
            LinkProperties lp =
                    connectivityManager.getLinkProperties(network);
            if (lp == null) return null;
            for (LinkAddress link :
                    lp.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet6Address
                        && !link.getAddress().isLoopbackAddress()
                        && !link.getAddress().isMulticastAddress()) {
                    return link.getAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public synchronized int peerPort() {
        return peerPort;
    }

    public synchronized String localPort() {
        return Integer.toString(bulkPort);
    }

    private void notifyError(Exception error) {
        if (listener != null) listener.onError(error);
    }

    @SuppressLint("MissingPermission")
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        started = false;

        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(
                        networkCallback);
            } catch (Exception ignored) {}
            networkCallback = null;
        }

        clearPath();

        if (publishSession != null) {
            try { publishSession.close(); }
            catch (Exception ignored) {}
            publishSession = null;
        }
        if (subscribeSession != null) {
            try { subscribeSession.close(); }
            catch (Exception ignored) {}
            subscribeSession = null;
        }
        if (awareSession != null) {
            try { awareSession.close(); }
            catch (Exception ignored) {}
            awareSession = null;
        }

        peerHandle = null;
        peerPort = -1;
        Arrays.fill(localBulkToken, (byte) 0);
        if (peerPmk != null) Arrays.fill(peerPmk, (byte) 0);
        peerPmk = null;
    }
}
