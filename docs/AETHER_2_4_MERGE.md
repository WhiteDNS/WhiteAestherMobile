# Aether 2.4.0 Android integration

The Android core incorporates the network changes of upstream tag `v2.4.0`
(release commit `d58f9d5`) over the existing `v2.3.0` integration. Android keeps
its own embedded dispatcher, identity store, VPN socket protection, endpoint
discovery coordinator, and Tor/Psiphon services.

## Identity and API lifecycle

The API tries `api.devices.cloudflare.com` and `api.cloudflareclient.com`, with
the new Cloudflare edge addresses as additional authenticated routes. Only TLS
handshakes race. A request is sent on the winning connection; an ambiguous
request failure never replays a registration on a second route. A non-API error
page can select another route without declaring the identity dead. The answering
API name/edge is remembered within a native operation.

New registrations are persisted before enrollment or activation. The store
records `activation_pending`, `activation_retry_at`, and the acknowledged
`warp_enabled` state. An interrupted activation reuses the same identity and
certificate. A 429 preserves the full server hold across restart. Legacy and
imported completed identities are not forced through activation on each start.
Optional activation fields travel in format-2 backups; older backups remain
readable. Enrollment metadata, pending enrollment keys, the single-device store,
registration budgets and full numeric/HTTP-date Retry-After handling remain.

Saved Android `enrollAddress` values now map to `AETHER_API_ADDRESS`. The engine
also accepts the old `AETHER_ENROLL_ADDRESS` / `--enroll-address` compatibility
form. API host/SNI and MASQUE SNI can be configured separately. Explicit names,
addresses and ECH remain strict policies. Every session clears previous overrides.

## TLS and endpoint discovery

Boring, tokio-boring and quiche use 5.3. The active locally patched crate is
`native/third-party/boring-sys-5.3`; the 5.2 directory remains an unused rollback
reference. The Windows NDK executable normalization and quoted NASM include path
are retained. Rust stays pinned to 1.98 and Android stays at API 26.

The core supports the upstream TLS 1.3 suites, key shares, signature algorithms,
certificate compression, ALPS, trust-anchor request, server padding and extension
controls. The JNI configuration and stored TLS options carry these values;
the advanced panel exposes API host/SNI, MASQUE SNI, TLS 1.3 suites and key shares.
The remaining fingerprint controls are available through the native configuration
and CLI. They do not turn off API/DoH authentication or MASQUE pinning. The new
MASQUE pin joins the existing pins and expiry checks. Cache proofs cover all
new fingerprint settings and MASQUE SNI.

The 297 upstream IPv4 gateways and updated IPv6/team ranges feed the Android
scanner. Assigned endpoints, fair address-family/port scheduling, optional CDN
expansion, cancellation, telemetry, traffic confirmation and network-scoped hint
validation remain. The default MASQUE SNI is still `www.cloudflare.com`.

Embedded SOCKS listeners are held before identity/tunnel startup and passed to
the serving task without rebinding. Source restrictions, LAN authentication and
the private internal-proxy policy remain. Invalid upstream proxy settings now
fail before provisioning rather than silently connecting directly.

## Deliberate integration boundaries

Android reconnects and automatic carrier fallback remain owned by the Kotlin
service/coordinator. Upstream's CLI reconnect loop and its five-minute gateway
rest policy are not substituted for that lifecycle. Native CLI Tor/Psiphon
process management and the upstream C FFI are not Android's carrier/FFI paths
and remain excluded from this port. Existing Android `gool` / `wiw` / `wom`
meanings and identity slots remain compatible.

## Verification

The 313 Android unit tests, preview lint, 530 native engine tests and 16 JNI
bridge tests passed on 2026-10-25. Bridge Clippy passed with warnings denied,
and Rust formatting passed. Five live-network tests remained ignored. New local
fixtures cover API fallback versus identity refusal, ambiguous registration
replies, interrupted activation, persisted activation holds, activation backup,
early SOCKS port ownership and JNI setting reset. The host JNI tests reused the
same freshly built, post-quantum-patched BoringSSL 5.3 library as the engine.

Preview debug APKs were built for arm64-v8a, armeabi-v7a, x86_64 and universal.
Their signatures, ELF architectures and native dependency closure passed against
the NDK API-26 stubs: nine native libraries per split APK and 27 in universal.
Every packaged core matched its final stripped build output; the generated JNI
libraries also matched Cargo's output for each ABI. APK hashes and core hashes
are saved in the local review artifact `apk-verification.json`.

The Proxy card was checked visually in English and Persian, dark and light
themes, at 320–768 dp widths and 1.6x text scale. Five component tests and
16 navigation, LAN-sharing, clipboard and TLS-input instrumentation tests passed
on the API-37 emulator. The clipboard regression confirms that LAN sharing
still copies the usable loopback address rather than the wildcard bind address.
Preview unit tests, lint and APK/test-APK assembly passed again after the UI change.

No Cloudflare device is registered by the local regression suite. Live-network
tests remain ignored. Physical phones and affected carriers still require field
acceptance, independently of local tests and APK compilation. The existing
publishing workflow is unchanged; this integration is prepared for v1.15.0.
