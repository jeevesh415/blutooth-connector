# Protocol and Systems Design

## State machine

DISCONNECTED -> CONNECTING -> LINKED -> NEGOTIATING -> READY -> CLOSED

Bluetooth RFCOMM is the control/discovery transport. Bulk data is a separate, explicitly advertised local-network service.

## Framing

Control frames use a 4-byte big-endian signed length followed by UTF-8 JSON. Frames must be between 1 and 65536 bytes and must use the exact supported protocol version.

## Sequencing and reliability

Each ordered RFCOMM session owns its own transmit sequence. Commands also carry a UUID requestId. Receiver-side deduplication is scoped by peer address plus requestId, preventing one peer from accidentally satisfying another peer's retry.

Command retries use the peer's measured RTT distribution to derive a bounded timeout. The timeout is an operational heuristic, not a latency guarantee.

## Control plane / data plane separation

Control plane:
- peer establishment and capability exchange
- commands, results and errors
- heartbeat
- state synchronization hooks

Data plane:
- file and other large payload streams

Keeping large streams off RFCOMM prevents bulk serialization from blocking interactive commands.

## Bulk protocol

BCL1 remains an internal legacy implementation, but the active TCP server accepts only BCL3 multipath transfers.

BCL3 authenticates every chunk with HMAC-SHA256 using the 256-bit bearer secret delivered over the secure Bluetooth control channel. The bearer secret itself is not transmitted over TCP.

Each chunk then uses AES-256-GCM. The AES key is derived from the bearer secret and random transferId. Every chunk has a fresh random 96-bit GCM IV. The canonical chunk descriptor is used as authenticated associated data and is also covered by the HMAC authorization proof.

Wire order:

MAGIC, VERSION, authorizationTag,
transferId, fileSize, offset, length,
chunkIndex, chunkCount, SHA-256, fileName,
GCM IV, ciphertextLength, ciphertext

The receiver authenticates metadata before accepting encrypted bytes, decrypts/authenticates the chunk, writes it at the specified offset, and only promotes the partial object after whole-file SHA-256 verification.

## Path scheduling

For paths p_i, the scheduler maintains short throughput and RTT histories. A DFT projection estimates high-frequency variability and feeds a softmax allocation utility.

This is an application-layer scheduler. It cannot enlarge a radio's PHY channel or make two addresses physically independent. Android Network-specific sockets are used when the platform exposes matching Network objects.

## Android network paths

The path catalog uses ConnectivityManager network snapshots and associates advertised endpoint transports with Android Network objects. When a matching Network exists, the sender uses that Network's SocketFactory, keeping the candidate path within that Android network. Wi-Fi Direct and Wi-Fi Aware require platform-specific session establishment; an interface label alone is never treated as proof that a path exists.

## Security boundary

Android's secure RFCOMM API provides an authenticated/encrypted Bluetooth link when the platform can establish an authenticated link key. The bulk bearer secret is distributed only over that channel.

The remaining trust decision is user/device pairing. There is no unattended public-key enrollment in the current protocol.

## Measurements

Required benchmarks on real Android devices:
- control RTT p50/p95/p99
- command delivery rate
- reconnect time
- bulk Mbps
- path utilization
- chunk retry rate
- integrity failures
- energy per MB
- convergence time after link interruption

The mathematical scheduling layer should be tuned from measured distributions rather than assumed radio properties.
