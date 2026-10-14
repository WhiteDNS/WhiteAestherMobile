# Connection recovery, 2026-10-10

This update targets failed or slow connection establishment. It does not
establish that a reported six-packet restriction affects WARP or that any
transport can connect through a complete loss of international connectivity.

Automatic connection now gives a previously successful Aether route 30 seconds
before trying the carrier race, rather than 150 seconds. Tor joins that race
after 15 seconds, while Aether and Psiphon begin immediately. Short Aether
rungs have 45-second windows and use a 12-second discovery budget with eight
concurrent probes and three seconds per probe. Deep/manual searches retain
their original budgets. Account registration and validated data-plane checks
are still required; a fast rung can be interrupted while a cold registration
is slow, and the later full search remains available.

The Aether lane includes two additional H2 ClientHello shapes, IPv6 discovery
when the user allows IPv6, and WARP WireGuard over MASQUE before the deep search
and nested MASQUE fallback. A successful shape is remembered as its own route.
IPv6-only discovery must not accept an IPv4 endpoint from the cache. The saved
assigned endpoint is tested before a potentially stalled device API refresh.

`records` splits ClientHello into TLS records with payload lengths 5, 94, then
1 byte, bounded to 355 records, then sends TCP writes of 109, 1, ... bytes,
bounded to 355 writes with a 1 ms interval. `compact` uses payload lengths 0,
104, then 1 byte (bounded to 1024 records) and TCP writes of 114, 1, ... bytes,
bounded to 11 writes. These are recovery profiles, not universally working
offsets. The handshake payload and any subsequent TLS records are preserved.
Short underlying writes and backpressure are handled through a bounded pending
buffer. The original ClientHello write is acknowledged only after the rewritten
bytes have drained, preserving BoringSSL BIO retry/flush semantics.

Shaped H2 uses a deterministic BoringSSL compatibility ClientHello, with GREASE
off and ordinary X25519/P-256/P-384 groups unless the user overrides them. TLS
1.3, ALPN h2, client authentication and server certificate pins are preserved.
It does not claim to reproduce PattNG's Go TLS `unsafe` fingerprint. The
existing SNI-based TCP fragmentation remains available independently.

The new `wom` transport carries WARP WireGuard through MASQUE over H2 in the
Android app. Both servers are Cloudflare WARP endpoints. A fresh inner identity
is registered and activated through the outer tunnel using a task-local proxy;
the process's outer carrier setting is not changed. The existing dedicated
WireGuard-inner slot can be reused, so no additional registration is spent just
to replace an already usable inner key. Registration is persisted before
activation and activation is recorded separately, so interruption retries
activation rather than generating another key. Backup format remains valid;
restoring a backup can require activation confirmation again.

Inner endpoints are verified through the outer tunnel, starting with the
assigned WARP edge on port 2408 and then bounded known WARP candidates. Connected
is reported only after both hops have passed data-plane validation. The TUN
uses inner identity addresses and MTU 1200; device-wide direct TUN mode is IPv4
only at that MTU, as existing WARP-in-WARP already is. Proxy/chain mode uses the
existing userspace stack. Dropping or cancelling the session aborts outer,
inner, forwarder and endpoint tasks.

Validate engine/bridge unit tests, Android unit tests, lint and all ABI builds.
Network success rates require measurements on affected networks; local tests
prove packet integrity and application behaviour, not population coverage.

## Carrier lifecycle and manual TLS controls

Carrier sequences such as Aether → Psiphon do not require node subscriptions.
An independently enabled exit-node chain still does: an empty chain now fails
before starting carriers or creating an interface, rather than retrying that
configuration error. Routes offers “Use carriers only” to explicitly disable
an empty exit-node chain while keeping the selected carriers.

Tunnel ownership remains with the app until configuration has succeeded and
the native routing engine is actually called. Configuration failures and
cancelled startup close the unclaimed descriptor exactly once. Stop and profile
replacement can close pending interfaces immediately, even while a native
preparation is unwinding. A stale stop or failure cannot clear a newer session.
The native run slot stays occupied until its owning call finishes cleanup;
signalling stop does not free that slot. Failed competing runs cannot remove
another run's cancellation handle. Carrier replacement joins the stopped native
run before preparing its successor, and cancellation takes priority over ready
notifications. Detached descriptors are also closed on native setup errors.
Changing the selected carrier, profile, transport or exit-chain switch replaces
an active session; typing into custom fields saves settings for the next connect.

Traffic → Advanced exposes Automatic, TLS records, Compact and Custom splitting.
Custom splitting accepts a fixed byte count or ascending range (1–16384), a
delay in milliseconds or ascending range (0–100), and a server-name split switch.
These customize the existing H2 TCP-write fragmentation. The two record presets
use the bounded TLS-record shaping described above. Automatic recovery preserves
explicit manual overrides. ECH offers automatic discovery or a supplied Base64
ECHConfigList, checked for encoding and list boundaries before connecting.
In 1.11.0, ECH applied only to H3. The Aether 2.3.0 integration also sends ECH
on H2, the outer WOM tunnel and WARP registration; see `AETHER_2_3_MERGE.md`.
The ECH cache is keyed by the configured policy so a profile change takes effect
without waiting for cache expiration. Preferences retain custom text when its
switch is disabled, and disabled overrides are omitted from active configuration.
