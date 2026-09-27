# Deep Remote Sensor Control Architecture

## Scope

Phone A is the control plane. Phone B owns the physical/synthetic Android sensors and performs acquisition locally. Bluetooth (and the existing higher-bandwidth paths) carries authenticated commands and sensor results.

The implementation deliberately maximizes the control exposed by Android without pretending that a remote peer can bypass Android, the sensor HAL, the kernel, hardware limits, or user permissions.

## Control model

Phone A exposes one compact Hardware & Sensor Control surface with four primary actions:

- AUTO MAX — requests the lowest Android-reported period for every streamable sensor and zero report latency.
- LIVE STATE — retrieves the most recent sample from every active sensor.
- IMU FUSION — retrieves fused orientation from accelerometer + gyroscope + magnetometer when available.
- STOP ALL — unregisters all active sensor listeners.

Every individual sensor is also available through TUNE for explicit rate and batching-latency control.

## Sensor coverage

The remote capability calls SensorManager.getSensorList(Sensor.TYPE_ALL), so it discovers the complete SensorManager inventory exposed by the target device, including hardware sensors and Android software/synthetic sensors.

Each sensor advertises:

- stable Android sensor handle
- sensor type/name/vendor/version
- resolution and maximum range
- minimum delay
- power estimate
- wake-up property
- reporting mode
- dynamic-sensor status
- direct-report rate information
- whether normal listener streaming is supported

Trigger-only sensors are identified rather than incorrectly treated as continuous streams.

## Mathematical layer

SensorFusionEngine performs quaternion attitude propagation from gyroscope angular velocity:

q_dot = 1/2 q ⊗ omega

Discrete propagation uses the exponential-map form of the incremental rotation quaternion. The quaternion is normalized after propagation to control numerical drift.

Accelerometer and magnetometer data provide a slower reference estimate. The fusion correction is adaptive:

- when acceleration magnitude is close to g, gravity is trusted more;
- when acceleration departs strongly from g, correction is reduced to avoid treating translation as tilt;
- magnetometer data supplies heading correction when a valid horizontal magnetic vector exists.

This gives a lightweight complementary estimator with quaternion state, without requiring a native dependency.

## Maximum-rate behavior

getMinDelay() is used as the fastest hardware-reported streaming period. The AUTO MAX command requests this period and zero batching latency for continuous/on-change sensors.

The effective delivery rate may still be lower than the request because Android, the sensor HAL, other system load, vendor implementation, privacy protections, thermal state, and hardware characteristics can constrain event delivery.

For Android 12+ targets, motion/position sensor sampling is subject to platform protections. HIGH_SAMPLING_RATE_SENSORS is declared for the application so high-rate access is possible where Android permits it.

## Security boundary

Sensor control is classified as an explicitly authorized capability. The authenticated Bluetooth session alone is not sufficient authority.

The target device must explicitly authorize the peer before sensor.control commands are accepted.

This prevents a paired device from silently turning the connection into unrestricted sensor surveillance.

## Privilege ladder

The architecture can be deployed at multiple depths:

1. Normal Android app: SensorManager and permitted system APIs.
2. Foreground service: persistent remote acquisition where Android permits it.
3. Device-owner/managed deployment: additional enterprise management.
4. Privileged/system application: deeper framework-facing access on devices that permit it.
5. Root/custom ROM: access to selected kernel/vendor interfaces.
6. Custom Android framework + vendor HAL: maximum software control when the full device stack is under project control.

The project should treat these as separate deployment profiles rather than assuming that one APK can obtain every layer on arbitrary consumer hardware.

## High-throughput relationship

Sensor acquisition and transport are decoupled. The Bluetooth throughput optimizer adapts host-side buffering from measured bandwidth and RTT using a bandwidth-delay-product model while respecting the negotiated radio/controller capabilities.

For very high-rate telemetry, the next transport evolution should be a compact binary sensor stream on the existing bulk/L2CAP path instead of JSON-per-sample messages. The current command channel is intentionally retained for low-rate control, discovery, configuration, and snapshots.

## UI principle

The control surface intentionally avoids dozens of controls. The user gets one corner entry point, a compact summary, three high-value actions, a stop control, and an expandable sensor matrix with per-sensor tuning.

This keeps the interface simple while preserving access to the underlying capability set.
