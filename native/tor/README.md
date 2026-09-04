# Tor carrier

Tor as an alternative to the Aether engine. It finds its own way out through
three relays, and mihomo routes the interface into the SOCKS5 listener it
produces — the same arrangement Psiphon uses, and deliberately the same shape in
the code.

Nothing to build. tor arrives as `info.guardianproject:tor-android`, Guardian
Project's Android build and the one Orbot ships, with `jtorctl` for its control
protocol. Both are pinned in `app/build.gradle.kts`.

It requires `compileSdk 37`. `targetSdk` deliberately stays at 36: raising that
changes behaviour across every permission and background rule in the app, and it
is not something to carry in behind a dependency bump.

## Its own process

`TorService` is declared in this app's manifest with `android:process=":tor"`,
which overrides the library's own declaration, and `TorCarrierService` sits in
the same process to drive it.

Unlike Psiphon this is not forced — tor is C and has no Go runtime to collide
with mihomo's. It is still right. tor is loaded through a JNI library with
process-wide static state and a lock, so a failure in it should take down
something restartable rather than the process holding the interface. And the
pluggable transports, when they arrive, are separate executables that have to be
launched and reaped by whoever owns tor.

## No UDP, declared rather than discovered

Tor carries TCP only. `Carrier.TOR.carriesUdp` is false, and mihomo is
configured from it: the carrier proxy is declared `udp: false` and a
`NETWORK,udp,REJECT` rule sits above the default route.

This matters more than it sounds. A proxy declared as carrying datagrams that
cannot swallows every one of them, and a phone experiences that as DNS and QUIC
hanging while TCP works — which is the hardest shape of broken to recognise.
Refused, a resolver falls back to TCP and a browser falls back off QUIC, both
within a round trip. DNS still resolves because the chain's resolvers are
DNS-over-HTTPS, which is TCP.

## The transports, and why we start them

obfs4 and snowflake, both working. Built from source by `setup.ps1` and
`build.ps1` as ordinary Go programs cross-compiled for Android, shipped as
`liblyrebird.so` and `libsnowflake.so` -- executables named like libraries
because that is the only form Android extracts and leaves executable, which is
also why `jniLibs.useLegacyPackaging` being on is a prerequisite here rather
than only a size decision.

tor would normally launch them itself: `ClientTransportPlugin obfs4 exec <path>`.
This build cannot. It aborts inside `pt_parse_transport_line` before logging a
word, which is what a libtor built without the fork it needs looks like; the
identical torrc with `socks5` in place of `exec` starts cleanly. Measured, not
assumed.

So `PluggableTransport` does tor's half of the managed-proxy protocol -- sets the
`TOR_PT_*` environment, starts the binary, reads the `CMETHOD` line naming the
loopback port -- and the torrc hands tor that port. This is the same arrangement
Orbot arrives at through IPtProxy, by a different road.

meek is deliberately not offered. lyrebird starts it and tor accepts it, and then
it never finishes bootstrapping: seven minutes on the public bridge, repeatedly,
where obfs4 and snowflake took under a minute from the same network. The plumbing
is generic, so it is a two-line change when there is a bridge worth pointing at.

## Bridges

Three sources, in increasing order of what they survive.

**Built-in.** Tor's own published list, refreshed and health-checked by
`refresh-bridges.ps1`. Public by design, and therefore the first addresses a
censor blocks -- in the places this carrier matters most they are already gone.
The first version of this list was written from memory and two of its three
bridges had been dead long enough to be unreachable from an uncensored network,
which is why the refresh script exists and prints reachability.

**Pasted.** Whatever the user was given, from bridges.torproject.org, the
`@GetBridgesBot` on Telegram, or email. Parsing is forgiving: a Telegram reply
arrives with a greeting around it, and losing the bridges to a stray line is a
failure the user cannot debug.

**Fetched, in one tap.** `MoatClient` asks Tor's own recommendation service --
the one Tor Browser's Connection Assist uses -- which answers with the transports
it currently recommends for a country, each with lines from BridgeDB rather than
from the public list. No CAPTCHA.

Two things make that work here that do not work in a browser. The country asked
about is the *network's*, from the telephony operator, not the exit's: asked
through a tunnel the service would otherwise be told about Singapore and answer
about Singapore. And the request itself goes through whichever carrier is
already running, because bridges.torproject.org is blocked in most of the places
its answer is wanted -- which is something this app has and Tor Browser does not.

Measured for Iran: the service recommends webtunnel, and a session behind it
built a circuit in seventeen seconds.

## Bootstrapped is not the same as listening

`TorService` broadcasts `STATUS_ON` when tor's control connection comes up, which
is before it has a consensus or a circuit. Reporting that as connected is how a
slow transport ends up looking connected while carrying nothing -- which is
exactly what meek did. `TorCarrierService` polls `status/bootstrap-phase` and
only reports CONNECTED at `PROGRESS=100`.

## Licence

tor is BSD-3-Clause, and so are `tor-android` and `jtorctl`. Recorded in
`THIRD_PARTY_NOTICES.md`.
