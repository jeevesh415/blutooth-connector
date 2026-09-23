package com.jeevesh415.blutoothconnector.transport;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Snapshot of Android networks usable for path-specific socket binding.
 *
 * A Network is a routing object supplied by Android. Multiple Networks are
 * only useful as independent paths when the platform and hardware actually
 * expose independent connectivity.
 */
public final class NetworkPathCatalog {
    public static final class Path {
        public final Network network;
        public final String id;
        public final String kind;

        Path(Network network, String id, String kind) {
            this.network = network;
            this.id = id;
            this.kind = kind;
        }
    }

    private final ConnectivityManager connectivityManager;

    public NetworkPathCatalog(Context context) {
        if (context == null) throw new IllegalArgumentException("context");
        connectivityManager = (ConnectivityManager)
                context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            throw new IllegalStateException("ConnectivityManager unavailable");
        }
    }

    public List<Path> snapshot() {
        if (Build.VERSION.SDK_INT < 23) return Collections.emptyList();

        List<Path> result = new ArrayList<>();
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null) continue;
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;

            LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
            String interfaceName = linkProperties == null
                    ? ""
                    : linkProperties.getInterfaceName();
            String kind = classify(capabilities, interfaceName);
            if ("unknown".equals(kind)) continue;

            long handle = network.getNetworkHandle();
            result.add(new Path(network, kind + ":" + handle, kind));
        }
        return result;
    }

    private static String classify(NetworkCapabilities capabilities, String interfaceName) {
        String name = interfaceName == null
                ? ""
                : interfaceName.toLowerCase(Locale.US);

        if (name.startsWith("p2p")) return "wifi-direct";
        if (name.startsWith("aware") || name.startsWith("nan")) return "wifi-aware";
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
            return "wifi-aware";
        }
        if (name.startsWith("wlan")
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "wifi-lan";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "wifi-lan";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "ethernet";
        }
        return "unknown";
    }
}
