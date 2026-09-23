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
import com.jeevesh415.blutoothconnector.transport.BluetoothTransport;
import com.jeevesh415.blutoothconnector.transport.FramedConnection;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 100;
    private final AtomicLong sequence = new AtomicLong();
    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private TextView status;
    private BluetoothTransport transport;
    private FramedConnection connection;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        requestBluetoothPermissions();
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
        status.setText("Bluetooth control substrate");
        status.setTextSize(16);
        status.setPadding(0, 32, 0, 32);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button inspect = new Button(this);
        inspect.setText("Inspect paired devices");
        inspect.setOnClickListener(v -> inspectBluetooth());
        root.addView(inspect, new LinearLayout.LayoutParams(-1, -2));

        Button listen = new Button(this);
        listen.setText("Start receiver");
        listen.setOnClickListener(v -> startReceiver());
        root.addView(listen, new LinearLayout.LayoutParams(-1, -2));

        Button connect = new Button(this);
        connect.setText("Connect to first paired device");
        connect.setOnClickListener(v -> connectFirst());
        root.addView(connect, new LinearLayout.LayoutParams(-1, -2));

        Button ping = new Button(this);
        ping.setText("PING remote phone");
        ping.setOnClickListener(v -> sendPing());
        root.addView(ping, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            }, REQUEST_BLUETOOTH);
        } else inspectBluetooth();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void inspectBluetooth() {
        if (!hasConnectPermission()) {
            status.setText("Bluetooth permission is required.");
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            status.setText("Bluetooth is unavailable.");
            return;
        }

        if (!adapter.isEnabled()) {
            status.setText("Bluetooth is disabled. Enable it and try again.");
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        devices.clear();
        devices.addAll(adapter.getBondedDevices());

        StringBuilder out = new StringBuilder("Bluetooth ready.\n\nPaired devices:\n");
        if (devices.isEmpty()) {
            out.append("None");
        } else {
            for (int i = 0; i < devices.size(); i++) {
                BluetoothDevice d = devices.get(i);
                out.append(i).append(": ").append(d.getName()).append("\n");
            }
        }
        status.setText(out);
    }

    private void startReceiver() {
        if (!hasConnectPermission()) {
            status.setText("Bluetooth permission is required.");
            return;
        }

        Intent intent = new Intent(this, ConnectionService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);

        status.setText("Receiver active. Pair this phone with the controller.");
    }

    private void connectFirst() {
        if (!hasConnectPermission()) {
            status.setText("Bluetooth permission is required.");
            return;
        }

        if (devices.isEmpty()) inspectBluetooth();
        if (devices.isEmpty()) {
            status.setText("No paired device. Pair the phones in Android Bluetooth settings first.");
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        transport = new BluetoothTransport(adapter, new BluetoothTransport.Listener() {
            @Override public void onConnected(android.bluetooth.BluetoothSocket socket) {
                try {
                    final FramedConnection[] holder = new FramedConnection[1];
                    holder[0] = new FramedConnection(
                            socket.getInputStream(), socket.getOutputStream(),
                            new FramedConnection.Listener() {
                                @Override public void onFrame(Frame frame) {
                                    runOnUiThread(() -> status.setText(
                                            "Remote: " + frame.type + " seq=" + frame.sequence));
                                }

                                @Override public void onClosed(Exception error) {
                                    runOnUiThread(() -> status.setText("Connection closed."));
                                }
                            });
                    connection = holder[0];
                    connection.startReader();

                    org.json.JSONObject hello = new org.json.JSONObject()
                            .put("role", "controller");

                    connection.send(new Frame(
                            Protocol.VERSION, Protocol.HELLO,
                            sequence.incrementAndGet(), System.currentTimeMillis(), hello));

                    runOnUiThread(() -> status.setText("Connected. Press PING."));
                } catch (Exception e) {
                    runOnUiThread(() ->
                            status.setText("Connection setup failed: " + e.getMessage()));
                }
            }

            @Override public void onError(Exception error) {
                runOnUiThread(() ->
                        status.setText("Bluetooth connection failed: " + error.getMessage()));
            }
        });

        transport.connect(devices.get(0));
        status.setText("Connecting...");
    }

    private void sendPing() {
        if (connection == null) {
            status.setText("Not connected.");
            return;
        }

        try {
            long now = System.nanoTime();
            org.json.JSONObject payload = new org.json.JSONObject().put("t0", now);
            connection.send(new Frame(
                    Protocol.VERSION, Protocol.PING,
                    sequence.incrementAndGet(), System.currentTimeMillis(), payload));
            status.setText("PING sent.");
        } catch (Exception e) {
            status.setText("PING failed: " + e.getMessage());
        }
    }

    @Override protected void onDestroy() {
        if (connection != null) try { connection.close(); } catch (Exception ignored) {}
        if (transport != null) transport.close();
        super.onDestroy();
    }
}
