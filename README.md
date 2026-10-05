# Blutooth Connector

<a href="https://github.com/jeevesh415/blutooth-connector/releases/latest/download/blutooth-connector.apk"><strong>⬇️ Download Android APK</strong></a>

A local-first Android inter-device control platform.

## Install on Android

1. Tap **Download Android APK** above.
2. Open the downloaded `blutooth-connector.apk`.
3. If Android asks, allow your browser/file manager to **install unknown apps**.
4. Tap **Install**.

The repository builds the APK automatically in GitHub Actions. The complete build environment (JDK 17, stable Android SDK 36, Android Gradle Plugin 9.4.1, Gradle 9.7.1, dependencies, tests, and APK packaging) runs on GitHub's build machine, so an end user does **not** need Android Studio, Gradle, Java, or an Android compiler installed on the phone.

**Supported Android:** Android 10 (API 29) and newer. Android 12+ enables additional capabilities where the platform provides them; Wi-Fi Aware and other newer transport capabilities remain runtime-gated rather than being required for installation.

The download link always points to the repository's **latest** APK release. A new APK is published automatically whenever `main` changes and the build/tests succeed.

## Release signing

The published APK is a **release-signed** build, not a GitHub-runner-generated debug APK. The release workflow uses one persistent Android signing key stored in GitHub Actions secrets:

- `ANDROID_KEYSTORE_B64` — base64-encoded PKCS12/JKS keystore
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — signing-key alias
- `ANDROID_KEY_PASSWORD` — signing-key password

The private keystore is never committed to the repository. The workflow reconstructs it only on the ephemeral runner, signs the release APK, verifies the published artifact byte-for-byte, and deletes the temporary keystore afterward. Keeping the same private key across releases is what allows Android to update an existing installation instead of treating the APK as a different signer.

**Important for the first migration:** APKs previously installed from the old GitHub debug-release pipeline were signed by the old debug certificate. They cannot be updated by the new release signer. Uninstall that old installation once before installing the first persistently signed release. After that migration, subsequent releases retain the same signing identity and can update normally.

## Vision

**Phone A <-> transport <-> Phone B**

Phone A can act as a controller while Phone B exposes a set of explicitly authorized capabilities. The goal is not to clone a remote-control app, but to build a general capability-oriented protocol that can adapt to the device and permissions actually available.

## Architecture

- **Bluetooth-first adaptive transport** - runtime capability negotiation across legacy BR/EDR and BLE generations. The app uses the strongest common Bluetooth plane actually exposed by both Android devices instead of guessing a Bluetooth version.
- **LE performance path** - LE L2CAP CoC is preferred for bulk when both peers expose it; newer PHY/advertising capabilities are recorded for adaptive behavior and are never assumed.
- **Optional Wi-Fi** - Wi-Fi Direct/Aware and local TCP remain available as optional higher-throughput paths. They are not required for the Bluetooth control architecture.

- **Controller** - discovers Phone B, negotiates capabilities, sends commands, receives events and synchronized state.
- **Transport** - starts with Bluetooth and is abstracted so higher-bandwidth local transports can be added later.
- **Protocol** - versioned, authenticated, bidirectional messages with capability negotiation.
- **Capability Broker** - Phone B exposes only operations that Android and the user have explicitly authorized.
- **State/Event layer** - commands produce results and device events flow back to the controller.

## Design principle

Bluetooth is the **primary communication pipe**, not the control authority. Wi-Fi remains an optional secondary path. Phone B remains the authority over what the controller is allowed to do.

The application must never assume unrestricted Android control. Each capability must use an Android-supported mechanism and explicit user authorization where required.

## Initial milestones

1. **Transport** - authenticated phone-to-phone Bluetooth session.
2. **Protocol** - versioned command/event envelope.
3. **Capability discovery** - Phone B advertises available authorized capabilities.
4. **Controller** - dynamically renders controls from negotiated capabilities.
5. **State synchronization** - reconnect, acknowledgements, events, and state snapshots.
6. **Capability expansion** - media, volume, camera shutter, notifications, sensors, files/data transfer, and deeper UI interaction where Android permits it.
7. **Multi-transport** - evaluate Wi-Fi/local networking and other appropriate transports without changing the protocol.
8. **Benchmarking** - latency, reliability, throughput, power consumption, and recovery behavior.

## Security model

Every device relationship should be explicitly paired and revocable. Commands should be authenticated, sessions encrypted, and capabilities authorized independently. The receiver must reject unsupported or unauthorized operations.

## Current milestone - 0.8

The repository now contains the bidirectional Bluetooth control plane, capability routing, adaptive command retries, foreground service ownership, encrypted BCL3 multipath bulk transfer, Android Network-specific path binding, and automated JVM/loopback verification.

## Build

Open the project in Android Studio with JDK 17 and sync Gradle. From a terminal,
the reproducible command is `./gradlew testDebugUnitTest lintDebug assembleDebug`.

Toolchain baseline: Android Gradle Plugin 9.4.1, Gradle 9.7.1, compileSdk 36, targetSdk 36, minSdk 29 (Android 10).

