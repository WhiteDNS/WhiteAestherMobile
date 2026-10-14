# Aether 2.3.0 Android integration

The network modules are taken to upstream tag `v2.3.0`, commit
`6175b67df370ab856bcee07fe85b524031903956`. Android's embedded dispatcher and
identity store are retained rather than replaced with upstream's CLI lifecycle.

## Resulting behavior

- ECH works on H2 and H3, including the outer H2 hop of WOM, endpoint discovery,
  validation, reconnect and WARP registration. A requested ECH connection fails
  before sending the server name if its key cannot be obtained or offered.
  Server retry keys are retained for subsequent handshakes.
- Advanced settings expose ECH resolver/domain, TLS cipher suites, key groups,
  GREASE and an optional registration endpoint. Existing custom ECH and fragment
  fields round-trip unchanged. Disabling ECH clears active lookup overrides but
  keeps their saved text. Android's empty resolver uses protected DoH at
  `https://1.1.1.1/dns-query`, so H2+ECH does not depend on UDP reaching port 53.
- Automatic adds H2+ECH and remembers that route. Existing route names and the
  meanings of `wg`, `wiw`, `gool` and `wom` remain compatible.
- Eight successful gateway records retain their framing and TLS proof. Their
  fingerprint covers the record shape, fragment parameters, cipher suites,
  groups, GREASE and ECH policy. Records are saved on data-plane readiness;
  older files still receive a validation attempt.
- Happy Eyeballs prevents a stalled address family from consuming the entire
  TCP connection budget. All outbound sockets still pass through `egress::apply`
  and Android's protector. WOM's inner registration remains task-local. API/DoH
  HTTP/2 drivers are owned by a task guard so cancellation releases their sockets
  even while writes are backpressured; a local duplex test verifies this.
- Verified discovery prefers known inner gateways and different ranges, with
  bounded fallback candidates; it does not promise that a historical measured
  address is reachable on the current network.

## Deliberate differences from upstream

Pinning remains the default for MASQUE. The new HTTPS helper authenticates API
and DoH servers with system trust roots, including Android's Conscrypt/system CA
directories. A TLS fingerprint does not disable certificate verification.

The fragmented registration fallback remains for connections without ECH or a
custom enrollment address. It is never a downgrade path for failed ECH. It uses
the pre-existing alternate fingerprints, rather than claiming to share the
modern direct route's fingerprint. Transient HTTP replies retain Retry-After
and the identity store's existing registration budget.

Fragment defaults remain 16–32 bytes with SNI splitting. Records/Compact retain
their compatibility fingerprint unless the user supplies explicit cipher/group
settings. The identity backup format remains version 2.

Android uses its existing Psiphon and Tor services. Upstream's optional Tor
dependency graph and C FFI remain excluded. The exit-country CLI policy is not
attached to embedded TUN sessions; it needs a separate implementation that can
probe through the completed tunnel without opening a second routing stack.

## Dependencies and validation

Boring, tokio-boring and quiche use 5.2, backed by the official boring-sys 5.2.0
crate. The Windows NDK `.exe` normalization is retained; the NASM include path
is quoted for host workspaces containing spaces. Rust remains pinned to 1.98.
Both engine and bridge lockfiles are updated, and CI/release now run the full
engine library suite as well as bridge tests.

Required checks are engine and bridge tests, formatting, bridge clippy, Android
unit tests/lint and APK builds for arm64-v8a, armeabi-v7a and x86_64. Tests use
local TLS/DNS/proxy fixtures; live Cloudflare tests remain opt-in. Physical-device
acceptance and connection success on affected networks require the checks in
`DEVICE_TEST_PLAN.md` and are not established by local builds.

Local verification on 2026-10-12: 486 engine tests, 13 bridge tests and 292
Android tests passed; six live-network engine tests were left disabled. Rust
formatting, bridge clippy with warnings denied, and Android lint passed. Final
preview APKs were built for all three ABIs plus universal, their signatures were
verified and their packaged cores matched the final stripped libraries. All six
carrier/chain libraries and Psiphon's bootstrap asset were present. This first
packaging check missed BoringSSL's dynamic C++ runtime dependency; a device then
reported `dlopen failed: library "libc++_shared.so" not found` on Connect.

The corrected build stages `libc++_shared.so` for each selected ABI from the same
NDK used by cargo-ndk, for both preview/stable and debug/release builds. The new
`tools/verify_native_dependencies.py` check rejected the original ARM64 APK for
that missing dependency. CI and release now check every packaged ELF's
architecture and `DT_NEEDED` dependencies against the libraries in that ABI and
the Android API 26 system-library stubs, including universal APKs and release
bundles. The corrected preview APKs passed signature and dependency checks:
nine native libraries per ABI and 27 in the universal APK. The stable-release
task plan also stages the runtime before merging native libraries; a signed
release was not built or published. No Android device was connected during
local validation, so physical-device/network acceptance is still pending.

## TLS settings input correction

The five new text fields initially read their displayed values directly from
asynchronously persisted settings. Typing was reset before a save returned.
They now hold an immediate, saveable editing value and ignore older storage
acknowledgements while focused; stored changes still reach unfocused fields.

Verification on 2026-10-13 used the Android API 37 emulator. The original APK
failed four of five focused UI tests. The corrected APK passed all five TLS/ECH
tests plus three existing LAN-sharing tests. The tests cover character-by-character
typing while storage is pending, late acknowledgements, clearing, inserting a
whole value and external changes while unfocused. All 292 preview Android unit
tests and preview lint passed. The new split and universal APKs passed signatures,
ELF architecture and native dependency checks. Physical-device/network acceptance
remains separate from this emulator input verification.
