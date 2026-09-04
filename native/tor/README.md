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

## Pluggable transports are missing, and why

Direct Tor only, for now. Where tor is blocked outright — which is the case this
carrier is most wanted for — it will not connect.

The obvious dependency is IPtProxy, which packages lyrebird (obfs4, meek,
webtunnel) and snowflake. It cannot be used here: it is a gomobile library, so
it ships `libgojni.so` and the `go.*` support classes, and so does Psiphon. Two
of them in one APK is a duplicate-class failure at build time, and worse, one
`libgojni.so` quietly winning over the other at packaging time — which would
break whichever carrier lost, at run time, on a device.

The way in is the way tor itself expects: transports as their own executables,
launched with `ClientTransportPlugin`. lyrebird and snowflake are ordinary Go
programs, and a Go program cross-compiled for Android with `-buildmode=pie` and
shipped in `jniLibs` as `lib*.so` is extracted to `nativeLibraryDir` and can be
executed from there. That extraction is why `useLegacyPackaging` being on is a
prerequisite rather than only a size decision.

That build is not written yet.

## Licence

tor is BSD-3-Clause, and so are `tor-android` and `jtorctl`. Recorded in
`THIRD_PARTY_NOTICES.md`.
