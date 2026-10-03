package com.jeevesh415.blutoothconnector;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jeevesh415.blutoothconnector.control.BulkTransferAuthorization;
import com.jeevesh415.blutoothconnector.control.RemoteControlAuthorization;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.protocol.ReliableCommandClient;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;
import com.jeevesh415.blutoothconnector.transport.WifiAwarePathManager;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

public final class DeviceSessionActivity extends Activity {
    private static final int REQUEST_STREAM_AUDIO = 401;
    private static final int REQUEST_SCREEN_CAPTURE = 402;
    private static final int REQUEST_FILE = 403;
    private static final int REQUEST_WIFI = 404;

    private final int bg = Color.parseColor("#08111F");
    private final int panel = Color.parseColor("#101D30");
    private final int panel2 = Color.parseColor("#14263D");
    private final int accent = Color.parseColor("#55D6FF");
    private final int green = Color.parseColor("#59E6A7");
    private final int red = Color.parseColor("#FF6B7A");
    private final int textPrimary = Color.parseColor("#F4F8FF");
    private final int textSecondary = Color.parseColor("#9DB0C8");

    private ConnectionService service;
    private MultiDeviceManager peers;
    private ServiceConnection connection;
    private TextView status;
    private TextView peerInfo;
    private LinearLayout peerList;
    private DeviceSession selected;
    private String pendingStreamPeer;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        bindService();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 38, 28, 38);

        TextView eyebrow = text("UNIFIED SESSION", 11, accent);
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow);

        TextView title = text("Device Control Center", 29, textPrimary);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, 7, 0, 3);
        root.addView(title);

        TextView subtitle = text(
                "One connected session for screen, control, device state, transfer, network and authorization.",
                14, textSecondary);
        root.addView(subtitle);

        addGap(root, 18);

        LinearLayout connectionCard = card();
        TextView label = text("ACTIVE CONNECTION", 11, accent);
        connectionCard.addView(label);

        peerInfo = text("Connecting…", 15, textPrimary);
        peerInfo.setPadding(0, 8, 0, 0);
        connectionCard.addView(peerInfo);

        status = text("Opening session…", 13, textSecondary);
        status.setPadding(0, 8, 0, 0);
        connectionCard.addView(status);
        root.addView(connectionCard);

        addGap(root, 10);

        LinearLayout peersCard = card();
        TextView peersTitle = text("CONNECTED DEVICES", 11, accent);
        peersCard.addView(peersTitle);
        peerList = new LinearLayout(this);
        peerList.setOrientation(LinearLayout.VERTICAL);
        peerList.setPadding(0, 8, 0, 0);
        peersCard.addView(peerList);
        root.addView(peersCard);

        addSection(root, "LIVE CONTROL");
        addButton(root, "◉  View screen + remote control", panel2, textPrimary,
                v -> openViewer());
        addButton(root, "■  Stop remote stream", panel2, textPrimary,
                v -> stopStream());
        addButton(root, "●  Start screen + microphone stream", accent, bg,
                v -> startScreenStream());

        addSection(root, "DEVICE");
        addButton(root, "▣  Refresh device information + state", panel2, textPrimary,
                v -> queryDevice());
        addButton(root, "▶  Launch an app on this device", panel2, textPrimary,
                v -> launchApp());

        addSection(root, "FILES + NETWORK");
        addButton(root, "⇧  Send file to selected device", panel2, textPrimary,
                v -> chooseFile());
        addButton(root, "⌁  Start Wi-Fi Direct discovery", panel2, textPrimary,
                v -> startWifiDirect());
        addButton(root, "◌  Start Wi-Fi Aware transport", panel2, textPrimary,
                v -> startWifiAware());

        addSection(root, "AUTHORIZATION");
        addButton(root, "✓  Authorize remote control", panel2, textPrimary,
                v -> authorize(true));
        addButton(root, "×  Revoke remote control", panel2, textPrimary,
                v -> authorize(false));
        addButton(root, "⇧  Authorize file transfer", panel2, textPrimary,
                v -> authorizeBulk(true));
        addButton(root, "×  Revoke file transfer", panel2, textPrimary,
                v -> authorizeBulk(false));
        addButton(root, "⚙  Open Accessibility settings", panel2, textPrimary,
                v -> openAccessibility());

        addSection(root, "DEVICE ADMIN");
        addButton(root, "▣  Device-admin enrollment", panel2, textPrimary,
                v -> openDeviceAdmin());

        addGap(root, 12);
        Button close = addButton(root, "CLOSE SESSION DASHBOARD", Color.TRANSPARENT, textSecondary,
                v -> finish());
        close.setBackgroundColor(Color.TRANSPARENT);

        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(20, 18, 20, 18);
        android.graphics.drawable.GradientDrawable bgShape =
                new android.graphics.drawable.GradientDrawable();
        bgShape.setColor(panel);
        bgShape.setCornerRadius(22f);
        card.setBackground(bgShape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = 8;
        card.setLayoutParams(lp);
        return card;
    }

    private Button addButton(LinearLayout root, String label, int background,
            int foreground, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(foreground);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(18, 2, 18, 2);
        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
        shape.setColor(background == Color.TRANSPARENT ? panel : background);
        shape.setCornerRadius(19f);
        b.setBackground(shape);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 56);
        lp.bottomMargin = 8;
        b.setLayoutParams(lp);
        root.addView(b);
        return b;
    }

    private void addSection(LinearLayout root, String label) {
        TextView s = text(label, 11, accent);
        s.setTypeface(null, Typeface.BOLD);
        s.setPadding(4, 16, 4, 7);
        root.addView(s);
    }

    private void addGap(LinearLayout root, int height) {
        root.addView(new View(this), new LinearLayout.LayoutParams(1, height));
    }

    private void bindService() {
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((ConnectionService.LocalBinder) binder).service();
                peers = service.peers();
                rebuildPeerList();
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                service = null;
                peers = null;
                selected = null;
                peerInfo.setText("Disconnected");
            }
        };

        Intent intent = new Intent(this, ConnectionService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            bindService(intent, connection, BIND_AUTO_CREATE);
        } catch (Exception e) {
            peerInfo.setText("Connection service unavailable");
        }
    }

    private void rebuildPeerList() {
        peerList.removeAllViews();
        if (peers == null || peers.sessions().isEmpty()) {
            selected = null;
            peerInfo.setText("No connected peer");
            status.setText("Connect a device first.");
            TextView empty = text("No active device sessions.", 13, textSecondary);
            peerList.addView(empty);
            return;
        }

        for (DeviceSession session : peers.sessions()) {
            Button peer = new Button(this);
            peer.setText("●  " + safeName(session.device) + "\n    " + session.address());
            peer.setAllCaps(false);
            peer.setGravity(Gravity.CENTER_VERTICAL);
            peer.setTextColor(textPrimary);
            peer.setTextSize(13);
            peer.setPadding(16, 4, 16, 4);
            android.graphics.drawable.GradientDrawable shape =
                    new android.graphics.drawable.GradientDrawable();
            shape.setColor(panel2);
            shape.setCornerRadius(18f);
            peer.setBackground(shape);
            peer.setOnClickListener(v -> {
                selected = session;
                showSelected();
                rebuildPeerList();
            });
            peerList.addView(peer, new LinearLayout.LayoutParams(-1, 62));
        }

        if (selected == null || !containsSelected()) {
            selected = peers.sessions().iterator().next();
        }
        showSelected();
    }

    private boolean containsSelected() {
        if (selected == null || peers == null) return false;
        for (DeviceSession s : peers.sessions()) {
            if (selected.address().equals(s.address())) return true;
        }
        return false;
    }

    private void showSelected() {
        if (selected == null) return;
        peerInfo.setText(
                "●  " + safeName(selected.device)
                        + "\n" + selected.address()
                        + "\nRTT EWMA  " + selected.metrics.ewmaMs() + " ms"
                        + "   •   p95  " + selected.metrics.p95Ms() + " ms");
        peerInfo.setTextColor(green);
        status.setText("Unified session ready. Selected device can be controlled from this dashboard.");
        status.setTextColor(textSecondary);
    }

    private DeviceSession selectedSession() {
        if (selected != null && containsSelected()) return selected;
        if (peers == null || peers.sessions().isEmpty()) return null;
        selected = peers.sessions().iterator().next();
        return selected;
    }

    private String safeName(android.bluetooth.BluetoothDevice device) {
        if (device == null) return "Unknown device";
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
            return "Connected device";
        }
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? "Connected device" : name;
        } catch (Exception ignored) {
            return "Connected device";
        }
    }

    private void openViewer() {
        if (selectedSession() == null) {
            status.setText("No connected device.");
            return;
        }
        startActivity(new Intent(this, RtcViewerActivity.class)
                .putExtra("peer_address", selected.address()));
    }

    private void stopStream() {
        DeviceSession session = selectedSession();
        if (session == null) { status.setText("No connected device."); return; }
        try {
            service.rtc().requestStop(session);
            status.setText("Remote stream stop requested.");
        } catch (Exception e) {
            status.setText("Could not stop remote stream: " + e.getMessage());
        }
    }

    private void queryDevice() {
        DeviceSession session = selectedSession();
        if (session == null) { status.setText("No connected device."); return; }
        try {
            service.refreshCapabilities();
            ReliableCommandClient client = new ReliableCommandClient(
                    frame -> session.connection.send(frame),
                    session.metrics::adaptiveTimeoutMs);
            client.execute(session.nextSequence(), "device.info", "get", null)
                    .whenComplete((frame, error) -> runOnUiThread(() -> {
                        client.close();
                        status.setText(error == null
                                ? "Device information refreshed."
                                : "Device query failed: " + error.getMessage());
                    }));
        } catch (Exception e) {
            status.setText("Device query failed.");
        }
    }

    private void launchApp() {
        DeviceSession session = selectedSession();
        if (session == null) { status.setText("No connected device."); return; }

        EditText input = new EditText(this);
        input.setHint("package.name");
        input.setSingleLine(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Launch app")
                .setMessage("The selected device must have authorized this controller.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Launch", (d, w) -> {
                    try {
                        ReliableCommandClient client = new ReliableCommandClient(
                                frame -> session.connection.send(frame),
                                session.metrics::adaptiveTimeoutMs);
                        client.execute(session.nextSequence(), "app.control", "launch",
                                new JSONObject().put("packageName",
                                        input.getText().toString().trim()))
                                .whenComplete((frame, error) -> runOnUiThread(() -> {
                                    client.close();
                                    status.setText(error == null
                                            ? "Launch command sent."
                                            : "Launch failed: " + error.getMessage());
                                }));
                    } catch (Exception e) {
                        status.setText("Launch failed.");
                    }
                }).show();
    }

    private void chooseFile() {
        if (selectedSession() == null) {
            status.setText("Select a connected device first.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_FILE);
    }

    private void startWifiDirect() {
        if (service == null) { status.setText("Connection service unavailable."); return; }
        try {
            service.startWifiDirect();
            status.setText("Wi-Fi Direct discovery started.");
        } catch (Exception e) {
            status.setText("Wi-Fi Direct could not start: " + e.getMessage());
        }
    }

    private void startWifiAware() {
        if (Build.VERSION.SDK_INT < 31) {
            status.setText("Wi-Fi Aware requires Android 12+.");
            return;
        }
        if (!WifiAwarePathManager.isSupported(this)) {
            status.setText("Wi-Fi Aware is not available on this device.");
            return;
        }
        try {
            service.startWifiAware();
            status.setText("Wi-Fi Aware transport starting.");
        } catch (SecurityException e) {
            status.setText("Wi-Fi Aware permission is required.");
        } catch (Exception e) {
            status.setText("Wi-Fi Aware could not start: " + e.getMessage());
        }
    }

    private void startScreenStream() {
        DeviceSession session = selectedSession();
        if (session == null) {
            status.setText("Select a connected device first.");
            return;
        }
        pendingStreamPeer = session.address();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_STREAM_AUDIO);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            status.setText("MediaProjection is unavailable.");
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE);
        status.setText("Approve Android's screen-capture prompt.");
    }

    private void authorizeBulk(boolean allow) {
        DeviceSession session = selectedSession();
        if (session == null) {
            status.setText("No connected device.");
            return;
        }
        try {
            if (allow) {
                BulkTransferAuthorization.authorize(this, session.address());
            } else {
                BulkTransferAuthorization.revoke(this, session.address());
            }
            if (service != null) service.refreshCapabilitiesAndRotateBulkToken();
            status.setText(allow
                    ? "File transfer authorized for this peer."
                    : "File transfer revoked for this peer.");
        } catch (Exception error) {
            status.setText("File-transfer authorization failed: " + error.getMessage());
        }
    }

    private void authorize(boolean allow) {
        DeviceSession session = selectedSession();
        if (session == null) { status.setText("No connected device."); return; }
        try {
            if (allow) RemoteControlAuthorization.authorize(this, session.address());
            else RemoteControlAuthorization.revoke(this, session.address());
            service.refreshCapabilities();
            status.setText(allow
                    ? "Remote control authorized for this device."
                    : "Remote control revoked for this device.");
        } catch (Exception e) {
            status.setText("Authorization change failed.");
        }
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            status.setText("Accessibility settings unavailable.");
        }
    }

    private void openDeviceAdmin() {
        try {
            Intent intent = new Intent(
                    android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    new ComponentName(this,
                            com.jeevesh415.blutoothconnector.admin.BlutoothDeviceAdminReceiver.class));
            startActivity(intent);
        } catch (Exception e) {
            status.setText("Device-admin enrollment unavailable.");
        }
    }


    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode != RESULT_OK || data == null || pendingStreamPeer == null) {
                status.setText("Screen capture was not granted.");
                return;
            }
            try {
                service.startScreenShare(pendingStreamPeer, resultCode, data);
                status.setText("Screen + microphone streaming started.");
            } catch (Exception e) {
                status.setText("Could not start stream: " + e.getMessage());
            }
            return;
        }

        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;

        try {
            File staged = stageUri(data.getData());
            DeviceSession session = selectedSession();
            if (session == null) {
                status.setText("Selected device disconnected.");
                return;
            }
            peers.transferFile(session.address(), staged,
                    new MultiDeviceManager.TransferListener() {
                        @Override public void onComplete(DeviceSession peer, long bytes) {
                            runOnUiThread(() -> {
                                status.setText("Transfer complete • " + bytes + " bytes");
                                staged.delete();
                            });
                        }

                        @Override public void onError(DeviceSession peer, Exception error) {
                            runOnUiThread(() -> {
                                status.setText("Transfer failed: " + error.getMessage());
                                staged.delete();
                            });
                        }
                    });
            status.setText("Sending " + staged.getName() + "…");
        } catch (Exception e) {
            status.setText("Could not stage file: " + e.getMessage());
        }
    }

    private File stageUri(Uri uri) throws Exception {
        String name = "upload-" + System.currentTimeMillis() + ".bin";
        android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{"_display_name"}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    String display = cursor.getString(0);
                    if (display != null && !display.isEmpty()) {
                        name = display.replaceAll("[^a-zA-Z0-9._-]", "_");
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

    @Override protected void onResume() {
        super.onResume();
        if (peers != null) rebuildPeerList();
        if (pendingStreamPeer != null
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
            requestScreenCapture();
        }
    }

    @Override protected void onDestroy() {
        if (connection != null) {
            try { unbindService(connection); } catch (Exception ignored) {}
        }
        service = null;
        peers = null;
        super.onDestroy();
    }
}
