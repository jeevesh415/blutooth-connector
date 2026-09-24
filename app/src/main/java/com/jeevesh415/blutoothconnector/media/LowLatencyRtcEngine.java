package com.jeevesh415.blutoothconnector.media;

import android.content.Context;
import android.content.Intent;
import android.view.Surface;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-latency media core.
 *
 * Transport choice:
 * - WebRTC SRTP for real-time audio/video.
 * - DataChannel for interactive control and telemetry.
 * - Existing Bluetooth control plane can be used as the signaling channel.
 *
 * This class deliberately does not hide MediaProjection consent behind an API.
 * Android requires explicit user consent for each projection session.
 */
public final class LowLatencyRtcEngine implements AutoCloseable {
    public interface Listener {
        void onLocalOffer(String sdp);
        void onLocalAnswer(String sdp);
        void onIceCandidate(IceCandidate candidate);
        void onRemoteVideo(VideoTrack track);
        void onDataMessage(String text);
        void onState(String state);
        void onError(Exception error);
    }

    private final Context context;
    private final Listener listener;
    private final EglBase eglBase;
    private final PeerConnectionFactory factory;
    private final PeerConnection peer;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoSource videoSource;
    private VideoTrack videoTrack;
    private SurfaceTextureHelper surfaceTextureHelper;
    private org.webrtc.VideoCapturer screenCapturer;
    private DataChannel controlChannel;

