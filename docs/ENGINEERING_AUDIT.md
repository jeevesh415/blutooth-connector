# Engineering Audit — Blutooth Connector

**Audit date:** 2026-10-05  
**Scope:** Android application, transport/protocol/security paths, build toolchain, CI structure, tests, and formal-verification assets.

## Executive result

The project is a local-first Android inter-device control platform with a layered architecture:

```text
UI / dashboard / session activities
        |
ConnectionService (foreground ownership and lifecycle)
        |
MultiDeviceManager + DeviceSession
        |
FramedConnection / BluetoothTransport (authenticated control plane)
        |
Protocol frames -> SessionAuthenticator -> replay/dedup guards -> CommandRouter
        |
CapabilityRegistry -> explicitly authorized Android capabilities
        |
Bulk, RTC, Wi-Fi Direct/Aware, sensors, Accessibility, device policy
```

The repository now builds reproducibly through the checked-in Gradle wrapper. During the audit, the first real source defect was found in `BluetoothL2capBulkTransport`: its receive/send validation paths referenced `MAX_TRANSFER_BYTES` and `MAX_PARTIAL_BYTES` without declarations. This was fixed with the project-wide documented limits of **8 GiB per transfer** and **16 GiB aggregate partial storage**.

## Path-by-path findings

### Build and compiler path

- **Gradle:** 9.7.1, wrapper checked in (`gradlew`, `gradlew.bat`, wrapper JAR and properties).
- **Android Gradle Plugin:** 9.4.1.
- **Java:** source/target 17.
- **Android:** min SDK 29, compile/target SDK 36.
- **Dependency:** WebRTC Android SDK `150.7871.01`; JUnit 4.13.2.
- **Reproducibility:** `.gitignore` now excludes Gradle/build/local SDK output; wrapper fetches the pinned distribution.

### Control-plane path

`BluetoothTransport` owns secure RFCOMM server/client sockets. `FramedConnection` provides bounded frame I/O and reader lifecycle. `Protocol`/`Frame` define the versioned envelope. `SessionAuthenticator` adds Keystore-backed application identity, signed nonce transcript, and trust-on-first-use pinning. `ControlReplayGuard` rejects non-positive, replayed, or out-of-order control sequences. `CommandDeduplicator` prevents repeated command execution. `ReliableCommandClient` adds bounded retries, request correlation, timeout handling, and close-time executor shutdown.

### Authorization and capability path

`CommandRouter` rejects malformed, unsupported, unauthorized, and replayed commands before capability execution. `CapabilityRegistry` maps negotiated capability IDs to implementations. Device state, ping, device info, sensors, app launch, UI inspection, remote input, and device policy are separated into capability classes. High-risk paths require receiver-side grants; Accessibility and device-admin/device-owner boundaries remain Android-controlled rather than bypassed.

### Bulk-data path

The control plane advertises optional bulk endpoints. `MultipathFileTransfer` discovers usable local network candidates, filters by routeability, removes duplicate path tuples, schedules chunks, retries bounded failures, and observes throughput/RTT. `SpectralPathScheduler` adapts allocation from path history; it does not claim to increase physical radio bandwidth. `MultipathCrypto` derives transfer keys, authenticates canonical chunk descriptors, encrypts with AES-GCM, and binds transfer/chunk metadata. `MultipathReceiver` validates metadata, sanitizes names, bounds chunks and storage, writes random-access partial files, verifies whole-file SHA-256, and promotes only after integrity success.

`BluetoothL2capBulkTransport` mirrors the same security model over LE L2CAP CoC, including packet-aware buffering, deterministic IVs, per-chunk authorization, watchdog timeouts, safe destination handling, and the corrected 8/16 GiB resource limits.

### Media and alternate transports

`LowLatencyRtcEngine` and `RtcPeerManager` provide WebRTC screen/microphone media and unordered control DataChannel handling. MediaProjection and microphone permissions remain explicit. `WifiDirectPathManager` and `WifiAwarePathManager` expose optional local-network paths and clean up sessions/receivers. Android capability/version checks gate these paths at runtime.

### Lifecycle path

`ConnectionService` owns long-lived foreground transport/media/bulk resources. Activities bind/unbind and close their clients in destruction paths. Managers shut down executors and sockets. Runtime permission requests occur at point of use. Physical radio behavior, simultaneous independent paths, encoder performance, and end-to-end media latency still require two real Android devices and are not proven by JVM tests.

## Security review summary

- Control sessions use Bluetooth secure transport plus application authentication.
- Bulk endpoints use rotating bearer tokens delivered via the control channel.
- Bulk chunks use HMAC authorization and AES-256-GCM with transfer/chunk-bound nonces/AAD.
- Whole-file SHA-256 verification occurs before promotion.
- Remote control requires both enabled AccessibilityService and explicit peer authorization.
- UI inspection masks password fields and bounds tree traversal/data.
- Destination names, IDs, chunk counts, file sizes, and partial storage are bounded.
- Commands reject unsupported/unauthorized/replayed frames before execution.

## Validation performed

Command executed with JDK 17, Android SDK platform 36/build-tools 36.0.0, and Gradle wrapper:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Results:

- **37 unit tests:** passed, 0 failures, 0 errors, 0 skipped.
- **Android lint:** passed with exit code 0 and no lint findings.
- **Debug APK:** assembled successfully.
- **APK metadata:** package `com.jeevesh415.blutoothconnector`, version `0.8.0` / code `8`, min SDK `29`, target SDK `36`.
- **APK SHA-256:** `047352889162f53388e9aa9c9ffb955949cb1dfe6214e89221cefd7f9356ab00`.
- **Diff hygiene:** `git diff --check` passed.

## Remaining device-level validation

1. Pair two Android 10+ devices and verify RFCOMM authentication/reconnect behavior.
2. Enable receiver AccessibilityService and test authorized tap/swipe/text/semantic actions.
3. Exercise MediaProjection/WebRTC consent and revoke flows.
4. Test file transfer over RFCOMM-advertised TCP, Wi-Fi Direct, Wi-Fi Aware, and LE L2CAP where supported.
5. Measure RTT, throughput, battery impact, partial-transfer resume, and radio/network failover on representative hardware.
6. Run the Lean workflow in CI to validate the formal model/refinement assets.
