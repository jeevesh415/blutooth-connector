# Blutooth Connector

A local-first Android inter-device control platform.

## Vision

**Phone A <-> transport <-> Phone B**

Phone A can act as a controller while Phone B exposes a set of explicitly authorized capabilities. The goal is not to clone a remote-control app, but to build a general capability-oriented protocol that can adapt to the device and permissions actually available.

## Architecture

- **Controller** - discovers Phone B, negotiates capabilities, sends commands, receives events and synchronized state.
- **Transport** - starts with Bluetooth and is abstracted so higher-bandwidth local transports can be added later.
- **Protocol** - versioned, authenticated, bidirectional messages with capability negotiation.
- **Capability Broker** - Phone B exposes only operations that Android and the user have explicitly authorized.
- **State/Event layer** - commands produce results and device events flow back to the controller.

## Design principle

Bluetooth is the **communication pipe**, not the control authority. Phone B remains the authority over what the controller is allowed to do.

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

## Current milestone - 0.6

The repository now contains the bidirectional Bluetooth control plane, capability routing, adaptive command retries, foreground service ownership, encrypted BCL3 multipath bulk transfer, Android Network-specific path binding, and automated JVM/loopback verification.

## Build

Open the project in Android Studio with JDK 17 and sync Gradle.

Toolchain baseline: Android Gradle Plugin 9.4.0, Gradle 9.6.0, compileSdk 37.


## Multi-device and high-throughput design

One controller can maintain multiple independent Bluetooth sessions. The application enforces a seven-peer policy for Bluetooth Classic because the Bluetooth SIG describes a BR/EDR piconet with up to seven active peripherals; actual handset/controller implementations can impose lower limits. citeturn422326search3

Each peer has its own socket, state, heartbeat, reconnect schedule, RTT estimator, and metrics. A failure on one peer does not have to tear down the other sessions.

### Two-tier data plane

Bluetooth is used for low-latency control, discovery, and capability negotiation.

When Phone B exposes a local TCP endpoint, the controller can move bulk data to TCP while keeping the Bluetooth control channel alive. Android Wi-Fi Direct and local-only Wi-Fi APIs provide phone-to-phone local networking without requiring Internet access, and Android documents Wi-Fi Direct as suitable for higher-throughput peer communication and multi-device groups. citeturn139478search3turn139478search6

Bulk transfers use:

- 1 MiB application buffers.
- Resume from the receiver's existing partial-file length.
- A 32-byte cryptographic transfer token.
- Final SHA-256 end-to-end integrity verification.
- Deterministic temporary files.
- Path-safe destination names.
- TCP keep-alive and large socket buffers.
- Bounded retry/backoff at the caller.

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

## Android 17 / local-network behavior

Because the app targets SDK 37, Android local-network access must be explicitly granted before operations that use local TCP/UDP networking. The UI requests this permission when file transfer or real-time streaming needs it. Nearby Wi-Fi permissions are requested separately for Wi-Fi Direct.

## Security boundary

Bluetooth pairing is the transport relationship, not authorization for high-risk actions. Bulk transfer uses per-transfer bearer tokens, HMAC authorization proofs, AES-256-GCM, deterministic transfer/chunk-bound 96-bit GCM nonces, and a final SHA-256 integrity check. Retries reuse the same nonce only for the same authenticated transfer/chunk; a different chunk or transfer derives a different nonce. Remote Accessibility control additionally requires an explicit peer grant on the receiving phone.

The control plane now adds an Android Keystore application identity and a signed per-connection nonce transcript, with trust-on-first-use pinning per bonded Bluetooth address. This is stronger than treating Bluetooth pairing as application authorization, but it is still not a full interactive enrollment ceremony or certificate authority.

## Multipath boundary

The bulk path can stripe chunks across distinct Android Network objects and now filters candidates by the route table for the advertised destination. This can exploit genuinely independent connectivity when Android and the hardware expose it.

WebRTC media is **not** presented as multipath. It currently uses normal ICE/WebRTC path selection. True RTP multipath would require a dedicated media transport design or WebRTC stack changes.

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


## Validation boundary

The repository is designed so every transport/capability is optional and negotiated from actual Android/device support. The automated suite can verify protocol, crypto, persistence, scheduling, and build behavior, but it cannot prove physical radio coexistence, measured end-to-end media latency, or hardware encoder performance. Those require two real Android devices.

Current explicit limitations:

- WebRTC media uses normal ICE path selection; true multipath RTP is not implemented.
- Microphone capture is implemented; Android playback capture is not yet wired into the WebRTC audio device path.
- Remote input requires the user to enable the AccessibilityService and separately authorize the Bluetooth peer.
- Application identity uses an Android Keystore signing key plus trust-on-first-use pinning; there is no interactive enrollment/attestation authority.
- Android 17 local-network and nearby-Wi-Fi permissions are requested at the point of use.
- Wi-Fi Direct/Aware availability and simultaneous independent paths depend on the device chipset, Android routing, and RF environment.
