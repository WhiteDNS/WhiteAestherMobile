# Aether upstream provenance

- Repository: https://github.com/CluvexStudio/Aether
- Revision: `0e6f6a52`
- Version: Aether 2.0.0
- Imported: 2026-08-09; last taken to 2.0.0 on 2026-09-15.
- Carried from 2.1.0 (2026-09-25): `dc797fc` (the HTTP/2 data-plane probe is
  resent), `2509da0` (WireGuard's firewall and gfw profiles), `5556752` (the
  ClientHello split inside the server name, and a split TLS 1.3 registration
  fingerprint), `3788f4b` (WARP-in-WARP follows its inner tunnel's port).
  Psiphon and Tor inside the core, exit-country checks, statistics and the
  socket mark are not carried: this app runs Psiphon and Tor itself, and the
  rest serves the command-line client. Neither are the verified scan mode or
  the ring of remembered gateways, which meet this app's own record of how an
  endpoint was proven and need merging rather than copying.
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
`docs/AETHER_2_0_MERGE.md` records how the 2.0.0 merge was done and what was
decided in it.

Upstream's `tor` feature is deliberately not carried; see that document.

This tracked snapshot is the Android integration baseline. Android-specific
refactoring must preserve upstream copyright, trademark, and license notices.
