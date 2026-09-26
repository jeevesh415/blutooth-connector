package com.jeevesh415.blutoothconnector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jeevesh415.blutoothconnector.media.RtcPeerManager;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;

import org.json.JSONObject;
import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

/**
 * Full-screen remote viewer/controller.
 *
 * The remote screen remains the primary surface. A restrained video-call style
 * overlay exposes the peer, live-stream state and explicit control actions
 * without hiding the screen being controlled.
 */
public final class RtcViewerActivity extends Activity {
    private static final int ACCENT = 0xFF58D6FF;
    private static final int LIVE = 0xFF5BE38A;
    private static final int DANGER = 0xFFFF6B6B;

    private SurfaceViewRenderer renderer;
    private TextView status;
    private TextView connectionBadge;
    private TextView liveDot;
    private CheckBox controlToggle;
    private ServiceConnection connection;
    private ConnectionService service;
    private MultiDeviceManager peers;
    private String peerAddress;
    private float downX;
    private float downY;
    private long downAt;

    private EglBase eglBase;
    private VideoTrack activeTrack;
    private VideoSink activeSink;
    private volatile int remoteWidth = 1280;
    private volatile int remoteHeight = 720;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        renderer = new SurfaceViewRenderer(this);
        root.addView(renderer, new FrameLayout.LayoutParams(-1, -1));

        // Small peer card, similar to a video-call participant indicator.
        LinearLayout peerCard = new LinearLayout(this);
        peerCard.setOrientation(LinearLayout.VERTICAL);
        peerCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        peerCard.setBackground(roundBackground(0xCC101820, dp(18), 0x443B5664));

        TextView eyebrow = label("REMOTE DEVICE", 10, 0xFF8EA7B4);
        eyebrow.setLetterSpacing(0.14f);
        peerCard.addView(eyebrow);

        connectionBadge = label("Connecting…", 13, Color.WHITE);
        peerCard.addView(connectionBadge);

        LinearLayout.LayoutParams peerParams =
                new LinearLayout.LayoutParams(-2, -2);
        peerParams.topMargin = dp(4);
        peerCard.addView(label("SCREEN + CONTROL", 10, ACCENT), peerParams);

        FrameLayout.LayoutParams peerCardParams =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        peerCardParams.setMargins(dp(16), dp(24), dp(16), 0);
        root.addView(peerCard, peerCardParams);

        // Live status pill.
        LinearLayout livePill = new LinearLayout(this);
        livePill.setGravity(Gravity.CENTER_VERTICAL);
        livePill.setPadding(dp(12), dp(7), dp(12), dp(7));
        livePill.setBackground(roundBackground(0xCC101820, dp(20), 0x443B5664));

        liveDot = label("●", 10, LIVE);
        livePill.addView(liveDot);

        status = label("Starting secure stream…", 11, Color.WHITE);
        LinearLayout.LayoutParams statusTextParams =
                new LinearLayout.LayoutParams(-2, -2);
        statusTextParams.leftMargin = dp(6);
        livePill.addView(status, statusTextParams);

        FrameLayout.LayoutParams liveParams =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        liveParams.topMargin = dp(28);
        root.addView(livePill, liveParams);

        // Compact vertical action palette: easy to reach without covering the stream.
        LinearLayout palette = new LinearLayout(this);
        palette.setOrientation(LinearLayout.VERTICAL);
        palette.setGravity(Gravity.CENTER);
        palette.setPadding(dp(8), dp(8), dp(8), dp(8));
        palette.setBackground(roundBackground(0xD9101820, dp(24), 0x55445B67));

        controlToggle = new CheckBox(this);
        controlToggle.setText("CONTROL");
        controlToggle.setTextColor(Color.WHITE);
        controlToggle.setTextSize(10);
        controlToggle.setGravity(Gravity.CENTER);
        controlToggle.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        controlToggle.setChecked(true);
        controlToggle.setPadding(0, 0, 0, dp(5));
        palette.addView(controlToggle);

        Button back = actionButton("‹", "Back");
        back.setOnClickListener(v -> sendGlobal("back"));
        palette.addView(back);

        Button home = actionButton("●", "Home");
        home.setOnClickListener(v -> sendGlobal("home"));
        palette.addView(home);

