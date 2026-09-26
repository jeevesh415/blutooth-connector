# Frontier Architecture Track

## Purpose

Blutooth Connector is being evolved as a **local-first inter-device control substrate** rather than a conventional remote-control application.

The target architecture is:

```
                ┌──────────────────────────────────────────────┐
                │          Intent / Capability Layer           │
                │  commands • state • events • workflows      │
                └──────────────────────┬───────────────────────┘
                                       │
                ┌──────────────────────▼───────────────────────┐
                │            Secure Session Layer              │
                │ identity • pairing • authorization • replay  │
                └──────────────────────┬───────────────────────┘
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        │                              │                              │
┌───────▼────────┐             ┌───────▼────────┐             ┌───────▼────────┐
│ Control Path   │             │ Media Path     │             │ Bulk/Data Path │
│ Bluetooth      │             │ WebRTC / RTC   │             │ TCP / Aware /  │
│ low latency    │             │ interactive    │             │ Direct / future│
└───────┬────────┘             └───────┬────────┘             └───────┬────────┘
        │                              │                              │
        └──────────────────────────────┼──────────────────────────────┘
                                       │
                ┌──────────────────────▼───────────────────────┐
                │        Android Capability Boundary           │
                │ Accessibility • MediaProjection • Admin      │
                │ Companion APIs • platform permissions        │
                └──────────────────────────────────────────────┘
```

The application must not bypass Android security boundaries. "Deep control" means composing the deepest **user-authorized, platform-supported** capabilities available on the device.

## Frontier principles

### 1. Capability-oriented control

A peer never receives an implicit set of powers because it is paired.

Each capability has:
- stable identifier;
- semantic version;
- authorization requirement;
- platform prerequisites;
- bounded command schema;
- observable result/error semantics.

Future work should move toward capability negotiation as a typed contract rather than a list of strings.

### 2. Separate control, media, and bulk planes

The control plane is optimized for correctness and recovery.

The media plane is optimized for interactive latency.

The bulk plane is optimized for throughput, integrity, and resumability.

A failure in one plane should not unnecessarily terminate the others.

### 3. Multipath as an explicit path model

The current scheduler is an application-layer precursor to a Multipath QUIC-style architecture.

The current IETF Multipath QUIC draft defines explicit path identifiers and path lifecycle management, while deliberately leaving address discovery and application scheduling outside its scope. The project therefore keeps:

- path discovery in Android-specific transport managers;
- path identity and health in the transport layer;
- scheduling in an application policy layer;
- payload integrity above individual paths.

This separation should be preserved as the project grows.

### 4. Scheduling must remain physically honest

A scheduler may exploit genuinely independent paths.

It must not claim that:
- two Network objects necessarily mean two independent radios;
- Wi-Fi Direct + Wi-Fi Aware necessarily provide additive throughput;
- spectral analysis creates bandwidth;
- a successful socket bind proves physical path independence.

Measurements should distinguish **logical path diversity** from **physical path independence**.

### 5. Android 17 is a first-class constraint

The project targets SDK 37. Android 17 introduces mandatory local-network permission enforcement for apps targeting API 37+, including an `ACCESS_LOCAL_NETWORK` runtime permission path.

Any new LAN transport, discovery mechanism, TCP endpoint, or WebRTC local-network path must be audited against that boundary.

### 6. Pairing and authorization are different

Bluetooth pairing establishes a transport relationship.

Application authorization establishes what the remote peer can do.

The receiver remains the policy authority. High-risk capabilities must fail closed.

### 7. Real-time interaction

Remote screen/control is a coupled media + input system:

- MediaProjection supplies user-consented screen capture.
- WebRTC provides the interactive media transport.
- DataChannel can carry low-latency input.
- AccessibilityService performs platform-supported input injection.
- session identifiers and monotonic sequence guards prevent stale/replayed control.

True multipath media is a separate research track and must not be advertised as implemented until a real implementation and two-device measurements exist.

### 8. Identity should evolve beyond address-based trust

Bluetooth MAC addresses are transport identifiers, not durable application identities.

