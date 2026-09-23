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

The foreground ConnectionService owns the MultiDeviceManager so the Activity is no longer the owner of the live transport graph. Closing or recreating the UI therefore does not intentionally tear down every peer connection.

## Reliability stack

Physical/link layer:
Bluetooth's lower layers already provide link reliability.

Session layer:
Every peer has a heartbeat every 5 seconds. A peer that produces no received traffic for 15 seconds is treated as unhealthy. Reconnect uses bounded exponential backoff:

1, 2, 4, 8, 16, 30, 30, ... seconds

Application sequencing and idempotent command identifiers remain per ordered RFCOMM session.

Bulk-data layer:
Bulk transfer uses TCP where a mutually reachable private IPv4 endpoint is advertised. TCP supplies ordered reliable delivery; the application adds a random 256-bit endpoint token and final SHA-256 verification.

## Why Bluetooth is not the bulk path

BR/EDR bandwidth is shared at the physical-link level. Making several applications push large streams simultaneously over Bluetooth therefore competes for the same radio resources.

For large data, the preferred path is:

Bluetooth control
    |
    +---- capability exchange ----+
                                  |
                         local TCP path(s)
                                  |
                           striped chunks

Each chunk is acknowledged independently, so a failed path can be retried without retransmitting successful chunks. The receiver writes completed chunks at explicit file offsets and only finalizes the object after whole-file SHA-256 verification.

## Path independence

The endpoint list is derived from active, non-virtual interfaces and private IPv4 addresses. Interface names are classified as:

- wifi-lan
- wifi-direct
- wifi-aware
- ethernet
- tcp-local

The scheduler treats these as candidate path identities, but an interface label is not proof of an independent physical link. Two addresses can still traverse the same Wi-Fi radio, AP, chipset queue, or RF channel.

True aggregate throughput requires genuinely independent usable paths. The code therefore avoids claiming “zero lag” or guaranteed bandwidth multiplication.

## Failure semantics

If Bluetooth drops:
- the affected session is closed;
- the peer remains in the retry roster;
- reconnect uses bounded exponential backoff;
- other peers continue independently.

If one TCP path drops during a file transfer:
- that chunk is retried;
- successful chunks remain valid;
- other paths continue;
- finalization still requires complete SHA-256 verification.

If integrity verification fails, the partial object is not promoted to the final filename.

## Performance quantities

For a transfer of B bytes completed in Delta t:

throughput_Mbps = 8 * B / Delta t / 1,000,000

For N control requests:

delivery_rate = successful_results / N

RTT sample:

RTT_i = t_result_i - t_send_i

Latency estimator:

S_i = alpha * RTT_i + (1-alpha) * S_(i-1)

The implementation exposes EWMA, standard deviation and p95 over a rolling sample window so thresholds can be tuned from measurements.

## Next validation milestone

The repository now has unit tests for framing, path allocation, and bulk-request encoding. The next runtime benchmark on two or more real Android devices should compare:

1. Bluetooth control + single local TCP path.
2. Bluetooth control + concurrent local TCP endpoints when they are actually independent.
3. Bluetooth-only control under load.

Measure median/p95/p99 command latency, sustained Mbps, chunk retry rate, reconnect time, path utilization, and energy/MB.
