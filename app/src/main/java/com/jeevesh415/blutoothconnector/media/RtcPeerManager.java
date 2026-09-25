package com.jeevesh415.blutoothconnector.media;

import android.content.Context;
import android.content.Intent;

import com.jeevesh415.blutoothconnector.control.RemoteControlAuthorization;
import com.jeevesh415.blutoothconnector.control.RemoteInputAccessibilityService;
import com.jeevesh415.blutoothconnector.protocol.ControlReplayGuard;
import com.jeevesh415.blutoothconnector.protocol.Frame;
import com.jeevesh415.blutoothconnector.protocol.Protocol;
import com.jeevesh415.blutoothconnector.transport.DeviceSession;

import org.json.JSONObject;
import org.webrtc.IceCandidate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Couples Bluetooth RFCOMM signaling to WebRTC real-time media.
 *
 * Remote input is an explicitly authorized capability. Pairing alone does
 * not grant Accessibility-backed control.
 */
public final class RtcPeerManager implements AutoCloseable {
    private static final int CONTROL_VERSION = 3;
    private static final int MAX_CONTROL_BYTES = 8192;

    public interface Listener {
        void onRemoteVideo(String peerAddress, org.webrtc.VideoTrack track);
        void onState(String peerAddress, String state);
        void onError(String peerAddress, Exception error);
    }

    private final Context context;
    private volatile Listener listener;
    private final Map<String, LowLatencyRtcEngine> engines =
            new ConcurrentHashMap<>();
    private final Map<String, org.webrtc.VideoTrack> lastRemoteVideos =
            new ConcurrentHashMap<>();
    private final Map<String, ControlReplayGuard> inboundControl =
            new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> outboundControl =
            new ConcurrentHashMap<>();
    private final Map<String, String> sessionIds =
            new ConcurrentHashMap<>();