The next security evolution should establish a device/application identity backed by Android Keystore, then bind authorized capabilities to that identity and to an explicit enrollment/revocation record.

The current trust-on-first-use mechanism is a useful compatibility layer, not the final identity architecture.

### 9. Companion-device integration is strategic

Android's CompanionDeviceManager provides system-mediated companion-device association and presence capabilities. The project should evaluate it as an enrollment/presence mechanism instead of inventing a parallel pairing UX where Android already provides a system boundary.

It does not replace the application's capability authorization model.

## Ordered engineering tree

### A. History and reproducibility
1. Keep `main` green.
2. Preserve historical SHAs as immutable evidence.
3. Build a repaired-history branch separately if exact historical snapshots need repair.
4. Never relabel a failing historical snapshot as green.

### B. Protocol contract
1. Define a typed capability manifest.
2. Add protocol feature negotiation.
3. Define explicit compatibility rules.
4. Add negative tests for unknown versions/features.
5. Add bounded message/resource limits.

### C. Security
1. Separate transport authentication from application authorization.
2. Introduce durable application identity.
3. Make enrollment explicit and revocable.
4. Bind authorization to identity, capability, and session.
5. Add key rotation/revocation semantics.
6. Preserve replay protection across reconnects.

### D. Transport substrate
1. Treat every Android Network as a candidate path.
2. Assign stable per-session path IDs.
3. Maintain path state, RTT, loss/failure, throughput, and availability.
4. Keep path discovery separate from scheduling.
5. Add path probing before bulk allocation.
6. Add fairness and starvation protection to the scheduler.
7. Benchmark actual path independence on physical devices.

### E. Data plane
1. Keep chunk-level integrity and resumability.
2. Add explicit transfer/session IDs.
3. Add congestion/backpressure signals.
4. Make scheduling decisions observable.
5. Evaluate a QUIC-based transport implementation only after measuring the current TCP architecture.
6. Do not replace working transports merely for novelty.

### F. Real-time plane
1. Keep Bluetooth as signaling/control.
2. Keep WebRTC for interactive media.
3. Separate media authorization from remote-input authorization.
4. Add playback-capture support only through Android's user-consent APIs where eligible.
5. Measure glass-to-glass latency, jitter, packet loss, and control latency on real hardware.
6. Treat multipath RTP as research until experimentally validated.

### G. Device capability plane
1. Device state.
2. Semantic UI inspection.
3. Accessibility-backed interaction.
4. Launchable-app control.
5. Device policy.
6. Sensors/media/files where Android permissions permit.
7. Capability-specific audit events.

### H. Multi-device orchestration
1. Multiple independent sessions.
2. Per-peer authorization.
3. Aggregate state model.
4. Explicit group operations.
5. Failure isolation.
6. Bounded fan-out.
7. No implicit trust propagation between peers.

### I. Verification
1. JVM unit tests.
2. Android lint.
3. Debug build.
4. APK assembly.
5. Protocol fuzz/property tests.
6. Fault-injection loopback tests.
7. Two-device integration tests.
8. Reconnect/resume tests.
9. Security negative tests.
10. Performance benchmark suite.

## Definition of "frontier-ready"

A feature is frontier-ready only when all four conditions hold:

1. **Architecturally justified** — it improves the substrate rather than adding a disconnected demo.
2. **Platform-correct** — it respects current Android APIs and permission/security boundaries.
3. **Measured** — performance or reliability claims have reproducible measurements.
4. **Verified** — automated tests cover normal and adversarial behavior.

Research ideas can exist behind feature flags or experimental modules before they satisfy this definition, but they must be labeled experimental.

## Current verified baseline

Baseline for this track:

`c3f22ceb2836461ce3a4aad791730e855a1bb5cc`

The baseline contains the 0.7 deep-control plane, authenticated control/data architecture, WebRTC screen/control path, Wi-Fi Aware bulk path, adaptive multipath transfer, and automated verification.

The current main branch is the stable baseline. This frontier track must not destabilize it.