GitHub Actions installs the exact Gradle version and Android SDK required for the automated APK build. End users do not need any of these tools on the phone.

## Multi-device and high-throughput design

One controller can maintain multiple independent Bluetooth sessions. The application enforces a seven-peer policy for Bluetooth Classic because the Bluetooth SIG describes a BR/EDR piconet with up to seven active peripherals; actual handset/controller implementations can impose lower limits. citeturn422326search3

Each peer has its own socket, state, heartbeat, reconnect schedule, RTT estimator, and metrics. A failure on one peer does not have to tear down the other sessions.

### Two-tier data plane

Bluetooth is used for low-latency control, discovery, and capability negotiation.

When Phone B exposes a local TCP endpoint, the controller can move bulk data to TCP while keeping the Bluetooth control channel alive. Android Wi-Fi Direct and local-only Wi-Fi APIs provide phone-to-phone local networking without requiring Internet access, and Android documents Wi-Fi Direct as suitable for higher-throughput peer communication and multi-device groups. citeturn139326search3turn139326search6

Bulk transfers use:

- 1 MiB application buffers on the IP multipath path; Bluetooth L2CAP uses controller-aware host buffering.
- Resume from the receiver's existing partial-file length.
- A 32-byte cryptographic transfer token.
- Final SHA-256 end-to-end integrity verification.
- Deterministic temporary files.
- Path-safe destination names.
- TCP keep-alive and large socket buffers.
- Bounded retry/backoff at the caller.
- An explicit per-peer file-transfer authorization gate; revocation rotates the bulk bearer token.
- An 8 GiB per-transfer limit and a 16 GiB aggregate partial-file budget.

There is deliberately no promise of zero physical link failures. The engineering target is **fault tolerance**: if the radio or network drops, the session detects it, reconnects where possible, and bulk transfers can resume without restarting the entire file.

### Why not force Bluetooth for bulk?

Bluetooth Classic BR/EDR is designed for lower-bandwidth personal-area networking. The Bluetooth SIG lists a 1–3 Mb/s nominal data-rate range for BR/EDR and notes that all channels between two devices share the same physical link. citeturn422326search3turn422326search2

Therefore the high-throughput strategy is not to fake a faster Bluetooth link. It is to keep Bluetooth as the robust control plane and opportunistically use a faster local IP path for the data plane.

## Real-time screen + control

The repository now includes a WebRTC real-time path bootstrapped by Bluetooth RFCOMM:

- Android MediaProjection provides user-consented screen frames.
- The microphone is captured through WebRTC audio capture.
- WebRTC DataChannel carries interactive control messages separately from media.
- The receiver's AccessibilityService is required for touch/navigation injection.
- Remote control is additionally gated by an explicit per-peer authorization stored by the receiver.
- Control envelopes carry a strictly increasing sequence number; replayed or out-of-order control messages are rejected.
- The viewer maps touch coordinates to the actual received frame size and compensates for aspect-fit letterboxing.
- If MediaProjection is revoked, screen-capture resources are released.

**System playback audio is not represented as microphone audio.** The current implementation deliberately advertises and starts microphone capture only. Android's AudioPlaybackCapture API can capture eligible playback streams with a separate user-consent flow, but integrating that PCM source into the WebRTC audio device path is not yet part of this build.

## Wi-Fi Aware path

Wi-Fi Aware is wired into the bulk multipath architecture on supported Android 12+ devices. Each side publishes/subscribes to the BCL service, establishes a secure Aware data path using a PMK derived symmetrically from both peers' authenticated BCL3 bulk tokens, and exposes the resulting Android Network as a wifi-aware bulk endpoint.

The path remains optional: device support and current Aware availability are checked at runtime. Bluetooth RFCOMM remains the control/signaling and capability channel.

## Android local-network behavior

The current stable build targets SDK 36 (Android 15) with a minimum of Android 10 (API 29). Local-network and nearby-device operations remain subject to the permissions and runtime restrictions enforced by the Android version running on the device. The UI requests the relevant permissions at the point of use rather than assuming unrestricted local-network access.

## Security boundary

Bluetooth pairing is the transport relationship, not authorization for high-risk actions or bulk file transfer. Bulk transfer uses per-transfer bearer tokens, HMAC authorization proofs, AES-256-GCM, deterministic transfer/chunk-bound 96-bit GCM nonces, and a final SHA-256 integrity check. Retries reuse the same nonce only for the same authenticated transfer/chunk; a different chunk or transfer derives a different nonce. Remote Accessibility control additionally requires an explicit peer grant on the receiving phone.

The control plane now adds an Android Keystore application identity and a signed per-connection nonce transcript, with trust-on-first-use pinning per bonded Bluetooth address. This is stronger than treating Bluetooth pairing as application authorization, but it is still not a full interactive enrollment ceremony or certificate authority.

## Multipath boundary

The bulk path can stripe chunks across distinct Android Network objects and now filters candidates by the route table for the advertised destination. This can exploit genuinely independent connectivity when Android and the hardware expose it.

WebRTC media is **not** presented as multipath. It currently uses normal ICE/WebRTC path selection. True RTP multipath would require a dedicated media transport design or WebRTC stack changes.

