# Blutooth Connector validation matrix

Automated CI can prove source compatibility, JVM protocol/crypto behavior, persistence, and build assembly. Radio coexistence, real media latency, hardware codec selection, and Android permission UX require physical devices.

## Baseline

Use two Android phones with the same app build. Pair them in Android Bluetooth settings first. Keep the app running in the foreground during the first validation pass.

Record for each run:

- phone model and SoC
- Android release/API level
- Wi-Fi chipset mode/band
- app version and commit SHA
- screen resolution/refresh rate
- measured command RTT/p95
- media resolution/FPS
- observed audio/video stalls
- transfer throughput per path and aggregate
- battery/thermal behavior

## Control-plane test

1. Enable Bluetooth.
2. Start the receiver service on both phones.
3. On the controller, scan paired devices and connect.
4. Verify both sides reach an authenticated session.
5. Verify capabilities arrive only after authentication.
6. Reconnect after a Bluetooth disconnect and verify a fresh nonce/authentication exchange.
7. Change the receiver's application identity key only by reinstalling/clearing app data; verify a previously pinned peer is rejected rather than silently trusted.

## Remote-control test

1. On the receiver, explicitly enable this app's AccessibilityService.
2. Explicitly authorize the controller peer in the app.
3. Start a screen stream.
4. Verify tap/swipe coordinates remain correct in portrait and landscape/aspect-fit display.
5. Verify back/home/recents/text commands.
6. Revoke authorization and verify subsequent commands are rejected.
7. Close the viewer and verify the publisher receives a stop request.
8. Stop MediaProjection from Android system UI and verify the RTC session terminates.

## Local-network and Wi-Fi Direct test

1. Grant Nearby Wi-Fi devices permission on Android 13+.
2. On Android 17/API 37+, grant local-network access when prompted.
3. Start Wi-Fi Direct discovery on both phones.
4. Connect the peer and verify a wifi-direct endpoint is advertised.
5. Transfer a large file and compare single-path vs available multipath throughput.
6. Disable one network path during a transfer and verify affected chunks are retried/resumed.

## Wi-Fi Aware test

1. Use devices that report FEATURE_WIFI_AWARE.
2. Grant Nearby Wi-Fi and local-network permissions as applicable.
3. Start Wi-Fi Aware on both phones.
4. Verify discovery occurs.
5. Verify the Aware PMK is derived from authenticated BCL3 tokens exchanged over Bluetooth.
6. Verify an Aware socket is usable for the advertised bulk endpoint.
7. Disable Aware and verify the rest of the connection stack remains functional.

## Media test

1. Grant microphone permission.
2. Approve the MediaProjection prompt.
3. Start screen + microphone streaming.
4. Verify video renders on the receiver.
5. Measure capture-to-display latency using an external timestamp/LED method.
6. Exercise touch control while the video is moving.
7. Record packet loss/jitter/bitrate statistics if available.
8. Test multiple orientations.
9. Repeat on devices with different hardware codecs.

System playback capture is intentionally not part of this build. Android playback capture has additional eligibility and consent requirements and requires a dedicated audio-device integration; do not treat microphone samples as system playback audio.

## Negative/security tests

- Alter a BCL3 authorization tag: chunk must be rejected.
- Alter a chunk ciphertext: GCM authentication must fail.
- Reuse a completed control sequence: command must be rejected.
- Send a control command for the wrong RTC session ID: reject.
- Send an unauthorized Accessibility command: reject.
- Send a second HELLO with changed identity material on an existing session: reject.
- Supply invalid transfer IDs or paths: reject.
- Corrupt a transfer metadata sidecar: receiver must safely reconstruct state rather than trust it.

## Performance targets

Targets are engineering goals, not guaranteed properties:

- control-plane median RTT: <100 ms on a clean local link
- p95 control RTT: <200 ms
- media one-way capture-to-display: <150 ms where the device/network can sustain it
- no UI-thread blocking for network, crypto, or large-file work
- graceful degradation when one path disappears

True multipath RTP is not implemented. WebRTC selects its media path through ICE. Bulk file transfer can use multiple Android Network objects when the platform exposes genuinely independent routes.