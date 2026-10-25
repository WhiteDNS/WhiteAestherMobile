# Aether upstream provenance

- Repository: https://github.com/CluvexStudio/Aether
- Revision: `d58f9d568083129ce2c767c85054021eb3ee6a38` (`v2.4.0`).
- Version: Aether 2.4.0 network changes, adapted for the Android embedded entry points.
- Imported: 2026-08-09; network core updated to 2.4.0 on 2026-10-25.
- The 2.4 integration adds authenticated API-name/edge fallback, resumable WARP
  activation, updated gateway inputs, extended TLS fingerprint controls, a third
  MASQUE pin and early ownership of embedded SOCKS ports. Boring/tokio-boring
  use 5.3. Android remains API 26; its reconnect coordinator and independent
  carrier processes are retained. See `docs/AETHER_2_4_MERGE.md` for the boundaries.
- The embedded dispatcher, identity store, task-local WOM registration proxy,
  socket protection, cancellation and authenticated LAN sharing remain this
  fork's. ECH now covers H2/H3 and WARP registration, with configurable DNS,
  offerability checks and retry keys. TLS fingerprint options and Happy Eyeballs
  are carried. The gateway history holds eight validated routes with the Android
  proof of framing, ECH and TLS settings; verified scan prefers known inner
  gateways and different ranges when possible.
- Certificate pinning stays enabled by default. API/DoH HTTPS validates system
  certificates. The existing fragmented registration fallback remains available
  when ECH and a custom enrollment address are not requested. Fragment defaults
  stay 16–32 bytes with SNI splitting to preserve existing profiles.
- Upstream helper modules are vendored, but Android continues to run its own
  Psiphon/Tor carriers and traffic meter. Exit-country enforcement is not wired
  into embedded TUN sessions. The optional `tor` dependencies and upstream C FFI
  remain omitted. The CLI retains this fork's protocol names.
- License: GNU Affero General Public License v3.0; see `LICENSE`.
- Android recovery additions (2026-10-10): WARP WireGuard over MASQUE is
  adapted from `patterniha/Aether` 2.3.0, commit
  `512726803eb7245802d0d877eb9682243a31bac5`. The embedded TUN lifecycle,
  identity store, cancellation and socket protection remain this fork's.
  The `wg`/`wiw`/`gool` meanings of existing profiles are preserved; the new
  embedded transport is `wom` (`wg-over-masque` in the engine).
  TLS record/write splitting follows the algorithm in `patterniha/Xray-core`
  commit `aa7886b51c14` (`transport/internet/finalmask/fragment/conn.go`), under
  that repository's MPL-2.0 license; the Rust implementation is original.

Earlier releases of this app name `MatinSenPai/Aether`, which carries the
revision shipped up to v1.2.1 and stops at its own v1.3.0. The two hold
identical objects for the tags they share; 1.7.0 onwards come from CluvexStudio.
`THIRD_PARTY_NOTICES.md` in the repository root is the fuller record, and
`docs/AETHER_2_0_MERGE.md` records the earlier merge;
`docs/AETHER_2_3_MERGE.md` records the previous update;
`docs/AETHER_2_4_MERGE.md` records this update and its validation.

Upstream's `tor` feature is deliberately not carried; see that document.

This tracked snapshot is the Android integration baseline. Android-specific
refactoring must preserve upstream copyright, trademark, and license notices.