    public static void initialize(Context context) {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions());
    }

    public LowLatencyRtcEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initialize(this.context);

        eglBase = EglBase.create();
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(
                        new org.webrtc.DefaultVideoEncoderFactory(
                                eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(
                        new org.webrtc.DefaultVideoDecoderFactory(
                                eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        List<PeerConnection.IceServer> iceServers =
                Collections.singletonList(
                        PeerConnection.IceServer.builder(
                                "stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration configuration =
                new PeerConnection.RTCConfiguration(iceServers);
        configuration.sdpSemantics =
                PeerConnection.SdpSemantics.UNIFIED_PLAN;
        configuration.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peer = factory.createPeerConnection(
                configuration,
                new PeerConnection.Observer() {
                    @Override public void onIceCandidate(IceCandidate candidate) {
                        listener.onIceCandidate(candidate);
                    }

                    @Override public void onAddStream(MediaStream stream) {}

                    @Override public void onRemoveStream(MediaStream stream) {}

                    @Override public void onDataChannel(DataChannel channel) {
                        controlChannel = channel;
                        channel.registerObserver(new DataChannel.Observer() {
                            @Override public void onBufferedAmountChange(long amount) {}

                            @Override public void onStateChange() {}

                            @Override public void onMessage(DataChannel.Buffer buffer) {
                                try {
                                    java.nio.ByteBuffer data = buffer.data;
                                    byte[] bytes = new byte[data.remaining()];
                                    data.get(bytes);
                                    listener.onDataMessage(
                                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                                } catch (Exception e) {
                                    listener.onError(e);
                                }
                            }
                        });
                    }

                    @Override public void onSignalingChange(
                            PeerConnection.SignalingState state) {}

                    @Override public void onIceConnectionChange(
                            PeerConnection.IceConnectionState state) {
                        listener.onState("ice:" + state);
                    }

                    @Override public void onIceConnectionReceivingChange(
                            boolean receiving) {}

                    @Override public void onIceGatheringChange(
                            PeerConnection.IceGatheringState state) {}

                    @Override public void onConnectionChange(
                            PeerConnection.PeerConnectionState state) {
                        listener.onState("peer:" + state);
                    }

                    @Override public void onStandardizedIceConnectionChange(
                            PeerConnection.IceConnectionState state) {}



                    @Override public void onRenegotiationNeeded() {}

                    @Override public void onTrack(RtpTransceiver transceiver) {
                        RtpReceiver receiver = transceiver.getReceiver();
                        if (receiver == null) return;
                        org.webrtc.MediaStreamTrack track = receiver.track();
                        if (track instanceof VideoTrack) {
                            listener.onRemoteVideo((VideoTrack) track);
                        }
                    }

                    @Override public void onIceCandidatesRemoved(
                            IceCandidate[] candidates) {}

                });

        if (peer == null) {
            throw new IllegalStateException("Unable to create WebRTC peer connection");
        }
    }

    public EglBase.Context eglContext() {
        return eglBase.getEglBaseContext();
    }

    public synchronized void addMicrophone() {
        if (audioTrack != null) return;
        MediaConstraints constraints = new MediaConstraints();
        audioSource = factory.createAudioSource(constraints);
        audioTrack = factory.createAudioTrack("mic", audioSource);
        peer.addTrack(audioTrack);
    }

    /**
     * Capture the Android display through the user-authorized MediaProjection token.
     * Hardware encoding is selected by WebRTC's encoder factory when available.
     */
    public synchronized void startScreen(Intent projectionData,
                                          int width,
                                          int height,
                                          int fps) {
        if (screenCapturer != null) return;
        if (projectionData == null) throw new IllegalArgumentException("projectionData");

        videoSource = factory.createVideoSource(true);
        surfaceTextureHelper = SurfaceTextureHelper.create(
                "BCL-screen-capture",
                eglBase.getEglBaseContext());

        screenCapturer = new org.webrtc.ScreenCapturerAndroid(
                projectionData,
                new android.media.projection.MediaProjection.Callback() {
                    @Override public void onStop() {
                        releaseScreenCaptureAfterProjectionStop();
                        listener.onState("mediaProjection:stopped");
                    }
                });

        screenCapturer.initialize(
                surfaceTextureHelper,
                context,
                videoSource.getCapturerObserver());
        screenCapturer.startCapture(width, height, fps);

        videoTrack = factory.createVideoTrack("screen", videoSource);
        peer.addTrack(videoTrack);
    }

    public synchronized void createControlChannel() {
        if (controlChannel != null) return;
        controlChannel = peer.createDataChannel(
                "control",
                new DataChannel.Init());
        controlChannel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long amount) {}

            @Override public void onStateChange() {}

            @Override public void onMessage(DataChannel.Buffer buffer) {
                try {
                    java.nio.ByteBuffer data = buffer.data;
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    listener.onDataMessage(
                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) {
                    listener.onError(e);
                }
            }
        });
    }

    public synchronized void sendControl(String message) {
        if (controlChannel == null
                || controlChannel.state() != DataChannel.State.OPEN) {
            throw new IllegalStateException("control channel not open");
        }
        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        controlChannel.send(
                new DataChannel.Buffer(
                        java.nio.ByteBuffer.wrap(bytes), false));
    }

    public synchronized void createOffer() {
        peer.createOffer(new SdpObserverAdapter() {
            @Override public void onCreateSuccess(SessionDescription description) {
                peer.setLocalDescription(new SdpObserverAdapter() {
                    @Override public void onSetSuccess() {
                        listener.onLocalOffer(description.description);
                    }
                    @Override public void onSetFailure(String error) {
                        listener.onError(new IllegalStateException(error));
                    }
                }, description);
            }
            @Override public void onCreateFailure(String error) {
                listener.onError(new IllegalStateException(error));
            }
        }, new MediaConstraints());
    }

    public synchronized void acceptOffer(String sdp) {
        peer.setRemoteDescription(new SdpObserverAdapter() {
            @Override public void onSetSuccess() {
                peer.createAnswer(new SdpObserverAdapter() {
                    @Override public void onCreateSuccess(SessionDescription description) {
                        peer.setLocalDescription(new SdpObserverAdapter() {
                            @Override public void onSetSuccess() {
                                listener.onLocalAnswer(description.description);
                            }
                            @Override public void onSetFailure(String error) {
                                listener.onError(new IllegalStateException(error));
                            }
                        }, description);
                    }
                    @Override public void onCreateFailure(String error) {
                        listener.onError(new IllegalStateException(error));
                    }
                }, new MediaConstraints());
            }
            @Override public void onSetFailure(String error) {
                listener.onError(new IllegalStateException(error));
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }

    public synchronized void acceptAnswer(String sdp) {
        peer.setRemoteDescription(new SdpObserverAdapter() {
            @Override public void onSetSuccess() {
                listener.onState("remote-answer-applied");
            }
            @Override public void onSetFailure(String error) {
                listener.onError(new IllegalStateException(error));
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    public synchronized void addIce(String sdpMid, int sdpMLineIndex, String candidate) {
        peer.addIceCandidate(new IceCandidate(sdpMid, sdpMLineIndex, candidate));
    }

    private synchronized void releaseScreenCaptureAfterProjectionStop() {
        screenCapturer = null;
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
        if (videoTrack != null) {
            videoTrack.dispose();
            videoTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (screenCapturer != null) screenCapturer.stopCapture();
        } catch (Exception ignored) {}
        if (surfaceTextureHelper != null) surfaceTextureHelper.dispose();
        if (peer != null) peer.close();
        if (videoTrack != null) videoTrack.dispose();
        if (audioTrack != null) audioTrack.dispose();
        if (videoSource != null) videoSource.dispose();
        if (audioSource != null) audioSource.dispose();
        if (factory != null) factory.dispose();
        eglBase.release();
    }

    private abstract static class SdpObserverAdapter implements org.webrtc.SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