## Deep-control smoke test

On Phone B:

1. Enable this app's Accessibility service.
2. In the app, authorize Phone A as a remote-control peer.
3. For device-policy operations, optionally activate the app as a device admin. Reboot remains unavailable unless the app is provisioned as device owner.
4. Keep the receiver service active.

On Phone A:

1. Connect the paired peer.
2. Query deep device state.
3. Use the remote screen/control flow for gesture or semantic-node actions.
4. Launch a launchable package through `app.control`.

Protected commands are rejected before capability execution when the peer has not been explicitly authorized, and replayed/out-of-order authenticated frames are rejected at the session boundary.

## Current practical test

On Phone B:

1. Start the receiver service.
2. Keep Bluetooth pairing active.
3. Ensure Phone A and B are on the same local Wi-Fi network for bulk testing.

On Phone A:

1. Pair the phones.
2. Scan paired devices.
3. Connect all paired devices.
4. Watch per-device RTT and p95 metrics.
5. The receiver advertises local TCP bulk endpoints through the Bluetooth capability exchange.

The current controller includes a file-picker workflow and sends staged files to all connected peers. Runtime device benchmarks are still required to measure the actual benefit of independent Wi-Fi paths.

## Low-latency control behavior

RTC control uses an unordered DataChannel with a one-retransmission budget. Commands generated while ICE/DataChannel negotiation is incomplete are held in a small bounded queue and flushed when the channel opens; the protocol's session ID and monotonic sequence guard still reject stale or replayed commands. This reduces UI stalls without claiming zero latency.

## Adaptive multipath data plane

The data plane now treats reachable local TCP addresses as a graph of candidate paths instead of selecting the first endpoint.

- Bluetooth RFCOMM remains the low-latency control/discovery plane.
- Local TCP paths can carry bulk data concurrently.
- Files are striped into independently acknowledged chunks.
- Chunks can arrive out of order and are reconstructed with random-access writes.
- A final SHA-256 check protects end-to-end integrity.
- Failed chunks are retried without restarting the entire transfer.
- A spectral path scheduler uses recent throughput/RTT time series and a discrete Fourier high-frequency-instability term to adapt path allocation.
- Endpoint metadata carries the network-interface identity so future Wi-Fi Direct/Aware paths can participate without changing the transfer abstraction.

The mathematical layer is deliberately used for **scheduling**, not for pretending that mathematics can increase the physical bandwidth of a radio. Aggregate throughput can improve when the device exposes genuinely independent usable paths; interference, Android routing, chipset limitations, and shared RF resources still bound the result.

### Research direction

The architecture is compatible with a future path-ID model similar to multipath QUIC: independent path state, path-specific sequencing/congestion control, and an application scheduler above the transport. Android Wi-Fi Direct provides a direct peer-to-peer path, while Wi-Fi Aware supports direct high-speed bidirectional connections when a socket is established. These are candidates for additional channels, not assumptions that every phone supports them.

## Deep Control Plane - 0.7

Phone B now exposes a deeper, capability-gated control surface after the existing authenticated Bluetooth session:

- `device.state` — battery, charging state, interactivity, keyguard state, accessibility readiness, and active network transports.
- `remote.control` — explicit Accessibility-backed tap, swipe, navigation, text, and semantic node actions.
- `ui.inspect` — bounded accessibility-tree inspection with password-field text/content masked.
- `app.control` — explicitly authorized launch of a user-installed launchable package.
- `device.policy` — device-admin status and lock operations; reboot is exposed only when the receiver is provisioned as the device owner.

The command router enforces the receiver-side authorization gate for protected capabilities, and each authenticated session now rejects replayed or out-of-order control frames before execution. Android's AccessibilityService API permits user-enabled services to dispatch gestures and perform global actions; device-owner APIs are the platform boundary for deeper device policy operations. citeturn522233search0turn522233search2turn957562search0

The project still does **not** attempt to bypass Android's security model. Screen capture remains MediaProjection/user-consent based, Accessibility must be enabled by the user, and device-owner-only operations remain unavailable on ordinary unmanaged installations. citeturn522233search0turn522233search2

## Validation boundary

The repository is designed so every transport/capability is optional and negotiated from actual Android/device support. The automated suite can verify protocol, crypto, persistence, scheduling, and build behavior, but it cannot prove physical radio coexistence, measured end-to-end media latency, or hardware encoder performance. Those require two real Android devices.

Current explicit limitations:

- WebRTC media uses normal ICE path selection; true multipath RTP is not implemented.
- Microphone capture is implemented; Android playback capture is not yet wired into the WebRTC audio device path.
- Remote input requires the user to enable the AccessibilityService and separately authorize the Bluetooth peer.
- Application identity uses an Android Keystore signing key plus trust-on-first-use pinning; there is no interactive enrollment/attestation authority.
- Android 17 local-network and nearby-Wi-Fi permissions are requested at the point of use.
- Wi-Fi Direct/Aware availability and simultaneous independent paths depend on the device chipset, Android routing, and RF environment.
