package com.jeevesh415.blutoothconnector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.jeevesh415.blutoothconnector.media.RtcPeerManager;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;
import com.jeevesh415.blutoothconnector.transport.MultiDeviceManager;

import org.json.JSONObject;
import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

/**
 * Phone-B viewer/controller.
 *
 * The remote surface is rendered directly by WebRTC. Touch events are sent
 * over the WebRTC DataChannel rather than through the media stream.
 */
public final class RtcViewerActivity extends Activity {
    private SurfaceViewRenderer renderer;
    private TextView status;
    private ConnectionService service;
    private MultiDeviceManager peers;
    private String peerAddress;
    private float downX;
    private float downY;
    private long downAt;

    private final ServiceConnection connection = new ServiceConnection() {
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
                    if (!peer.equals(peerAddress)) return;
                    runOnUiThread(() -> {
                        track.addSink(renderer);
                        status.setText("Live screen: " + peer);
                    });
                }

                @Override public void onState(String peer, String state) {
                    runOnUiThread(() -> status.setText(
                            "Stream " + peer + ": " + state));
                }

                @Override public void onError(String peer, Exception error) {
                    runOnUiThread(() -> status.setText(
                            "Stream error: " + error.getMessage()));
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
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        renderer = new SurfaceViewRenderer(this);
        root.addView(renderer, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setText("Starting viewer…");
        status.setTextSize(14);
        status.setPadding(20, 20, 20, 20);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2));

        setContentView(root);

        EglBase egl = EglBase.create();
        renderer.init(egl.getEglBaseContext(), null);
        renderer.setEnableHardwareScaler(true);
        renderer.setMirror(false);

        renderer.setOnTouchListener(this::handleTouch);

        Intent intent = new Intent(this, ConnectionService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            bindService(intent, connection, BIND_AUTO_CREATE);
        } catch (Exception e) {
            status.setText("Connection service error: " + e.getMessage());
        }
    }

    private boolean handleTouch(View view, MotionEvent event) {
        if (service == null || peerAddress == null) return true;

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
                float scaleX = 1280f / Math.max(1, view.getWidth());
                float scaleY = 720f / Math.max(1, view.getHeight());

                float remoteX = x * scaleX;
                float remoteY = y * scaleY;
                long duration = System.currentTimeMillis() - downAt;

                JSONObject command;
                if (Math.abs(x - downX) < 12 && Math.abs(y - downY) < 12) {
                    command = new JSONObject()
                            .put("type", "tap")
                            .put("x", remoteX)
                            .put("y", remoteY);
                } else {
                    command = new JSONObject()
                            .put("type", "swipe")
                            .put("x1", downX * scaleX)
                            .put("y1", downY * scaleY)
                            .put("x2", remoteX)
                            .put("y2", remoteY)
                            .put("durationMs", Math.max(1, Math.min(2000, duration)));
                }
                service.rtc().sendControl(peerAddress, command);
                return true;
            }
        } catch (Exception e) {
            status.setText("Control error: " + e.getMessage());
        }
        return true;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override protected void onDestroy() {
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        if (service != null) {
            try { unbindService(connection); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