    public RtcPeerManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        LowLatencyRtcEngine.initialize(this.context);
    }

    public void setListener(Listener listener) {
        if (listener == null) return;
        this.listener = listener;
        for (Map.Entry<String, org.webrtc.VideoTrack> entry : lastRemoteVideos.entrySet()) {
            try {
                listener.onRemoteVideo(entry.getKey(), entry.getValue());
            } catch (Exception ignored) {}
        }
    }

    public void sendControl(String peerAddress, JSONObject command) {
        if (command == null) throw new IllegalArgumentException("command");
        LowLatencyRtcEngine engine = engines.get(peerAddress);
        if (engine == null) throw new IllegalStateException("RTC session not found");

        AtomicLong sequence = outboundControl.computeIfAbsent(
                peerAddress, ignored -> new AtomicLong());
        String sessionId = sessionIds.get(peerAddress);
        if (sessionId == null) {
            throw new IllegalStateException("RTC session has no session identifier");
        }
        long seq = sequence.incrementAndGet();

        final String text;
        try {
            JSONObject envelope = new JSONObject()
                    .put("v", CONTROL_VERSION)
                    .put("sid", sessionId)
                    .put("seq", seq)
                    .put("command", command);
            text = envelope.toString();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Could not encode control message", error);
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTROL_BYTES) {
            throw new IllegalArgumentException("Control message too large");
        }
        engine.sendControl(text);
    }

    public void startPublisher(DeviceSession session,
                               Intent projectionData,
                               int width,
                               int height,
                               int fps) {
        final String peer = session.address();
        stop(peer);
        String sessionId = java.util.UUID.randomUUID().toString();
        sessionIds.put(peer, sessionId);
        outboundControl.put(peer, new AtomicLong());
        inboundControl.put(peer, new ControlReplayGuard());

        LowLatencyRtcEngine engine = create(session, sessionId);
        try {
            engine.addMicrophone();
            engine.startScreen(projectionData, width, height, fps);
            engine.createControlChannel();
            engine.createOffer();
        } catch (Exception error) {
            engines.remove(peer, engine);
            engine.close();
            listener.onError(peer, error);
        }
    }

    public void handle(DeviceSession session, Frame frame) {
        final String peer = session.address();
        try {
            switch (frame.type) {
                case Protocol.RTC_OFFER:
                    String offerSessionId = requireSessionId(frame);
                    LowLatencyRtcEngine receiver = engines.get(peer);
                    if (receiver == null) {
                        sessionIds.put(peer, offerSessionId);
                        inboundControl.put(peer, new ControlReplayGuard());
                        outboundControl.put(peer, new AtomicLong());
                        receiver = create(session, offerSessionId);
                    } else if (!offerSessionId.equals(sessionIds.get(peer))) {
                        throw new SecurityException("RTC session identifier mismatch");
                    }
                    receiver.acceptOffer(frame.payload.getString("sdp"));
                    break;
                case Protocol.RTC_ANSWER:
                    requireSessionMatch(peer, frame);
                    LowLatencyRtcEngine publisher = engines.get(peer);
                    if (publisher == null) {
                        throw new IllegalStateException("RTC session not found");
                    }
                    publisher.acceptAnswer(frame.payload.getString("sdp"));
                    break;
                case Protocol.RTC_ICE:
                    requireSessionMatch(peer, frame);
                    LowLatencyRtcEngine engine = engines.get(peer);
                    if (engine == null) {
                        throw new IllegalStateException("RTC session not found");
                    }
                    engine.addIce(
                            frame.payload.optString("sdpMid", null),
                            frame.payload.optInt("sdpMLineIndex", 0),
                            frame.payload.getString("candidate"));
                    break;
                case Protocol.RTC_CONTROL:
                    handleControlEnvelope(
                            peer,
                            frame.payload.toString());
                    break;
                case Protocol.RTC_STOP:
                    requireSessionMatch(peer, frame);
                    stop(peer);
                    break;
                default:
                    break;
            }
        } catch (Exception error) {
            listener.onError(peer, error);
        }
    }

    private void handleControlEnvelope(String peer, String text) throws Exception {
        if (text == null
                || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTROL_BYTES) {
            throw new SecurityException("Control message rejected");
        }
        if (!isRemoteControlAuthorized(peer)) {
            throw new SecurityException("Remote control is not authorized for this peer");
        }

        JSONObject envelope = new JSONObject(text);
        if (envelope.optInt("v", -1) != CONTROL_VERSION) {
            throw new SecurityException("Unsupported control envelope version");
        }

        String sid = envelope.optString("sid", "");
        if (!sid.equals(sessionIds.get(peer))) {
            throw new SecurityException("RTC session identifier mismatch");
        }

        long sequence = envelope.optLong("seq", 0);
        ControlReplayGuard guard = inboundControl.computeIfAbsent(
                peer, ignored -> new ControlReplayGuard());
        if (!guard.accept(sequence)) {
            throw new SecurityException("Replay or out-of-order control message rejected");
        }

        JSONObject command = envelope.optJSONObject("command");
        if (command == null) {
            throw new SecurityException("Missing control command");
        }

        RemoteInputAccessibilityService accessibility =
                RemoteInputAccessibilityService.instance();
        if (accessibility == null) {
            throw new SecurityException(
                    "Remote input service is not enabled by the user");
        }
        if (!accessibility.execute(command)) {
            throw new SecurityException("Remote input command was rejected");
        }
    }

    private String requireSessionId(Frame frame) throws Exception {
        String sid = frame.payload.optString("sid", "");
        if (sid.length() < 8 || sid.length() > 64) {
            throw new SecurityException("Missing or invalid RTC session identifier");
        }
        return sid;
    }

    private void requireSessionMatch(String peer, Frame frame) throws Exception {
        String sid = requireSessionId(frame);
        String expected = sessionIds.get(peer);
        if (expected == null || !sid.equals(expected)) {
            throw new SecurityException("RTC session identifier mismatch");
        }
    }

    private boolean isRemoteControlAuthorized(String peerAddress) {
        return RemoteInputAccessibilityService.instance() != null
                && RemoteControlAuthorization.isAuthorized(context, peerAddress);
    }

    private LowLatencyRtcEngine create(
            DeviceSession session, String sessionId) {
        final String peer = session.address();
        final LowLatencyRtcEngine engine = new LowLatencyRtcEngine(
                context,
                new LowLatencyRtcEngine.Listener() {
                    @Override public void onLocalOffer(String sdp) {
                        try {
                            send(session, Protocol.RTC_OFFER,
                                    new JSONObject()
                                            .put("sid", sessionId)
                                            .put("sdp", sdp));
                        } catch (Exception error) {
                            listener.onError(peer, error);
                        }
                    }

                    @Override public void onLocalAnswer(String sdp) {
                        try {
                            send(session, Protocol.RTC_ANSWER,
                                    new JSONObject()
                                            .put("sid", sessionId)
                                            .put("sdp", sdp));
                        } catch (Exception error) {
                            listener.onError(peer, error);
                        }
                    }

                    @Override public void onIceCandidate(IceCandidate candidate) {
                        try {
                            send(session, Protocol.RTC_ICE,
                                    new JSONObject()
                                            .put("sid", sessionId)
                                            .put("sdpMid", candidate.sdpMid)
                                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                            .put("candidate", candidate.sdp));
                        } catch (Exception error) {
                            listener.onError(peer, error);
                        }
                    }

                    @Override public void onRemoteVideo(org.webrtc.VideoTrack track) {
                        lastRemoteVideos.put(peer, track);
                        try {
                            listener.onRemoteVideo(peer, track);
                        } catch (Exception error) {
                            listener.onError(peer, error);
                        }
                    }

                    @Override public void onDataMessage(String text) {
                        try {
                            handleControlEnvelope(peer, text);
                        } catch (Exception error) {
                            listener.onError(peer, error);
                        }
                    }

                    @Override public void onState(String state) {
                        listener.onState(peer, state);
                        if ("mediaProjection:stopped".equals(state)) {
                            try {
                                send(session, Protocol.RTC_STOP,
                                        new JSONObject().put("sid", sessionId));
                            } catch (org.json.JSONException error) {
                                listener.onError(peer, error);
                            }
                            stop(peer);
                        } else if ("peer:FAILED".equals(state)
                                || "peer:CLOSED".equals(state)
                                || "ice:FAILED".equals(state)) {
                            stop(peer);
                        }
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
        } catch (Exception error) {
            listener.onError(session.address(), error);
        }
    }

    /**
     * Request the remote peer to terminate this RTC session, then close the
     * local engine. Used by the viewer lifecycle so projection resources do
     * not remain active after the UI is closed.
     */
    public void requestStop(DeviceSession session) {
        if (session == null) return;
        String peer = session.address();
        String sid = sessionIds.get(peer);
        if (sid != null) {
            send(
                    session,
                    Protocol.RTC_STOP,
                    new JSONObject().put("sid", sid));
        }
        stop(peer);
    }

    public void stop(String peerAddress) {
        LowLatencyRtcEngine engine = engines.remove(peerAddress);
        if (engine != null) {
            engine.close();
            lastRemoteVideos.remove(peerAddress);
        }
        sessionIds.remove(peerAddress);
        inboundControl.remove(peerAddress);
        outboundControl.remove(peerAddress);
    }

    @Override public void close() {
        for (LowLatencyRtcEngine engine : engines.values()) {
            engine.close();
        }
        engines.clear();
        lastRemoteVideos.clear();
        inboundControl.clear();
        outboundControl.clear();
        sessionIds.clear();
    }
}
