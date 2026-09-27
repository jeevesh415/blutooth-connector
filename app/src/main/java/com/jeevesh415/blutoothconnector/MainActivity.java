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
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jeevesh415.blutoothconnector.control.RemoteControlAuthorization;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.protocol.ReliableCommandClient;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;
import com.jeevesh415.blutoothconnector.transport.WifiAwarePathManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 100;
    private static final int REQUEST_LOCAL_NETWORK = 101;
    private static final int REQUEST_WIFI_DIRECT = 102;
    private static final int REQUEST_WIFI_AWARE = 103;
    private static final int REQUEST_FILE = 200;
    private static final int REQUEST_SCREEN_CAPTURE = 300;
    private static final int REQUEST_STREAM_PERMISSIONS = 301;

    private String pendingStreamPeer;

    private final ArrayList<BluetoothDevice> devices = new ArrayList<>();
    private final Map<String, ReliableCommandClient> commandClients =
            new ConcurrentHashMap<>();

    private TextView status;
    private ConnectionService service;
    private MultiDeviceManager peers;
    private boolean bound;
    private boolean pendingWifiDirectStart;
    private boolean pendingWifiAwareStart;

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
            if (pendingWifiDirectStart && service != null) {
                pendingWifiDirectStart = false;
                service.startWifiDirect();
                updateStatus("Wi-Fi Direct discovery started.");
            }
            if (pendingWifiAwareStart && service != null) {
                pendingWifiAwareStart = false;
                try {
                    service.startWifiAware();
                    updateStatus("Wi-Fi Aware transport starting.");
                } catch (Exception error) {
                    updateStatus("Wi-Fi Aware could not start: " + safeError(error));
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

        ArrayList<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }

        if (missing.isEmpty()) {
            ensureConnectionService();
        } else {
            requestPermissions(
                    missing.toArray(new String[0]),
                    REQUEST_BLUETOOTH_PERMISSIONS);
        }
    }

    private void requestLocalNetworkThenChooseFile() {
        ArrayList<String> missing = new ArrayList<>();


        if (missing.isEmpty()) {
            if (service != null) service.ensureBulkEndpoint();
            openFilePicker();
            return;
        }

        requestPermissions(
                missing.toArray(new String[0]),
                REQUEST_LOCAL_NETWORK);
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS
                && requestCode != REQUEST_LOCAL_NETWORK
                && requestCode != REQUEST_WIFI_DIRECT
                && requestCode != REQUEST_STREAM_PERMISSIONS) {
            return;
        }

        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (requestCode == REQUEST_STREAM_PERMISSIONS) {
            if (allGranted) {
                requestScreenCapture();
            } else {
                status.setText("Network and microphone permissions are required for A/V streaming.");
            }
            return;
        }

        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (allGranted) {
                ensureConnectionService();
                status.setText(
                        "Bluetooth permissions granted. Connection service starting.");
            } else {
                status.setText(
                        "Bluetooth permissions were not granted.");
            }
            return;
        }

        if (requestCode == REQUEST_WIFI_AWARE) {
            if (allGranted) {
                pendingWifiAwareStart = false;
                if (service == null) {
                    pendingWifiAwareStart = true;
                    ensureConnectionService();
                    status.setText("Connection service starting; Wi-Fi Aware will start when ready.");
                } else {
                    try {
                        service.startWifiAware();
                        updateStatus("Wi-Fi Aware transport starting.");
                    } catch (Exception error) {
                        updateStatus("Wi-Fi Aware could not start: " + safeError(error));
                    }
                }
            } else {
                pendingWifiAwareStart = false;
                status.setText("Wi-Fi Aware permissions were not granted.");
            }
            return;
        }

        if (requestCode == REQUEST_WIFI_DIRECT) {
            if (allGranted) {
                pendingWifiDirectStart = true;
                ensureConnectionService();
                if (service != null) {
                    pendingWifiDirectStart = false;
                    service.startWifiDirect();
                }
                status.setText(
                        "Wi-Fi Direct discovery starting.");
            } else {
                pendingWifiDirectStart = false;
                status.setText(
                        "Wi-Fi Direct permission was not granted.");
            }
            return;
        }

        if (allGranted) {
            if (service != null) service.ensureBulkEndpoint();
            openFilePicker();
        } else {
            status.setText(
                    "Local network permission was not granted.");
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
        final int bg = android.graphics.Color.parseColor("#08111F");
        final int panel = android.graphics.Color.parseColor("#101D30");
        final int panel2 = android.graphics.Color.parseColor("#14263D");
        final int accent = android.graphics.Color.parseColor("#55D6FF");
        final int textPrimary = android.graphics.Color.parseColor("#F4F8FF");
        final int textSecondary = android.graphics.Color.parseColor("#9DB0C8");

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 44, 32, 44);

        TextView eyebrow = new TextView(this);
        eyebrow.setText("BLUTOOTH CONNECTOR  •  CONTROL CENTER");
        eyebrow.setTextSize(12);
        eyebrow.setTextColor(accent);
        eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText("Connected Devices");
        title.setTextSize(30);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(textPrimary);
        title.setPadding(0, 8, 0, 4);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Connect once. Manage the complete device session from one interface.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(textSecondary);
        subtitle.setPadding(0, 0, 0, 24);
        root.addView(subtitle);

        LinearLayout statusCard = card(panel);
        TextView statusLabel = new TextView(this);
        statusLabel.setText("CONNECTION STATUS");
        statusLabel.setTextSize(11);
        statusLabel.setTextColor(accent);
        statusCard.addView(statusLabel);

        status = new TextView(this);
        status.setText("Waiting for connection service…");
        status.setTextSize(15);
        status.setTextColor(textPrimary);
        status.setPadding(0, 8, 0, 0);
        statusCard.addView(status);
        root.addView(statusCard);

        addGap(root, 12);

        Button scan = actionButton("SCAN  •  Find paired devices", panel2, textPrimary);
        scan.setOnClickListener(v -> inspectBluetooth());
        root.addView(scan);

        Button connectAll = actionButton("CONNECT  •  Connect paired devices", accent, bg);
        connectAll.setOnClickListener(v -> connectAll());
        root.addView(connectAll);

        Button session = actionButton("OPEN SESSION  →  Unified device control", panel2, textPrimary);
        session.setOnClickListener(v -> {
            if (peers == null || peers.sessions().isEmpty()) {
                status.setText("Connect a device first.");
                return;
            }
            startActivity(new Intent(this, DeviceSessionActivity.class));
        });
        root.addView(session);

        addGap(root, 16);

        LinearLayout tools = card(panel);
        TextView toolsTitle = new TextView(this);
        toolsTitle.setText("SESSION TOOLS");
        toolsTitle.setTextSize(11);
        toolsTitle.setTextColor(accent);
        tools.addView(toolsTitle);

        Button receiver = compactButton("Keep receiver service active");
        receiver.setOnClickListener(v -> ensureConnectionService());
        tools.addView(receiver);

        Button pingAll = compactButton("Ping connected devices");
        pingAll.setOnClickListener(v -> pingAll());
        tools.addView(pingAll);

        Button infoAll = compactButton("Refresh device information");
        infoAll.setOnClickListener(v -> queryAllDeviceInfo());
        tools.addView(infoAll);

        Button stateAll = compactButton("Refresh device state");
        stateAll.setOnClickListener(v -> queryAllDeviceState());
        tools.addView(stateAll);

        root.addView(tools);

        TextView footer = new TextView(this);
        footer.setText("All remote actions remain subject to Android authorization, Accessibility, MediaProjection and device-admin boundaries.");
        footer.setTextSize(11);
        footer.setTextColor(textSecondary);
        footer.setPadding(4, 22, 4, 0);
        root.addView(footer);

        android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        shell.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));

        Button sensorCorner = new Button(this);
        sensorCorner.setText("◈");
        sensorCorner.setTextSize(20);
        sensorCorner.setTextColor(textPrimary);
        sensorCorner.setContentDescription("Open sensor control");
        android.graphics.drawable.GradientDrawable sensorBg =
                new android.graphics.drawable.GradientDrawable();
        sensorBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        sensorBg.setColor(panel2);
        sensorBg.setStroke(2, accent);
        sensorCorner.setBackground(sensorBg);
        sensorCorner.setOnClickListener(v -> openSensorControlPanel());

        android.widget.FrameLayout.LayoutParams sensorLp =
                new android.widget.FrameLayout.LayoutParams(62, 62, Gravity.TOP | Gravity.END);
        sensorLp.setMargins(0, 18, 18, 0);
        shell.addView(sensorCorner, sensorLp);

        setContentView(shell);
    }

    private LinearLayout card(int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(22, 18, 22, 18);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(22f);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                -1, -2);
        lp.bottomMargin = 10;
        card.setLayoutParams(lp);
        return card;
    }

    private Button actionButton(String label, int background, int foreground) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(foreground);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(20, 4, 20, 4);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(background);
        bg.setCornerRadius(20f);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 58);
        lp.bottomMargin = 10;
        b.setLayoutParams(lp);
        return b;
    }

    private Button compactButton(String label) {
        Button b = actionButton(label,
                android.graphics.Color.parseColor("#182A42"),
                android.graphics.Color.parseColor("#DCE9F8"));
        b.setTextSize(13);
        b.setBackgroundTintList(null);
        return b;
    }

    private void addGap(LinearLayout root, int height) {
        android.view.View gap = new android.view.View(this);
        root.addView(gap, new LinearLayout.LayoutParams(1, height));
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void openSensorControlPanel() {
        if (peers == null || peers.sessions().isEmpty()) {
            updateStatus("Connect a peer first.");
            return;
        }

        DeviceSession session = peers.sessions().iterator().next();
        ReliableCommandClient client = commandClients.get(session.address());
        if (client == null) {
            attachCommandClient(session);
            client = commandClients.get(session.address());
        }
        if (client == null) {
            updateStatus("Sensor control channel is unavailable.");
            return;
        }

        final ReliableCommandClient active = client;
        active.execute(
                session.nextSequence(),
                "sensor.control",
                "list",
                null
        ).whenComplete((frame, error) -> runOnUiThread(() -> {
            if (error != null) {
                updateStatus("Sensor inventory failed: " + safeError(error));
                return;
            }
            try {
                JSONObject result = frame.payload.optJSONObject("result");
                if (result == null) result = frame.payload;
                JSONArray sensors = result.optJSONArray("sensors");
                if (sensors == null) throw new IllegalStateException("No sensor inventory");

                LinearLayout content = new LinearLayout(this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(22, 10, 22, 8);

                TextView summary = new TextView(this);
                summary.setText(
                        sensors.length() + " sensors  •  "
                                + result.optInt("activeCount", 0) + " active\n"
                                + "Remote acquisition runs at the fastest rate the sensor + Android stack accepts.");
                summary.setTextSize(14);
                summary.setTextColor(android.graphics.Color.parseColor("#23324A"));
                summary.setPadding(2, 2, 2, 12);
                content.addView(summary);

                LinearLayout primary = new LinearLayout(this);
                primary.setOrientation(LinearLayout.HORIZONTAL);

                Button optimize = compactButton("AUTO\nMAX");
                optimize.setTextSize(12);
                optimize.setOnClickListener(v ->
                        configureAllSensorsForPerformance(session, active));
                primary.addView(optimize, new LinearLayout.LayoutParams(0, 62, 1f));

                Button live = compactButton("LIVE\nSTATE");
                live.setTextSize(12);
                live.setOnClickListener(v ->
                        requestSensorSnapshot(session, active));
                LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(0, 62, 1f);
                liveLp.leftMargin = 8;
                primary.addView(live, liveLp);

                Button fusion = compactButton("IMU\nFUSION");
                fusion.setTextSize(12);
                fusion.setOnClickListener(v ->
                        requestSensorFusion(session, active));
                LinearLayout.LayoutParams fusionLp = new LinearLayout.LayoutParams(0, 62, 1f);
                fusionLp.leftMargin = 8;
                primary.addView(fusion, fusionLp);

                content.addView(primary);

                Button stop = compactButton("STOP ALL SENSOR STREAMS");
                stop.setOnClickListener(v ->
                        stopAllRemoteSensors(session, active));
                content.addView(stop);

                TextView matrixLabel = new TextView(this);
                matrixLabel.setText("SENSOR MATRIX");
                matrixLabel.setTextSize(11);
                matrixLabel.setTypeface(null, android.graphics.Typeface.BOLD);
                matrixLabel.setTextColor(android.graphics.Color.parseColor("#237D9F"));
                matrixLabel.setPadding(2, 14, 2, 6);
                content.addView(matrixLabel);

                android.widget.ScrollView listScroll =
                        new android.widget.ScrollView(this);
                LinearLayout list = new LinearLayout(this);
                list.setOrientation(LinearLayout.VERTICAL);

                for (int i = 0; i < sensors.length(); i++) {
                    JSONObject sensor = sensors.getJSONObject(i);
                    int handle = sensor.getInt("handle");
                    String name = sensor.optString("name", "Sensor");
                    int type = sensor.optInt("type", -1);
                    int minDelay = sensor.optInt("minDelayUs", 0);
                    double maxHz = minDelay > 0 ? 1_000_000.0 / minDelay : 0.0;
                    boolean streamable = sensor.optBoolean("streamable", false);

                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(4, 9, 4, 9);

                    TextView label = new TextView(this);
                    String detail = "type " + type
                            + "  •  " + (minDelay > 0
                            ? String.format(java.util.Locale.US, "%.0f Hz max-request", maxHz)
                            : "event driven")
                            + "  •  " + (streamable ? "streamable" : "trigger/event");
                    label.setText(name + "\n" + detail);
                    label.setTextSize(12.5f);
                    label.setTextColor(android.graphics.Color.parseColor("#23324A"));
                    label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

                    Button tune = compactButton(streamable ? "TUNE" : "TRIGGER");
                    tune.setTextSize(11);
                    if (streamable) {
                        tune.setOnClickListener(v ->
                                configureRemoteSensor(session, active, handle, name, minDelay));
                    } else {
                        tune.setOnClickListener(v ->
                                triggerRemoteSensor(session, active, handle, name));
                    }

                    row.addView(label);
                    row.addView(tune);
                    list.addView(row);
                }

                listScroll.addView(list);
                content.addView(listScroll, new LinearLayout.LayoutParams(-1, 430));

                new android.app.AlertDialog.Builder(this)
                        .setTitle("Hardware & Sensor Control")
                        .setView(content)
                        .setNegativeButton("Close", null)
                        .show();

                updateStatus("Sensor control ready for " + sensors.length()
                        + " sensors on " + safeName(session.device) + ".");
            } catch (Exception parseError) {
                updateStatus("Sensor panel error: " + safeError(parseError));
            }
        }));
    }

    private void configureAllSensorsForPerformance(
            DeviceSession session,
            ReliableCommandClient client) {
        client.execute(
                session.nextSequence(),
                "sensor.control",
                "optimize",
                null
        ).whenComplete((frame, error) -> runOnUiThread(() -> {
            if (error != null) {
                updateStatus("Sensor optimization failed: " + safeError(error));
                return;
            }
            JSONObject result = frame.payload.optJSONObject("result");
            if (result == null) result = frame.payload;
            updateStatus(
                    "MAX PERFORMANCE requested • "
                            + result.optInt("requested", 0) + " streams started • "
                            + result.optInt("skipped", 0) + " trigger-only/unavailable");
        }));
    }

    private void triggerRemoteSensor(
            DeviceSession session,
            ReliableCommandClient client,
            int handle,
            String name) {
        try {
            JSONObject payload = new JSONObject().put("handle", handle);
            client.execute(
                    session.nextSequence(),
                    "sensor.control",
                    "trigger",
                    payload
            ).whenComplete((frame, error) -> runOnUiThread(() ->
                    updateStatus(error == null
                            ? "Trigger armed for " + name + "."
                            : "Trigger failed: " + safeError(error))));
        } catch (org.json.JSONException error) {
            updateStatus("Could not prepare trigger: " + safeError(error));
        }
    }

    private void configureRemoteSensor(
            DeviceSession session,
            ReliableCommandClient client,
            int handle,
            String name,
            int minDelayUs) {
        final android.widget.LinearLayout editor =
                new android.widget.LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(8, 4, 8, 0);

        final android.widget.EditText rateInput = new android.widget.EditText(this);
        rateInput.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        rateInput.setHint("Sampling rate in Hz");
        if (minDelayUs > 0) {
            rateInput.setText(String.format(
                    java.util.Locale.US,
                    "%.0f",
                    1_000_000.0 / minDelayUs));
            rateInput.setSelection(rateInput.length());
        }
        editor.addView(rateInput);

        final android.widget.EditText latencyInput =
                new android.widget.EditText(this);
        latencyInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        latencyInput.setHint("Batching latency (ms), 0 = immediate");
        latencyInput.setText("0");
        editor.addView(latencyInput);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Tune • " + name)
                .setMessage(
                        "The request is clamped to the sensor's Android/HAL limits. "
                                + "Use 0 ms latency for lowest acquisition delay.")
                .setView(editor)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    try {
                        double hz = Double.parseDouble(
                                rateInput.getText().toString().trim());
                        long latencyMs = Long.parseLong(
                                latencyInput.getText().toString().trim());
                        if (!(hz > 0) || hz > 10000) {
                            throw new IllegalArgumentException(
                                    "Rate must be 0 < Hz <= 10000");
                        }
                        if (latencyMs < 0 || latencyMs > 60000) {
                            throw new IllegalArgumentException(
                                    "Latency must be 0..60000 ms");
                        }

                        int requestedPeriodUs = (int) Math.max(
                                1L,
                                Math.round(1_000_000.0 / hz));
                        int maxLatencyUs = (int) Math.min(
                                Integer.MAX_VALUE,
                                latencyMs * 1000L);

                        JSONObject payload = new JSONObject()
                                .put("handle", handle)
                                .put("periodUs", requestedPeriodUs)
                                .put("maxReportLatencyUs", maxLatencyUs);

                        client.execute(
                                session.nextSequence(),
                                "sensor.control",
                                "configure",
                                payload
                        ).whenComplete((frame, error) -> runOnUiThread(() -> {
                            if (error != null) {
                                updateStatus(
                                        "Sensor tuning failed: " + safeError(error));
                                return;
                            }
                            JSONObject result = frame.payload.optJSONObject("result");
                            if (result == null) result = frame.payload;
                            updateStatus(
                                    "Configured " + name + " • requested "
                                            + hz + " Hz • effective "
                                            + result.optDouble("effectiveRateHz", hz)
                                            + " Hz");
                        }));
                    } catch (Exception error) {
                        updateStatus("Invalid sensor settings: " + safeError(error));
                    }
                })
                .show();
    }

    private void requestSensorSnapshot(
            DeviceSession session,
            ReliableCommandClient client) {
        client.execute(
                session.nextSequence(),
                "sensor.control",
                "snapshot",
                null
        ).whenComplete((frame, error) -> runOnUiThread(() -> {
            if (error != null) {
                updateStatus("Sensor snapshot failed: " + safeError(error));
                return;
            }
            JSONObject result = frame.payload.optJSONObject("result");
            if (result == null) result = frame.payload;
            updateStatus(
                    "LIVE STATE • " + result.optInt("count", 0)
                            + " samples • "
                            + result.optInt("activeCount", 0) + " active");
        }));
    }

    private void requestSensorFusion(
            DeviceSession session,
            ReliableCommandClient client) {
        client.execute(
                session.nextSequence(),
                "sensor.control",
                "fusion",
                null
        ).whenComplete((frame, error) -> runOnUiThread(() -> {
            if (error != null) {
                updateStatus("IMU fusion unavailable: " + safeError(error));
                return;
            }
            JSONObject result = frame.payload.optJSONObject("result");
            if (result == null) result = frame.payload;
            if (!result.optBoolean("available", false)) {
                updateStatus("IMU fusion waiting for accelerometer/gyro samples.");
                return;
            }

            double roll = result.optDouble("rollDeg", 0.0);
            double pitch = result.optDouble("pitchDeg", 0.0);
            double yaw = result.optDouble("yawDeg", 0.0);
            updateStatus(String.format(
                    java.util.Locale.US,
                    "IMU FUSION • roll %.1f° • pitch %.1f° • yaw %.1f°",
                    roll, pitch, yaw));
        }));
    }

    private void stopAllRemoteSensors(
            DeviceSession session,
            ReliableCommandClient client) {
        client.execute(
                session.nextSequence(),
                "sensor.control",
                "stop",
                null
        ).whenComplete((frame, error) -> runOnUiThread(() ->
                updateStatus(error == null
                        ? "All remote sensor streams stopped."
                        : "Could not stop sensor streams: " + safeError(error))));
    }

    private void inspectBluetooth() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
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

    private void queryAllDeviceState() {
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
                    "device.state",
                    "get",
                    null
            ).whenComplete((frame, error) -> runOnUiThread(() -> {
                if (error != null) {
                    updateStatus("Device state failed for "
                            + safeName(session.device) + ": "
                            + safeError(error));
                } else {
                    updateStatus("Deep state from "
                            + safeName(session.device) + ": "
                            + frame.payload.toString());
                }
            }));
        }
    }

    private void launchAppOnFirstPeer() {
        if (peers == null || peers.sessions().isEmpty()) {
            status.setText("Connect a peer first.");
            return;
        }

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("package.name");
        input.setSingleLine(true);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Launch package on first peer")
                .setMessage("The remote peer must have explicitly authorized this phone.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Launch", (dialog, which) -> {
                    DeviceSession session = peers.sessions().iterator().next();
                    ReliableCommandClient client = commandClients.get(session.address());
                    if (client == null) {
                        attachCommandClient(session);
                        client = commandClients.get(session.address());
                    }
                    if (client == null) return;

                    try {
                        JSONObject payload = new JSONObject()
                                .put("packageName", input.getText().toString().trim());
                        client.execute(
                                session.nextSequence(),
                                "app.control",
                                "launch",
                                payload
                        ).whenComplete((frame, error) -> runOnUiThread(() -> {
                            if (error != null) {
                                updateStatus("Launch failed: " + safeError(error));
                            } else {
                                updateStatus("Remote launch result: "
                                        + frame.payload.toString());
                            }
                        }));
                    } catch (Exception error) {
                        updateStatus("Invalid launch request: " + safeError(error));
                    }
                })
                .show();
    }

    private void openDeviceAdminEnrollment() {
        try {
            Intent intent = new Intent(
                    android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    new ComponentName(
                            this,
                            com.jeevesh415.blutoothconnector.admin.BlutoothDeviceAdminReceiver.class));
            intent.putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Enables explicitly authorized peers to use device-admin operations supported by Android.");
            startActivity(intent);
        } catch (Exception error) {
            updateStatus("Device-admin enrollment unavailable: " + safeError(error));
        }
    }

    private void chooseFile() {
        if (peers == null || peers.sessions().isEmpty()) {
            status.setText("Connect at least one peer first.");
            return;
        }
        requestLocalNetworkThenChooseFile();
    }

    private void startWifiDirect() {
        ArrayList<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(
                    Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            if (checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }


        if (!missing.isEmpty()) {
            pendingWifiDirectStart = true;
            requestPermissions(
                    missing.toArray(new String[0]),
                    REQUEST_WIFI_DIRECT);
            return;
        }

        if (service == null) {
            pendingWifiDirectStart = true;
            ensureConnectionService();
            status.setText(
                    "Connection service is starting; Wi-Fi Direct will start when ready.");
            return;
        }

        service.startWifiDirect();
        updateStatus(
                "Wi-Fi Direct discovery started. Keep Wi-Fi enabled on both devices.");
    }

    private void startWifiAware() {
        ArrayList<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT < 31) {
            status.setText("Secure Wi-Fi Aware transport requires Android 12 or newer.");
            return;
        }

        if (!WifiAwarePathManager.isSupported(this)) {
            status.setText("This device does not currently expose Wi-Fi Aware.");
            return;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(
                    Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }


        if (!missing.isEmpty()) {
            pendingWifiAwareStart = true;
            requestPermissions(
                    missing.toArray(new String[0]),
                    REQUEST_WIFI_AWARE);
            return;
        }

        if (service == null) {
            pendingWifiAwareStart = true;
            ensureConnectionService();
            status.setText(
                    "Connection service is starting; Wi-Fi Aware will start when ready.");
            return;
        }

        try {
            service.startWifiAware();
            updateStatus(
                    "Wi-Fi Aware transport starting. Both devices must run the app.");
        } catch (Exception error) {
            status.setText(
                    "Wi-Fi Aware could not start: " + safeError(error));
        }
    }

    private void connectFirstWifiDirectPeer() {
        if (service == null || service.wifiDirect() == null) {
            status.setText(
                    "Start Wi-Fi Direct discovery first.");
            return;
        }

        java.util.List<android.net.wifi.p2p.WifiP2pDevice> candidates =
                service.wifiDirect().peers();
        if (candidates.isEmpty()) {
            status.setText(
                    "No Wi-Fi Direct app peers discovered yet.");
            return;
        }

        android.net.wifi.p2p.WifiP2pDevice device = candidates.get(0);
        service.wifiDirect().connect(device.deviceAddress);
        updateStatus(
                "Connecting Wi-Fi Direct: " + safeWifiName(device));
    }

    private static String safeWifiName(
            android.net.wifi.p2p.WifiP2pDevice device) {
        if (device == null) return "unknown";
        return device.deviceName == null
                ? "unknown"
                : device.deviceName;
    }

    private void startScreenStream() {
        if (service == null || peers == null || peers.sessions().isEmpty()) {
            status.setText("Connect at least one peer first.");
            return;
        }
        DeviceSession first = peers.sessions().iterator().next();
        pendingStreamPeer = first.address();

        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!missing.isEmpty()) {
            requestPermissions(
                    missing.toArray(new String[0]),
                    REQUEST_STREAM_PERMISSIONS);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager)
                        getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            status.setText("MediaProjection is unavailable.");
            return;
        }
        startActivityForResult(
                manager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE);
        status.setText("Approve Android's screen-capture prompt.");
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode != RESULT_OK || data == null || pendingStreamPeer == null) {
                status.setText("Screen capture was not granted.");
                return;
            }
            try {
                service.startScreenShare(
                        pendingStreamPeer,
                        resultCode,
                        data);
                status.setText("Screen + microphone streaming started.");
            } catch (Exception error) {
                status.setText("Could not start stream: " + safeError(error));
            }
            return;
        }

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
                    complete.incrementAndGet();
                    runOnUiThread(() -> updateStatus(
                            "Transfer complete: " + safeName(peer.device)
                                    + " (" + bytes + " bytes), "
                                    + complete.get() + "/" + total));
                    if (complete.get() == total) file.delete();
                }

                @Override public void onError(DeviceSession peer, Exception error) {
                    complete.incrementAndGet();
                    runOnUiThread(() -> updateStatus(
                            "Transfer failed: " + safeName(peer == null ? null : peer.device)
                                    + " - " + safeError(error)
                                    + " (" + complete.get() + "/" + total + ")"));
                    if (complete.get() == total) file.delete();
                }
            });
        }
    }

    private void authorizeFirstPeer() {
        if (peers == null || peers.sessions().isEmpty()) {
            status.setText("Connect a peer first.");
            return;
        }
        DeviceSession first = peers.sessions().iterator().next();
        try {
            RemoteControlAuthorization.authorize(this, first.address());
            if (service != null) service.refreshCapabilities();
            updateStatus(
                    "Remote control authorized for " + safeName(first.device)
                            + ". Enable this app's Accessibility service if it is not already enabled.");
        } catch (Exception error) {
            status.setText("Could not authorize peer: " + safeError(error));
        }
    }

    private void revokeFirstPeer() {
        if (peers == null || peers.sessions().isEmpty()) {
            status.setText("Connect a peer first.");
            return;
        }
        DeviceSession first = peers.sessions().iterator().next();
        try {
            RemoteControlAuthorization.revoke(this, first.address());
            if (service != null) service.refreshCapabilities();
            updateStatus(
                    "Remote control revoked for " + safeName(first.device) + ".");
        } catch (Exception error) {
            status.setText("Could not revoke peer: " + safeError(error));
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

    @android.annotation.SuppressLint("MissingPermission")
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
