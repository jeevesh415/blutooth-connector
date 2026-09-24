package com.jeevesh415.blutoothconnector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PointF;
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
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

/**
 * Phone-B viewer/controller.
 *
 * The remote surface is rendered by WebRTC. Touch events use the actual
 * received frame dimensions and account for aspect-fit letterboxing.
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

    private EglBase eglBase;
    private VideoTrack activeTrack;
    private VideoSink activeSink;
    private volatile int remoteWidth = 1280;
    private volatile int remoteHeight = 720;

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
                    if (!peer.equals(peerAddress) || renderer == null) return;
                    runOnUiThread(() -> attachTrack(track, peer));
                }

                @Override public void onState(String peer, String state) {
                    runOnUiThread(() -> {
                        if (status != null) {
                            status.setText("Stream " + peer + ": " + state);
                        }
                    });
                }

                @Override public void onError(String peer, Exception error) {
                    runOnUiThread(() -> {
                        if (status != null) {
                            status.setText(
                                    "Stream error: "
                                            + (error == null
                                                    ? "unknown"
                                                    : error.getMessage()));
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

        eglBase = EglBase.create();
        renderer.init(eglBase.getEglBaseContext(), null);
        renderer.setEnableHardwareScaler(true);
        renderer.setMirror(false);
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        renderer.setOnTouchListener(this::handleTouch);

        Intent intent = new Intent(this, ConnectionService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            bindService(intent, connection, BIND_AUTO_CREATE);
        } catch (Exception error) {
            status.setText("Connection service error: " + error.getMessage());
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
        status.setText("Live screen: " + peer);
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
            status.setText("Control error: " + error.getMessage());
        }
        return true;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override protected void onDestroy() {
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
        if (service != null) {
            try { unbindService(connection); } catch (Exception ignored) {}
        }
        service = null;
        peers = null;
        super.onDestroy();
    }
}
