# Multi-device and bulk-transfer engineering

## Topology

Phone A can maintain independent control sessions to several Phone B peers.

For Bluetooth Classic, the implementation uses an application policy of at most 7 active peers. This matches the Bluetooth SIG's documented BR/EDR piconet figure; handset/controller implementations may support fewer concurrent links. The limit is an application safeguard, not a promise that every Android handset can maintain seven RFCOMM sessions.

Each session owns its own:

- BluetoothSocket
- framed control channel
- heartbeat
- reconnect state
- RTT estimator
- negotiated bulk endpoints

One failing peer is therefore isolated from the other sessions.

## Reliability stack

The system deliberately uses several layers:

Physical/link layer:
Bluetooth's lower layers already use acknowledgements/retransmission for ACL data integrity. The application does not try to replace those mechanisms.

Session layer:
Every peer has a heartbeat every 5 seconds. A peer that produces no received traffic for 15 seconds is treated as unhealthy. Reconnect uses exponential backoff:

1, 2, 4, 8, 16, 30, 30, ... seconds

Control-plane sequencing and idempotent command identifiers are reserved for the next protocol milestone.

Bulk-data layer:
Bulk transfer uses TCP where a mutually reachable local IP endpoint is advertised. TCP supplies ordered reliable delivery; the application adds a random 256-bit transfer token, file-size metadata, resume offset, and final SHA-256 verification.

## Why Bluetooth is not the bulk path

BR/EDR bandwidth is shared at the physical-link level. Making several applications push large streams simultaneously over Bluetooth therefore competes for the same radio resources. The Bluetooth SIG describes BR/EDR as a 1-3 Mb/s class technology and a seven-device piconet topology.

For large data, the preferred path is:

Bluetooth control
    |
    +---- capability exchange ----+
                                  |
                           local IP endpoint
                                  |
                           TCP bulk stream

The controller can keep the Bluetooth session alive while the file transfer uses the higher-bandwidth local path.

Android documents Wi-Fi Direct as a direct peer-to-peer transport, including multi-device groups, and describes it as suitable for higher-throughput communication than Bluetooth. The project therefore keeps the bulk protocol transport-neutral so a Wi-Fi Direct bootstrap can be added without changing the transfer protocol.

## Failure semantics

There is no physically meaningful guarantee of zero wireless failures. Instead we target recoverability.

If Bluetooth drops:
- the affected session is closed;
- the peer remains in the roster;
- the reconnect scheduler retries with bounded exponential backoff;
- other peer sessions continue.

If TCP drops during a file transfer:
- the partial file remains;
- a later attempt sends the same transfer identity;
- the receiver reports the current partial length;
- the sender resumes from that offset;
- the complete object is accepted only after SHA-256 verification.

If the integrity check fails, the partial file is discarded and the transfer restarts cleanly.

## Performance quantities

For a transfer of B bytes completed in Delta t:

throughput_Mbps = 8 * B / Delta t / 1,000,000

For N control requests:

delivery_rate = successful_results / N

RTT sample:

RTT_i = t_result_i - t_send_i

Latency estimator:

S_i = alpha * RTT_i + (1-alpha) * S_(i-1)

Variance tracker:

V_i = beta * (RTT_i - S_(i-1))^2 + (1-beta) * V_(i-1)

Adaptive timeout candidate:

T_i = clamp(T_min, S_i + k * sqrt(V_i), T_max)

The implementation exposes EWMA, standard deviation and p95 over a rolling sample window so thresholds can be tuned from measurements rather than intuition.

## Next performance milestone

The protocol is now ready for a real transfer benchmark harness. That benchmark should compare:

1. Bluetooth-only bulk transfer.
2. Bluetooth control + local TCP bulk.
3. Bluetooth control + Wi-Fi Direct TCP bulk when available.

Measure median, p95, p99, sustained Mbps, reconnect time, transfer completion rate, and energy/MB.
