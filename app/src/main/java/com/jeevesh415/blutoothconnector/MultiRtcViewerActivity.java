package com.jeevesh415.blutoothconnector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jeevesh415.blutoothconnector.media.RtcPeerManager;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;

import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-to-many remote-screen monitor. Every tile is an independent remote
 * MediaProjection/WebRTC session; each remote owner approves their own request.
 * Tap a tile to open the existing full-screen interactive controller for it.
 */
public final class MultiRtcViewerActivity extends Activity {
    public static final String EXTRA_PEERS = "peer_addresses";

    private final Map<String, SurfaceViewRenderer> renderers = new LinkedHashMap<>();
    private final Map<String, VideoTrack> tracks = new LinkedHashMap<>();
    private final Map<String, VideoSink> sinks = new LinkedHashMap<>();
    private final Map<String, TextView> tileStatuses = new LinkedHashMap<>();
    private final List<String> peerAddresses = new ArrayList<>();

    private EglBase eglBase;
    private ConnectionService service;
    private MultiDeviceManager peers;
    private ServiceConnection serviceConnection;
    private TextView summary;
    private RtcPeerManager.Listener rtcListener;
    private boolean handingOffToFullScreen;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String[] requested = getIntent().getStringArrayExtra(EXTRA_PEERS);
        if (requested == null || requested.length == 0) {
            finish();
            return;
        }
        for (String address : requested) {
            if (address != null && !address.isEmpty()
                    && !peerAddresses.contains(address)) {
                peerAddresses.add(address);
            }
        }
        if (peerAddresses.isEmpty()) {
            finish();
            return;
        }
        eglBase = EglBase.create();
        buildUi();
        bindConnectionService();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF08111F);
        root.setPadding(dp(16), dp(22), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Remote screens");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        summary = new TextView(this);
        summary.setText("Waiting for each device owner to approve…");
        summary.setTextColor(0xFF9DB0C8);
        summary.setTextSize(13);
        summary.setPadding(0, dp(5), 0, dp(12));
        root.addView(summary);

        ScrollView scroll = new ScrollView(this);
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < peerAddresses.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            addTile(row, peerAddresses.get(i));
            if (i + 1 < peerAddresses.size()) {
                addTile(row, peerAddresses.get(i + 1));
            }
            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(10);
            tiles.addView(row, rowParams);
        }
        scroll.addView(tiles);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button stopAll = new Button(this);
        stopAll.setText("Stop all remote streams");
        stopAll.setAllCaps(false);
        stopAll.setOnClickListener(v -> stopAllStreams());
        LinearLayout.LayoutParams stopParams =
                new LinearLayout.LayoutParams(-1, dp(52));
        stopParams.topMargin = dp(10);
        root.addView(stopAll, stopParams);

        setContentView(root);
    }

    private void addTile(LinearLayout row, String peerAddress) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(7), dp(7), dp(7), dp(7));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF14263D);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0x443B5664);
        card.setBackground(background);

        TextView name = new TextView(this);
        name.setText(peerAddress);
        name.setTextColor(Color.WHITE);
        name.setTextSize(11);
        name.setMaxLines(1);
        card.addView(name);

        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(Color.BLACK);
        SurfaceViewRenderer renderer = new SurfaceViewRenderer(this);
        renderer.init(eglBase.getEglBaseContext(), null);
        renderer.setEnableHardwareScaler(true);
        renderer.setMirror(false);
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        renderers.put(peerAddress, renderer);
        preview.addView(renderer, new FrameLayout.LayoutParams(-1, -1));

        TextView tileStatus = new TextView(this);
        tileStatus.setText("Waiting for owner approval");
        tileStatus.setTextColor(0xFFE7F1FA);
        tileStatus.setTextSize(11);
        tileStatus.setPadding(dp(8), dp(5), dp(8), dp(5));
        tileStatus.setBackgroundColor(0xAA101820);
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(
                -2, -2, Gravity.BOTTOM | Gravity.START);
        preview.addView(tileStatus, badge);
        tileStatuses.put(peerAddress, tileStatus);

        card.addView(preview, new LinearLayout.LayoutParams(-1, dp(190)));
        card.setOnClickListener(v -> openFullScreen(peerAddress));

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        cardParams.leftMargin = dp(4);
        cardParams.rightMargin = dp(4);
        row.addView(card, cardParams);
    }

    private void bindConnectionService() {
        Intent intent = new Intent(this, ConnectionService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            serviceConnection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    service = ((ConnectionService.LocalBinder) binder).service();
                    peers = service.peers();
                    rtcListener = new RtcPeerManager.Listener() {
                        @Override public void onRemoteVideo(String peer, VideoTrack track) {
                            runOnUiThread(() -> attachTrack(peer, track));
                        }
                        @Override public void onState(String peer, String state) {
                            runOnUiThread(() -> updateTileState(peer, state));
                        }
                        @Override public void onError(String peer, Exception error) {
                            runOnUiThread(() -> updateTileState(
                                    peer, "Stream error: " + error.getMessage()));
                        }
                    };
                    service.rtc().setListener(rtcListener);
                    int connected = 0;
                    for (String peer : peerAddresses) {
                        if (peers != null && peers.session(peer) != null) connected++;
                    }
                    summary.setText(connected + " of " + peerAddresses.size()
                            + " selected devices are connected. Each owner must approve screen access.");
                }

                @Override public void onServiceDisconnected(ComponentName name) {
                    service = null;
                    peers = null;
                    summary.setText("Connection service disconnected.");
                }
            };
            bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        } catch (Exception error) {
            summary.setText("Could not open remote screens: " + error.getMessage());
        }
    }

    private void attachTrack(String peer, VideoTrack track) {
        SurfaceViewRenderer renderer = renderers.get(peer);
        if (renderer == null || track == null) return;
        VideoTrack previous = tracks.put(peer, track);
        VideoSink previousSink = sinks.remove(peer);
        if (previous != null && previousSink != null) {
            try { previous.removeSink(previousSink); } catch (Exception ignored) {}
        }
        VideoSink sink = frame -> {
            SurfaceViewRenderer target = renderers.get(peer);
            if (target != null && frame != null) target.onFrame(frame);
        };
        sinks.put(peer, sink);
        track.addSink(sink);
        updateTileState(peer, "LIVE · tap for control");
    }

    private void updateTileState(String peer, String state) {
        TextView label = tileStatuses.get(peer);
        if (label != null) label.setText(state == null ? "Waiting…" : state);
        if (summary != null) {
            int live = tracks.size();
            summary.setText(live + " live of " + peerAddresses.size()
                    + " selected devices. Tap a screen for full remote control.");
        }
    }

    private void openFullScreen(String peerAddress) {
        handingOffToFullScreen = true;
        startActivity(new Intent(this, RtcViewerActivity.class)
                .putExtra("peer_address", peerAddress));
        finish();
    }

    private void stopAllStreams() {
        stopRequestedStreams();
        finish();
    }

    private void stopRequestedStreams() {
        if (service != null && peers != null) {
            for (String address : peerAddresses) {
                DeviceSession session = peers.session(address);
                if (session != null) {
                    try { service.rtc().requestStop(session); } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override public void onBackPressed() {
        stopAllStreams();
    }

    @Override protected void onDestroy() {
        if (!handingOffToFullScreen) stopRequestedStreams();
        for (Map.Entry<String, VideoTrack> entry : tracks.entrySet()) {
            VideoSink sink = sinks.get(entry.getKey());
            if (sink != null) {
                try { entry.getValue().removeSink(sink); } catch (Exception ignored) {}
            }
        }
        for (SurfaceViewRenderer renderer : renderers.values()) {
            try { renderer.release(); } catch (Exception ignored) {}
        }
        renderers.clear();
        tracks.clear();
        sinks.clear();
        if (service != null && rtcListener != null) service.rtc().clearListener(rtcListener);
        if (serviceConnection != null) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceConnection = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
