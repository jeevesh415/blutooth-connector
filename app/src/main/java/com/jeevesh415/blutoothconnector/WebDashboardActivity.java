package com.jeevesh415.blutoothconnector;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;

import org.json.JSONArray;
import org.json.JSONObject;

public final class WebDashboardActivity extends Activity {
    private WebView webView;
    private ConnectionService service;
    private MultiDeviceManager peers;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            ConnectionService.LocalBinder local = (ConnectionService.LocalBinder) binder;
            service = local.service();
            peers = service == null ? null : service.peers();
            bound = service != null && peers != null;
            pushStatus();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            peers = null;
            pushStatus();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(webView);

        Intent intent = new Intent(this, ConnectionService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            bindService(intent, connection, BIND_AUTO_CREATE);
        } catch (Exception ignored) {
            pushStatus();
        }
        webView.loadUrl("file:///android_asset/web/index.html");
    }

    private void pushStatus() {
        if (webView == null) return;
        final String status = statusText().replace("\\", "\\\\").replace("'", "\\'");
        webView.post(() -> webView.evaluateJavascript(
                "setStatus('" + status + "')", null));
    }

    private String statusText() {
        if (!bound || peers == null) return "Connection service unavailable.";
        StringBuilder out = new StringBuilder();
        out.append("Connected peers: ").append(peers.sessions().size()).append("\n");
        for (DeviceSession s : peers.sessions()) {
            out.append("- ").append(s.address())
                    .append("  RTT=").append(String.format(java.util.Locale.US, "%.1f", s.metrics.ewmaMs()))
                    .append(" ms\n");
        }
        return out.toString().trim();
    }

    private void ensureReady() {
        if (!bound) pushStatus();
    }

    public final class Bridge {
        @android.webkit.JavascriptInterface
        public String status() {
            return statusText();
        }

        @android.webkit.JavascriptInterface
        public String scan() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return "Bluetooth unavailable.";
            if (android.os.Build.VERSION.SDK_INT >= 31
                    && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return "Bluetooth permission is required. Open the native screen once to grant it.";
            }
            try {
                StringBuilder out = new StringBuilder("Paired devices: ");
                java.util.Set<BluetoothDevice> devices = adapter.getBondedDevices();
                out.append(devices.size());
                for (BluetoothDevice device : devices) {
                    out.append("\\n- ").append(device.getName()).append(" [")
                            .append(device.getAddress()).append("]");
                }
                return out.toString();
            } catch (Exception e) {
                return "Bluetooth scan failed: " + e.getMessage();
            }
        }

        @android.webkit.JavascriptInterface
        public String connect() {
            ensureReady();
            if (peers == null) return "Connection service is not ready.";
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return "Bluetooth unavailable.";
            if (android.os.Build.VERSION.SDK_INT >= 31
                    && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return "Bluetooth permission is required.";
            }
            try {
                java.util.ArrayList<BluetoothDevice> devices =
                        new java.util.ArrayList<>(adapter.getBondedDevices());
                peers.connectAll(devices);
                return "Connection attempts started for " + devices.size() + " paired devices.";
            } catch (Exception e) {
                return "Connect failed: " + e.getMessage();
            }
        }

        @android.webkit.JavascriptInterface
        public String ping() {
            ensureReady();
            if (peers == null || peers.sessions().isEmpty()) return "No connected peers.";
            try {
                peers.broadcast(new com.jeevesh415.blutoothconnector.protocol.Frame(
                        com.jeevesh415.blutoothconnector.protocol.Protocol.VERSION,
                        com.jeevesh415.blutoothconnector.protocol.Protocol.PING,
                        0,
                        System.currentTimeMillis(),
                        new JSONObject().put("source", "web-dashboard")));
                return "PING sent to " + peers.sessions().size() + " peers.";
            } catch (Exception e) {
                return "PING failed: " + e.getMessage();
            }
        }

        @android.webkit.JavascriptInterface
        public String wifi() {
            if (service == null) return "Connection service is not ready.";
            service.startWifiDirect();
            return "Wi-Fi Direct discovery requested.";
        }

        @android.webkit.JavascriptInterface
        public String wifiPeer() {
            if (service == null || service.wifiDirect() == null) {
                return "Start Wi-Fi Direct discovery first.";
            }
            java.util.List<android.net.wifi.p2p.WifiP2pDevice> candidates =
                    service.wifiDirect().peers();
            if (candidates.isEmpty()) return "No discovered Wi-Fi Direct peers.";
            service.wifiDirect().connect(candidates.get(0).deviceAddress);
            return "Connecting to " + candidates.get(0).deviceName;
        }
    }

    @Override protected void onDestroy() {
        if (bound) {
            try { unbindService(connection); } catch (Exception ignored) {}
            bound = false;
        }
        if (webView != null) webView.destroy();
        webView = null;
        service = null;
        peers = null;
        super.onDestroy();
    }
}
