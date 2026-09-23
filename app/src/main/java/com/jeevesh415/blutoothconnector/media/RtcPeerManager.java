package com.jeevesh415.blutoothconnector.media;

import android.content.Context;
import android.media.projection.MediaProjection;

import com.jeevesh415.blutoothconnector.control.RemoteInputAccessibilityService;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;

import org.json.JSONObject;
import org.webrtc.IceCandidate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Couples the existing reliable Bluetooth control plane to WebRTC signaling.
 *
 * Design:
 *   Bluetooth RFCOMM = rendezvous/signaling/control
 *   WebRTC SRTP      = real-time media
 *   WebRTC DataChannel = low-latency remote input
 *
 * This avoids putting video/audio through JSON/RFCOMM and therefore keeps
 * large media frames away from the control channel.
 */
public final class RtcPeerManager implements AutoCloseable {
    public interface Listener {
        void onRemoteVideo(String peerAddress, org.webrtc.VideoTrack track);
        void onState(String peerAddress, String state);
        void onError(String peerAddress, Exception error);
    }

    private final Context context;
    private final Listener listener;
    private final Map<String, LowLatencyRtcEngine> engines = new ConcurrentHashMap<>();

    public RtcPeerManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        LowLatencyRtcEngine.initialize(this.context);
    }

    public void startPublisher(DeviceSession session,
                               MediaProjection projection,
                               int width,
                               int height,
                               int fps) {
        final String peer = session.address();
        stop(peer);
        LowLatencyRtcEngine engine = create(session);
        try {
            engine.addMicrophone();
            engine.startScreen(projection, width, height, fps);
            engine.createControlChannel();
            engine.createOffer();
        } catch (Exception e) {
            engines.remove(peer);
            engine.close();
            listener.onError(peer, e);
        }
    }

    public void handle(DeviceSession session, Frame frame) {
        final String peer = session.address();
        try {
            switch (frame.type) {
                case Protocol.RTC_OFFER:
                    LowLatencyRtcEngine receiver = engines.get(peer);
                    if (receiver == null) receiver = create(session);
                    receiver.acceptOffer(frame.payload.getString("sdp"));
                    break;
                case Protocol.RTC_ANSWER:
                    LowLatencyRtcEngine publisher = engines.get(peer);
                    if (publisher == null) throw new IllegalStateException("RTC session not found");
                    publisher.acceptAnswer(frame.payload.getString("sdp"));
                    break;
                case Protocol.RTC_ICE:
                    LowLatencyRtcEngine engine = engines.get(peer);
                    if (engine == null) throw new IllegalStateException("RTC session not found");
                    engine.addIce(
                            frame.payload.optString("sdpMid", null),
                            frame.payload.optInt("sdpMLineIndex", 0),
                            frame.payload.getString("candidate"));
                    break;
                case Protocol.RTC_CONTROL:
                    RemoteInputAccessibilityService accessibility =
                            RemoteInputAccessibilityService.instance();
                    if (accessibility == null) {
                        throw new SecurityException("Remote input service is not enabled by the user");
                    }
                    accessibility.execute(frame.payload.getJSONObject("command"));
                    break;
                case Protocol.RTC_STOP:
                    stop(peer);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            listener.onError(peer, e);
        }
    }

    private LowLatencyRtcEngine create(DeviceSession session) {
        final String peer = session.address();
        LowLatencyRtcEngine engine = new LowLatencyRtcEngine(
                context,
                new LowLatencyRtcEngine.Listener() {
                    @Override public void onLocalOffer(String sdp) {
                        try {
                            send(session, Protocol.RTC_OFFER,
                                    new JSONObject().put("sdp", sdp));
                        } catch (Exception e) {
                            listener.onError(peer, e);
                        }
                    }

                    @Override public void onLocalAnswer(String sdp) {
                        try {
                            send(session, Protocol.RTC_ANSWER,
                                    new JSONObject().put("sdp", sdp));
                        } catch (Exception e) {
                            listener.onError(peer, e);
                        }
                    }

                    @Override public void onIceCandidate(IceCandidate candidate) {
                        try {
                            send(session, Protocol.RTC_ICE,
                                    new JSONObject()
                                            .put("sdpMid", candidate.sdpMid)
                                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                            .put("candidate", candidate.sdp));
                        } catch (Exception e) {
                            listener.onError(peer, e);
                        }
                    }

                    @Override public void onRemoteVideo(org.webrtc.VideoTrack track) {
                        listener.onRemoteVideo(peer, track);
                    }

                    @Override public void onDataMessage(String text) {
                        try {
                            JSONObject command = new JSONObject(text);
                            RemoteInputAccessibilityService accessibility =
                                    RemoteInputAccessibilityService.instance();
                            if (accessibility == null) {
                                listener.onState(peer, "remote-input-not-enabled");
                                return;
                            }
                            accessibility.execute(command);
                        } catch (Exception e) {
                            listener.onError(peer, e);
                        }
                    }

                    @Override public void onState(String state) {
                        listener.onState(peer, state);
                    }

                    @Override public void onError(Exception error) {
                        listener.onError(peer, error);
                    }
                });
        LowLatencyRtcEngine old = engines.put(peer, engine);
        if (old != null) old.close();
        return engine;
    }

    private void send(DeviceSession session, String type, JSONObject payload) {
        try {
            session.connection.send(new Frame(
                    Protocol.VERSION,
                    type,
                    session.nextSequence(),
                    System.currentTimeMillis(),
                    payload));
        } catch (Exception e) {
            listener.onError(session.address(), e);
        }
    }

    public void stop(String peerAddress) {
        LowLatencyRtcEngine engine = engines.remove(peerAddress);
        if (engine != null) engine.close();
    }

    @Override public void close() {
        for (LowLatencyRtcEngine engine : engines.values()) engine.close();
        engines.clear();
    }
}
