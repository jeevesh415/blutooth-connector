package com.jeevesh415.blutoothconnector;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 100;

    private final AtomicLong sequence = new AtomicLong();
    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();

    private TextView status;
    private MultiDeviceManager peers;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();

        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQUEST_BLUETOOTH);
        } else {
            initPeerManager();
        }
    }

    private void initPeerManager() {
        if (peers != null) return;

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("Bluetooth is unavailable.");
            return;
        }

        peers = new MultiDeviceManager(adapter, new MultiDeviceManager.Listener() {
            @Override public void onConnected(DeviceSession session) {
                runOnUiThread(() -> updateStatus(
                        "Connected peers: " + peers.sessions().size()
                                + "\n" + safeName(session.device)
                                + "\nRTT EWMA: " + format(session.metrics.ewmaMs()) + " ms"
                                + "\np95: " + session.metrics.p95Ms() + " ms"));
            }

            @Override public void onFrame(DeviceSession session, Frame frame) {
                runOnUiThread(() -> updateStatus(
                        "Peers: " + peers.sessions().size()
                                + "\nLast: " + safeName(session.device)
                                + " -> " + frame.type
                                + "\nRTT EWMA: " + format(session.metrics.ewmaMs()) + " ms"
                                + "\np95: " + session.metrics.p95Ms() + " ms"));
            }

            @Override public void onDisconnected(DeviceSession session, Exception error) {
                runOnUiThread(() -> updateStatus(
                        "Reconnecting: " + safeName(session.device)
                                + "\nConnected peers: " + peers.sessions().size()));
            }

            @Override public void onConnectError(BluetoothDevice device, Exception error) {
                runOnUiThread(() -> updateStatus(
                        "Connect error: " + (device == null ? "unknown" : safeName(device))
                                + "\n" + error.getMessage()));
            }
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Blutooth Connector");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("Multi-device controller");
        status.setTextSize(16);
        status.setPadding(0, 32, 0, 32);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button inspect = new Button(this);
        inspect.setText("Scan paired devices");
        inspect.setOnClickListener(v -> inspectBluetooth());
        root.addView(inspect, new LinearLayout.LayoutParams(-1, -2));

        Button receiver = new Button(this);
        receiver.setText("Start receiver service");
        receiver.setOnClickListener(v -> startReceiver());
        root.addView(receiver, new LinearLayout.LayoutParams(-1, -2));

        Button connectAll = new Button(this);
        connectAll.setText("Connect all paired devices");
        connectAll.setOnClickListener(v -> connectAll());
        root.addView(connectAll, new LinearLayout.LayoutParams(-1, -2));

        Button pingAll = new Button(this);
        pingAll.setText("PING all connected devices");
        pingAll.setOnClickListener(v -> pingAll());
        root.addView(pingAll, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void inspectBluetooth() {
        if (!hasBluetoothPermission()) {
            status.setText("Bluetooth permission is required.");
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("Bluetooth is unavailable.");
            return;
        }

        if (!adapter.isEnabled()) {
            status.setText("Bluetooth is disabled. Enable it and retry.");
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        initPeerManager();

        devices.clear();
        devices.addAll(adapter.getBondedDevices());

        StringBuilder out = new StringBuilder(
                "Paired devices: " + devices.size() + "\n");
        for (int i = 0; i < devices.size(); i++) {
            out.append(i).append(": ")
                    .append(safeName(devices.get(i))).append("\n");
        }
        status.setText(out);
    }

    private void startReceiver() {
        if (!hasBluetoothPermission()) {
            status.setText("Bluetooth permission is required.");
            return;
        }
        Intent intent = new Intent(this, ConnectionService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        updateStatus("Receiver service started.");
    }

    private void connectAll() {
        if (!hasBluetoothPermission()) {
            status.setText("Bluetooth permission is required.");
            return;
        }
        if (devices.isEmpty()) inspectBluetooth();
        if (devices.isEmpty()) {
            status.setText("No paired devices.");
            return;
        }

        initPeerManager();
        try {
            peers.connectAll(devices);
            updateStatus("Connection attempts started for up to "
                    + MultiDeviceManager.MAX_CLASSIC_PEERS + " peers.");
        } catch (Exception e) {
            status.setText("Could not start multi-device connection: " + e.getMessage());
        }
    }

    private void pingAll() {
        if (peers == null || peers.sessions().isEmpty()) {
            status.setText("No connected peers.");
            return;
        }

        long now = System.nanoTime();
        try {
            peers.broadcast(new Frame(
                    Protocol.VERSION,
                    Protocol.PING,
                    sequence.incrementAndGet(),
                    System.currentTimeMillis(),
                    new JSONObject().put("t0", now)));
            updateStatus("PING broadcast to " + peers.sessions().size() + " peers.");
        } catch (Exception e) {
            status.setText("Broadcast failed: " + e.getMessage());
        }
    }

    private void updateStatus(String value) {
        if (status != null) status.setText(value);
    }

    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "unknown" : name;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "-" : String.format(java.util.Locale.US, "%.1f", value);
    }

    @Override protected void onDestroy() {
        if (peers != null) peers.close();
        super.onDestroy();
    }
}
