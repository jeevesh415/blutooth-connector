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

## Current milestone - 0.1

The repository contains the Android project skeleton, modern Bluetooth permissions, Bluetooth availability detection, and paired-device inspection. The next implementation milestone is the actual authenticated bidirectional transport and protocol.

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

The next implementation layer is a real file-picker workflow on the controller and an explicit transfer UI that selects the fastest mutually available path per device.
