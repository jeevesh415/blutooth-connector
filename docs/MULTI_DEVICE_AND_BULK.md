# Multi-device and bulk-transfer engineering

## Topology

Phone A can maintain independent Bluetooth control sessions to several Phone B peers.

Each session owns:
- one secure RFCOMM socket;
- ordered control frames;
- heartbeat and bounded reconnect state;
- RTT metrics;
- capability discovery;
- its own transmit sequence.

The application enforces a seven-peer policy for Bluetooth Classic. That is an application safeguard, not a promise that every handset can maintain seven RFCOMM links.

The foreground ConnectionService owns the live transport graph. MainActivity binds to the service and only observes/commands it.

## Control path

Bluetooth RFCOMM is the current interactive control path. Large data is deliberately kept away from it.

The connection uses Android's secure RFCOMM APIs. The Bluetooth platform performs link authentication and encryption when an authenticated link key can be established.

Every outbound frame gets a fresh monotonically increasing per-session sequence. Results and errors are assigned their own outbound sequence, so a slow command handler cannot emit an older sequence after a newer heartbeat and have its valid result discarded.

Commands carry a UUID requestId. Deduplication is scoped to the peer session namespace and expires after a bounded TTL.

## Bulk path

Large objects use BCL3 over mutually reachable local IP networks.

BCL3 provides:
- 1 MiB chunks;
- concurrent bounded in-flight work;
- per-chunk acknowledgements;
- retry of only the failed chunk;
- random-access reconstruction;
- whole-file SHA-256 verification;
- HMAC-SHA256 authorization proof;
- AES-256-GCM chunk encryption;
- a fresh random 96-bit GCM IV for every chunk.

The endpoint bearer secret is delivered through the secure Bluetooth control channel. It is not transmitted over the TCP connection.

The active TCP server rejects the legacy BCL1 protocol so an older plaintext transfer cannot bypass the BCL3 security model.

## Adaptive multipath

The sender treats each reachable endpoint/network combination as a candidate path.

When Android exposes a matching Network object, the socket is created from that Network's SocketFactory. The sender therefore does not silently collapse all candidate paths onto the device's default network.

The scheduler maintains short throughput/RTT histories and a Fourier-derived high-frequency instability term. Work is bounded to a small in-flight window so completed chunks can update the scheduler before later chunks are assigned.

A path failure raises a failure estimate and immediately reduces its scheduling utility. Successful observations decay that penalty.

The scheduling mathematics changes allocation decisions; it does not create bandwidth that the radio, chipset, AP, or operating system does not expose.

## Path independence

Network-interface labels are only metadata. Two endpoints can still share the same Wi-Fi radio, access point, channel, chipset queue, or physical route.

Meaningful bandwidth aggregation therefore requires genuinely independent usable paths, for example separate Wi-Fi/peer-to-peer connectivity that Android actually exposes as distinct Network objects.

## Failure model

Bluetooth failure:
- current session closes;
- other peer sessions continue;
- reconnect uses bounded exponential backoff;
- commands waiting on the dead session fail rather than hanging forever.

TCP path failure:
- only the affected chunk is retried;
- successful chunks remain valid;
- the scheduler penalizes the failing path;
- final promotion requires complete-file hash verification.

Incomplete receiver states are bounded and stale partial transfers are removed.

## Current Android networking boundary

The repository can recognize Wi-Fi LAN, Wi-Fi Direct, Wi-Fi Aware, and Ethernet network types when Android exposes them. It does not claim that a path is active merely because an interface has a matching name.

Wi-Fi Direct and Wi-Fi Aware still require their platform-specific discovery/session establishment APIs. A real two-phone test is required before those transports can be called production-ready.

## Metrics

For control:
- p50/p95/p99 RTT;
- command completion rate;
- reconnect time;
- timeout rate.

For bulk:
- Mbps;
- chunks completed per path;
- retry rate;
- path failure rate;
- final integrity failures;
- energy per MB.

The repository includes JVM coverage for framing, scheduler behavior, BCL request encoding, BCL3 cryptography/tamper detection, and a two-path loopback reconstruction test.