        Button recents = actionButton("▣", "Recent");
        recents.setOnClickListener(v -> sendGlobal("recents"));
        palette.addView(recents);

        Button lock = actionButton("⌁", "Lock");
        lock.setOnClickListener(v -> sendGlobal("lock"));
        palette.addView(lock);

        FrameLayout.LayoutParams paletteParams =
                new FrameLayout.LayoutParams(dp(76), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        paletteParams.setMargins(0, 0, dp(14), 0);
        root.addView(palette, paletteParams);

        // Bottom call-control rail: stream stop and exit stay visually separate from input controls.
        LinearLayout rail = new LinearLayout(this);
        rail.setGravity(Gravity.CENTER_VERTICAL);
        rail.setPadding(dp(10), dp(9), dp(10), dp(9));
        rail.setBackground(roundBackground(0xE6101820, dp(24), 0x55445B67));

        Button stop = actionButton("■", "Stop");
        stop.setOnClickListener(v -> stopRemoteStream());
        rail.addView(stop, railParams(0, 1f));

        TextView hint = label("Tap or drag the live screen", 10, 0xFF9FB0B8);
        hint.setGravity(Gravity.CENTER);
        rail.addView(hint, railParams(0, 2.5f));

        Button disconnect = actionButton("×", "Close");
        disconnect.setOnClickListener(v -> finish());
        rail.addView(disconnect, railParams(0, 1f));

        FrameLayout.LayoutParams railLayout =
                new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        railLayout.setMargins(dp(12), 0, dp(12), dp(16));
        root.addView(rail, railLayout);

        setContentView(root);

        eglBase = EglBase.create();
        renderer.init(eglBase.getEglBaseContext(), null);
        renderer.setEnableHardwareScaler(true);
        renderer.setMirror(false);
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        renderer.setOnTouchListener(this::handleTouch);

        bindConnectionService();
    }

    private void bindConnectionService() {
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                ConnectionService.LocalBinder local =
                        (ConnectionService.LocalBinder) binder;
                service = local.service();
                peers = service.peers();
                if (peers != null && !peers.sessions().isEmpty()) {
                    peerAddress = peers.sessions().iterator().next().address();
                }

                service.rtc().setListener(new RtcPeerManager.Listener() {
                    @Override public void onRemoteVideo(String peer, VideoTrack track) {
                        if (peerAddress == null) peerAddress = peer;
                        if (!peer.equals(peerAddress) || renderer == null) return;
                        runOnUiThread(() -> attachTrack(track, peer));
                    }

                    @Override public void onState(String peer, String state) {
                        runOnUiThread(() -> updateState(peer, state));
                    }

                    @Override public void onError(String peer, Exception error) {
                        runOnUiThread(() -> {
                            if (connectionBadge != null) {
                                connectionBadge.setText(peer + "  •  ERROR");
                            }
                            if (liveDot != null) liveDot.setTextColor(DANGER);
                            if (status != null) {
                                status.setText("Stream error");
                            }
                        });
                    }
                });

                status.setText(
                        peerAddress == null
                                ? "Waiting for a connected publisher…"
                                : "Waiting for screen stream…");
            }

