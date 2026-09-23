package com.jeevesh415.blutoothconnector;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.protocol.ReliableCommandClient;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_FILE = 200;

    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private final Map<String, ReliableCommandClient> commandClients =
            new ConcurrentHashMap<>();

    private TextView status;
    private ConnectionService service;
    private MultiDeviceManager peers;
    private boolean bound;

    private final MultiDeviceManager.Listener uiListener = new MultiDeviceManager.Listener() {
        @Override public void onConnected(DeviceSession session) {
            ReliableCommandClient old =
                    commandClients.remove(session.address());
            if (old != null) old.close();
            attachCommandClient(session);
            runOnUiThread(() -> updateStatus(
                    "Connected peers: " + peerCount()
                            + "\n" + safeName(session.device)
                            + "\nRTT EWMA: " + format(session.metrics.ewmaMs()) + " ms"
                            + "\np95: " + session.metrics.p95Ms() + " ms"));
        }

        @Override public void onFrame(DeviceSession session, Frame frame) {
            ReliableCommandClient client = commandClients.get(session.address());
            if (client != null) client.accept(frame);
            runOnUiThread(() -> updateStatus(
                    "Peers: " + peerCount()
                            + "\nLast: " + safeName(session.device)
                            + " -> " + frame.type
                            + "\nRTT EWMA: " + format(session.metrics.ewmaMs()) + " ms"
                            + "\np95: " + session.metrics.p95Ms() + " ms"));
        }

        @Override public void onDisconnected(DeviceSession session, Exception error) {
            ReliableCommandClient old = commandClients.remove(session.address());
            if (old != null) old.close();
            runOnUiThread(() -> updateStatus(
                    "Reconnecting: " + safeName(session.device)
                            + "\nConnected peers: " + peerCount()));
        }

        @Override public void onConnectError(BluetoothDevice device, Exception error) {
            runOnUiThread(() -> updateStatus(
                    "Connect error: " + safeName(device)
                            + "\n" + safeError(error)));
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            ConnectionService.LocalBinder local =
                    (ConnectionService.LocalBinder) binder;
            service = local.service();
            peers = service.peers();
            bound = peers != null;
            if (peers != null) {
                peers.addListener(uiListener);
                for (DeviceSession session : peers.sessions()) {
                    attachCommandClient(session);
                }
            }
            updateStatus(bound
                    ? "Connection service ready."
                    : "Connection service is starting.");
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            peers = null;
            closeCommandClients();
            updateStatus("Connection service disconnected.");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        requestRequiredPermissions();
    }

    private void requestRequiredPermissions() {
        ArrayList<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else if (Build.VERSION.SDK_INT >= 26) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= 37) {
            permissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK);
        }

        ArrayList<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }

        if (missing.isEmpty()) {
            ensureConnectionService();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            ensureConnectionService();
            status.setText("Permissions granted. Connection service starting.");
        } else {
            status.setText("Required nearby-device/network permissions were not granted.");
        }
    }

    private void ensureConnectionService() {
        Intent intent = new Intent(this, ConnectionService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        } catch (Exception error) {
            status.setText("Could not start connection service: " + safeError(error));
        }
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
        receiver.setText("Keep receiver service active");
        receiver.setOnClickListener(v -> ensureConnectionService());
        root.addView(receiver, new LinearLayout.LayoutParams(-1, -2));

        Button connectAll = new Button(this);
        connectAll.setText("Connect all paired devices");
        connectAll.setOnClickListener(v -> connectAll());
        root.addView(connectAll, new LinearLayout.LayoutParams(-1, -2));

        Button pingAll = new Button(this);
        pingAll.setText("PING all connected devices");
        pingAll.setOnClickListener(v -> pingAll());
        root.addView(pingAll, new LinearLayout.LayoutParams(-1, -2));

        Button infoAll = new Button(this);
        infoAll.setText("Query all device info");
        infoAll.setOnClickListener(v -> queryAllDeviceInfo());
        root.addView(infoAll, new LinearLayout.LayoutParams(-1, -2));

        Button sendFile = new Button(this);
        sendFile.setText("Send file to all connected devices");
        sendFile.setOnClickListener(v -> chooseFile());
        root.addView(sendFile, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocalNetworkPermission() {
        return Build.VERSION.SDK_INT < 37
                || checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
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

        devices.clear();
        try {
            devices.addAll(adapter.getBondedDevices());
        } catch (Exception error) {
            status.setText("Could not read paired devices: " + safeError(error));
            return;
        }

        StringBuilder out = new StringBuilder("Paired devices: ")
                .append(devices.size()).append("\n");
        for (int i = 0; i < devices.size(); i++) {
            out.append(i).append(": ")
                    .append(safeName(devices.get(i))).append("\n");
        }
        status.setText(out);
        if (!bound) ensureConnectionService();
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
        if (peers == null) {
            status.setText("Connection service is still starting.");
            return;
        }

        try {
            peers.connectAll(devices);
            updateStatus("Connection attempts started for up to "
                    + MultiDeviceManager.MAX_CLASSIC_PEERS + " peers.");
        } catch (Exception e) {
            status.setText("Could not start multi-device connection: " + safeError(e));
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
                    0,
                    System.currentTimeMillis(),
                    new JSONObject().put("t0", now)));
            updateStatus("PING broadcast to " + peers.sessions().size() + " peers.");
        } catch (Exception e) {
            status.setText("Broadcast failed: " + safeError(e));
        }
    }

    private void queryAllDeviceInfo() {
        if (peers == null || peers.sessions().isEmpty()) {
            status.setText("No connected peers.");
            return;
        }

        for (DeviceSession session : peers.sessions()) {
            ReliableCommandClient client = commandClients.get(session.address());
            if (client == null) {
                attachCommandClient(session);
                client = commandClients.get(session.address());
            }
            if (client == null) continue;

            client.execute(
                    session.nextSequence(),
                    "device.info",
                    "get",
                    null
            ).whenComplete((frame, error) -> runOnUiThread(() -> {
                if (error != null) {
                    updateStatus("Device info failed for " + safeName(session.device)
                            + ": " + safeError(error));
                } else {
                    updateStatus("Device info from " + safeName(session.device)
                            + ": " + frame.payload.toString());
                }
            }));
        }
    }

    private void chooseFile() {
        if (!hasLocalNetworkPermission()) {
            status.setText("Local network permission is required for bulk transfer.");
            return;
        }
        if (peers == null || peers.sessions().isEmpty()) {
            status.setText("Connect at least one peer first.");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        try {
            File staged = stageUri(uri);
            sendStagedFile(staged);
        } catch (Exception error) {
            status.setText("Could not stage file: " + safeError(error));
        }
    }

    private File stageUri(Uri uri) throws Exception {
        String name = "upload-" + System.currentTimeMillis() + ".bin";
        Cursor cursor = getContentResolver().query(
                uri, new String[] {"_display_name"}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    String display = cursor.getString(0);
                    if (display != null && !display.isEmpty()) {
                        display = display.replaceAll("[^a-zA-Z0-9._-]", "_");
                        name = display;
                    }
                }
            } finally {
                cursor.close();
            }
        }

        File target = new File(getCacheDir(), name);
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.BufferedOutputStream(
                     new java.io.FileOutputStream(target), 1024 * 1024)) {
            if (in == null) throw new java.io.IOException("Cannot open selected file");
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        return target;
    }

    private void sendStagedFile(File file) {
        int total = peers.sessions().size();
        AtomicInteger complete = new AtomicInteger(0);

        updateStatus("Sending " + file.getName() + " to " + total + " devices...");

        for (DeviceSession session : peers.sessions()) {
            peers.transferFile(session.address(), file, new MultiDeviceManager.TransferListener() {
                @Override public void onComplete(DeviceSession peer, long bytes) {
                    complete[0]++;
                    runOnUiThread(() -> updateStatus(
                            "Transfer complete: " + safeName(peer.device)
                                    + " (" + bytes + " bytes), "
                                    + complete[0] + "/" + total));
                    if (complete[0] == total) file.delete();
                }

                @Override public void onError(DeviceSession peer, Exception error) {
                    complete[0]++;
                    runOnUiThread(() -> updateStatus(
                            "Transfer failed: " + safeName(peer == null ? null : peer.device)
                                    + " - " + safeError(error)
                                    + " (" + complete[0] + "/" + total + ")"));
                    if (complete[0] == total) file.delete();
                }
            });
        }
    }

    private void attachCommandClient(DeviceSession session) {
        commandClients.computeIfAbsent(
                session.address(),
                ignored -> new ReliableCommandClient(
                        frame -> session.connection.send(frame),
                        session.metrics::adaptiveTimeoutMs));
    }

    private int peerCount() {
        return peers == null ? 0 : peers.sessions().size();
    }

    private void closeCommandClients() {
        for (ReliableCommandClient client : commandClients.values()) client.close();
        commandClients.clear();
    }

    private void updateStatus(String value) {
        if (status != null) status.setText(value);
    }

    private static String safeName(BluetoothDevice device) {
        if (device == null) return "unknown";
        try {
            String name = device.getName();
            return name == null ? "unknown" : name;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String safeError(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static String format(double value) {
        return Double.isNaN(value)
                ? "-"
                : String.format(java.util.Locale.US, "%.1f", value);
    }

    @Override protected void onDestroy() {
        if (bound && peers != null) peers.removeListener(uiListener);
        closeCommandClients();
        if (bound) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            bound = false;
        }
        service = null;
        peers = null;
        super.onDestroy();
    }
}
