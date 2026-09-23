# Protocol and Systems Design

## 1. State machine

A peer session moves through:

DISCONNECTED -> CONNECTING -> LINKED -> NEGOTIATING -> READY -> CLOSED

The current implementation uses the OS Bluetooth pairing boundary as the trust boundary for RFCOMM peers and a random 256-bit bearer token for each local bulk endpoint. Application-layer cryptographic identity/authentication is deliberately not claimed as complete yet.

## 2. Framing

The transport uses a 4-byte signed Java integer carrying the frame length, followed by UTF-8 JSON.

The implementation rejects L <= 0 or L > 65536, bounds the allocation from an untrusted peer, and rejects protocol-version mismatches before delivering a frame to the application.

## 3. Sequencing

Each Bluetooth session has a monotonically increasing transmit sequence number. Incoming frames with a lower sequence number than the last observed sequence are ignored. Equal sequence values remain acceptable so an idempotent retransmission can be handled by the command request identifier.

For a reliable command:

request_id = UUID

A result cache maps request_id -> result and safely returns the same result for a retransmission while the cache retains it.

The single-sequence model is intentionally local to each ordered RFCOMM session. A future true multipath control plane should use separate path/stream identifiers rather than pretending several links are one ordered byte stream.

## 4. Latency

For request i: RTT_i = t_result_i - t_send_i.

The first implementation uses round-trip time because accurate one-way latency requires clock synchronization.

EWMA:

S_i = alpha * RTT_i + (1-alpha) * S_(i-1), with 0 < alpha < 1.

Online variance estimate:

V_i = beta * (RTT_i - S_(i-1))^2 + (1-beta) * V_(i-1).

Standard deviation estimate: sigma_i = sqrt(V_i).

Adaptive timeout candidate:

T_i = max(T_min, S_i + k * sigma_i).

## 5. Reliability

For N commands:

delivery_rate = completed / N

loss_rate = 1 - delivery_rate

gap_rate = missing_sequence_numbers / expected_sequence_numbers

Report median, p95, p99, maximum RTT, throughput, reconnect time, and battery cost instead of relying on a single average.

## 6. Throughput

For B application bytes transferred during interval Delta_t:

throughput = 8B / Delta_t

Useful application throughput is below the raw link rate because of framing, serialization, acknowledgements, scheduling, and radio contention.

For control commands, latency and reliability matter more than bulk throughput.

## 7. Capability model

Capability = (id, version, permissions, operations, state_schema)

Example: transport.ping@1.0

The controller discovers capabilities before invoking them. It must not invent unsupported operations.

COMMAND(capability, operation, request_id, arguments)
RESULT(request_id, status, value, error)
EVENT(capability, event_id, sequence, state_delta)

## 8. Security model

Secure Bluetooth RFCOMM can provide link-level authentication/encryption when the Android Bluetooth stack establishes an authenticated socket.

Current application-layer protection is:

1. incoming unpaired Bluetooth peers are rejected;
2. bulk endpoints use random 256-bit bearer tokens;
3. bulk transfers verify an end-to-end SHA-256 object hash;
4. filenames and transfer ranges are bounded and sanitized.

A future application-security milestone should add an explicit user-approved secret or public-key enrollment, transcript-bound mutual authentication, key derivation, replay protection, and capability scopes. That is preferable to keeping an incomplete cryptographic handshake in the active protocol.

## 9. Control plane vs data plane

Control plane: handshake, capabilities, commands, acknowledgements, errors, state synchronization.

Data plane: files and other explicitly authorized bulk streams.

Bluetooth RFCOMM remains the low-volume control/discovery path. The bulk layer uses independent local TCP connections exposed by the peer. This keeps large payloads from blocking the command channel.

## 10. Multipath scheduling

The multipath file protocol uses a set of endpoint paths:

P = {p_1, ..., p_n}

Each path keeps a short time series for throughput and RTT. A discrete Fourier projection estimates the fraction of high-frequency variation. The scheduler converts the resulting utility values into a softmax allocation.

This is an application-level scheduling heuristic. It does not increase PHY bandwidth, defeat radio contention, or guarantee independent RF resources. Multiple IP addresses are only independent paths when the operating system and hardware actually route them independently.

## 11. Transport expansion

The abstraction deliberately accepts transport metadata such as wifi-lan, wifi-direct, wifi-aware, ethernet, or tcp-local. Android can expose Wi-Fi Direct and Wi-Fi Aware paths on supported devices, but creating those paths requires their platform-specific discovery/session APIs.

No current code claims that Wi-Fi Direct or Wi-Fi Aware is active merely because the interface name exists.

## 12. Experimental questions

Measure Bluetooth command RTT distribution, reconnect time after radio interruption, command loss under load, sustainable command rate, energy per successful command, transfer Mbps, path utilization, failover time, capability discovery time, and state convergence after reconnection.

The mathematics exists to make the system measurable and tunable, not decorative.
