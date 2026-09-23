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
