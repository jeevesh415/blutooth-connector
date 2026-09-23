# Protocol and Systems Design

## 1. State machine

A peer session moves through:

DISCONNECTED -> CONNECTING -> LINKED -> NEGOTIATING -> AUTHENTICATED -> READY -> CLOSED

Invalid transitions are rejected. A transport connection is never treated as authorization to execute a capability.

## 2. Framing

The transport uses a 4-byte unsigned big-endian length followed by UTF-8 JSON.

If payload length is L, wire cost is W = 4 + L.

The implementation rejects L <= 0 or L > 65536, bounding memory allocation from an untrusted peer.

## 3. Sequencing

Each session has a monotonically increasing sequence number: seq(n+1) > seq(n).

Once replay protection is enabled, stale and duplicate requests can be rejected.

For an idempotent command, request_id = device_id || session_id || seq.

A result cache can map request_id -> result and safely return the same result for a retransmission.

## 4. Latency

For request i: RTT_i = t_result_i - t_send_i.

The first implementation uses round-trip time because accurate one-way latency requires clock synchronization.

EWMA:

S_i = alpha * RTT_i + (1-alpha) * S_(i-1), with 0 < alpha < 1.

Online variance estimate:

V_i = beta * (RTT_i - S_i)^2 + (1-beta) * V_(i-1).

Standard deviation estimate: sigma_i = sqrt(V_i).

Adaptive timeout candidate: T_i = max(T_min, S_i + k * sigma_i).

k will be selected experimentally.

## 5. Reliability

For N commands:

delivery_rate = completed / N

loss_rate = 1 - delivery_rate

gap_rate = missing_sequence_numbers / expected_sequence_numbers

We will report median, p95, p99, maximum RTT, throughput, reconnect time, and battery cost instead of relying on a single average.

## 6. Throughput

For B application bytes transferred during interval Delta_t:

throughput = 8B / Delta_t

Useful application throughput is below the raw Bluetooth link rate because of framing, serialization, acknowledgements, scheduling, and radio contention.

For control commands, latency and reliability matter more than bulk throughput.

## 7. Capability model

Capability = (id, version, permissions, operations, state_schema)

Example: transport.ping@1.0

The controller discovers capabilities before invoking them. It must not invent unsupported operations.

COMMAND(capability, operation, request_id, arguments)
RESULT(request_id, status, value, error)
EVENT(capability, event_id, sequence, state_delta)

## 8. Security model

Secure Bluetooth RFCOMM provides link-level authentication/encryption when an authenticated socket is established.

The application layer will additionally establish device identity, session identity, explicit user confirmation, capability authorization, sequence/replay protection, and bounded input validation.

The key rule is: transport authentication is not application authorization.

## 9. Control plane vs data plane

Control plane: handshake, capabilities, commands, acknowledgements, errors, state synchronization.

Data plane: screen frames, files, sensor streams, and explicitly authorized audio/video.

This separation prevents a large stream from blocking latency-sensitive commands.

## 10. Future transport abstraction

ProtocolMessage -> TransportAdapter -> PhysicalLink

The protocol should remain independent of RFCOMM so that other appropriate local transports can be added later without redesigning the command model.

## 11. Experimental questions

Measure Bluetooth command RTT distribution, reconnection time after radio interruption, command loss under load, sustainable command rate, energy per successful command, screen-stream latency if projection is added, capability discovery time, and state convergence time after reconnection.

The mathematics exists to make the system measurable and tunable, not decorative.
