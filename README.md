# Blutooth Connector

A peer-to-peer Android experiment for controlling one phone from another phone over Bluetooth.

## Vision

Phone A -> Bluetooth -> Phone B

Phone A will eventually act as a controller and Phone B as a controlled peer. The project is designed as a bidirectional command system rather than a one-off remote-control demo.

## Roadmap

1. Establish a reliable authenticated phone-to-phone transport.
2. Define a small versioned command protocol.
3. Implement controller and receiver roles.
4. Add reconnect and state synchronization.
5. Add safe, explicit device capabilities such as media, volume, camera shutter, and custom actions.
6. Measure latency, throughput, reliability, and battery cost.

## Current milestone - 0.1

The Android app establishes the project skeleton, requests modern Android Bluetooth permissions, checks Bluetooth availability, and lists paired devices.

## Engineering principle

We will not assume that an ordinary Android application can arbitrarily control another phone. Each capability must use an Android-supported mechanism and require explicit user authorization where the platform requires it.

## Build

Open the project in a current Android Studio installation with JDK 17 and sync Gradle.

Toolchain baseline: Android Gradle Plugin 9.4.0, Gradle 9.6.0, compileSdk 37.