            @Override public void onServiceDisconnected(ComponentName name) {
                service = null;
                peers = null;
                if (connectionBadge != null) connectionBadge.setText("Disconnected");
                if (liveDot != null) liveDot.setTextColor(DANGER);
            }
        };

        Intent intent = new Intent(this, ConnectionService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            bindService(intent, connection, BIND_AUTO_CREATE);
        } catch (Exception error) {
            status.setText("Connection service error");
        }
    }

    private void updateState(String peer, String state) {
        if (connectionBadge != null) connectionBadge.setText(peer + "  •  " + state);
        if (status != null) status.setText(state);
        if (liveDot != null) {
            liveDot.setTextColor(
                    state != null && state.toUpperCase().contains("LIVE") ? LIVE : ACCENT);
        }
    }

    private void attachTrack(VideoTrack track, String peer) {
        if (renderer == null) return;
        if (activeTrack != null && activeSink != null) {
            try { activeTrack.removeSink(activeSink); } catch (Exception ignored) {}
        }

        activeTrack = track;
        activeSink = frame -> {
            if (frame == null || renderer == null) return;
            remoteWidth = Math.max(1, frame.getRotatedWidth());
            remoteHeight = Math.max(1, frame.getRotatedHeight());
            renderer.onFrame(frame);
        };
        track.addSink(activeSink);

        connectionBadge.setText(peer + "  •  LIVE");
        liveDot.setTextColor(LIVE);
        status.setText("Screen live  •  control ready");
    }

    private PointF mapToRemote(View view, float x, float y) {
        float viewWidth = Math.max(1, view.getWidth());
        float viewHeight = Math.max(1, view.getHeight());
        float rw = Math.max(1, remoteWidth);
        float rh = Math.max(1, remoteHeight);
        float aspect = rw / rh;

        float contentWidth = viewWidth;
        float contentHeight = viewWidth / aspect;
        if (contentHeight > viewHeight) {
            contentHeight = viewHeight;
            contentWidth = viewHeight * aspect;
        }

        float left = (viewWidth - contentWidth) / 2f;
        float top = (viewHeight - contentHeight) / 2f;
        float nx = clamp((x - left) / Math.max(1f, contentWidth), 0f, 1f);
        float ny = clamp((y - top) / Math.max(1f, contentHeight), 0f, 1f);
        return new PointF(nx * rw, ny * rh);
    }

    private boolean handleTouch(View view, MotionEvent event) {
        if (service == null || peerAddress == null) return true;
        if (controlToggle != null && !controlToggle.isChecked()) return true;

        float x = clamp(event.getX(), 0, view.getWidth());
        float y = clamp(event.getY(), 0, view.getHeight());

        try {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = x;
                downY = y;
                downAt = System.currentTimeMillis();
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                PointF start = mapToRemote(view, downX, downY);
                PointF end = mapToRemote(view, x, y);
                long duration = System.currentTimeMillis() - downAt;

                JSONObject command;
                if (Math.abs(x - downX) < 12 && Math.abs(y - downY) < 12) {
                    command = new JSONObject()
                            .put("type", "tap")
                            .put("x", end.x)
                            .put("y", end.y);
                } else {
                    command = new JSONObject()
                            .put("type", "swipe")
                            .put("x1", start.x)
                            .put("y1", start.y)
                            .put("x2", end.x)
                            .put("y2", end.y)
                            .put("durationMs", Math.max(1, Math.min(2000, duration)));
                }
                service.rtc().sendControl(peerAddress, command);
                return true;
            }
        } catch (Exception error) {
            status.setText("Control error");
        }
        return true;
    }

    private void sendGlobal(String type) {
        if (service == null || peerAddress == null) {
            status.setText("Peer is not connected");
            return;
        }
        if (controlToggle != null && !controlToggle.isChecked()) {
            status.setText("Turn CONTROL on first");
            return;
        }
        try {
            service.rtc().sendControl(
                    peerAddress,
                    new JSONObject().put("type", type));
            status.setText("Sent " + type);
        } catch (Exception error) {
            status.setText("Control error");
        }
    }

    private void stopRemoteStream() {
        if (service == null || peers == null || peerAddress == null) {
            if (status != null) status.setText("No active remote stream");
            return;
        }
        try {
            DeviceSession session = peers.session(peerAddress);
            if (session == null) {
                status.setText("Peer is no longer connected");
                return;
            }
            service.rtc().requestStop(session);
            status.setText("Remote stream stopped");
        } catch (Exception error) {
            status.setText("Could not stop stream");
        }
    }

    private Button actionButton(String icon, String label) {
        Button b = new Button(this);
        b.setText(icon + "\n" + label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(6), 0, dp(6));
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setBackground(roundBackground(0x332C3C45, dp(15), 0x334F6670));
        return b;
    }

    private LinearLayout.LayoutParams railParams(int width, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, -2);
        p.weight = weight;
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private TextView label(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(size);
        return v;
    }

    private GradientDrawable roundBackground(int color, float radiusPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override protected void onDestroy() {
        if (service != null && peers != null && peerAddress != null) {
            try { stopRemoteStream(); } catch (Exception ignored) {}
        }
        if (activeTrack != null && activeSink != null) {
            try { activeTrack.removeSink(activeSink); } catch (Exception ignored) {}
        }
        activeTrack = null;
        activeSink = null;

        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        if (service != null && connection != null) {
            try { unbindService(connection); } catch (Exception ignored) {}
        }
        service = null;
        peers = null;
        super.onDestroy();
    }
}
