# Taking the engine to Aether 2.0.0

What was merged, what was decided in it, and what is still owed. Written after
doing it, because the expensive part of this merge was not the mechanics — it
was the four places where upstream's new code reverses a deliberate
Android-specific fix in this tree.

`native/aether` now holds `CluvexStudio/Aether` `0e6f6a52`, tagged `v2.0.0`,
four commits ahead of the `v1.8.0` this tree carried:

| Commit | What it brings |
|---|---|
| `846346ab` | Name gool hops by hand; lift the HTTP/2 and netstack windows |
| `311b5733` | Release v1.9.0 — `smoltcp` 0.12 to 0.14 |
| `a463ab71` | Tor via arti, with bundled transports; MASQUE-in-MASQUE |
| `0e6f6a52` | Documentation |

`quiche/` is untouched between the two tags, which kept the vendored
`native/aether/quiche` tree out of the merge entirely.

## How it was merged

Whole-file three-way merging is the wrong tool here. This fork inserts large
Android-only blocks — `EmbeddedConfig`, `prepare_embedded`, `run_embedded`,
`scan_embedded`, `run_warp_in_warp_embedded`, the identity export — between
upstream's own functions, and `git merge-file` reads that as one 780-line
conflicting region in `lib.rs` alone. Applying upstream's hunks individually
lands everything that does not overlap:

```sh
# base = upstream v1.8.0, theirs = upstream v2.0.0, both rustfmt'd first
rustfmt --edition 2021 base/*.rs theirs/*.rs
for f in theirs/*.rs; do
  diff -u "base/$(basename $f)" "$f" > p.diff
  (cd work && patch -F 3 -p0 "$(basename $f)" < ../p.diff)
done
```

Formatting both sides with this tree's rustfmt first is not cosmetic. This fork
is `cargo fmt` clean and upstream is not, so without it the fork's reflowed
lines collide with upstream's real changes: 37 conflicting hunks, of which 20
were whitespace. After normalising, six files that looked changed upstream —
`aethernoize.rs`, `api.rs`, `config.rs`, `routing.rs`, `sniff.rs`, `tls.rs` —
turned out to be byte-identical to 1.8.0, because upstream's change to them was
a formatting pass.

What was left: **22 of 28 files applied with no conflict**, and **nine
conflicting hunks** in four files. Use this recipe again next time.

## The decisions

### Socket protection moved into upstream's `egress`

2.0.0 adds `egress.rs`, a single choke point for creating outbound sockets,
there to set `SO_MARK` so a Linux box can route the engine's own traffic around
the tunnel it is building. That is the same job this fork does with
`socketprotect.rs` and `VpnService.protect()` — necessarily by a different
mechanism, because `SO_MARK` wants root or `CAP_NET_ADMIN` and an app has
neither.

So upstream independently arrived at the structure this fork's own comment
argued for, and **protection now happens inside `egress::apply`**. Every call
site takes upstream's form verbatim; `socketprotect` keeps the protector, the
counters and `protect`, and its `connect_tcp` / `connect_tcp_host` are gone
because `egress` does both — and upstream's `tcp_connect_host` is better than
ours was, since it tries every address a name resolves to rather than only the
first.

This is the resolution that makes the *next* merge cheap: none of those call
sites is forked any more.

### `upstream::configured()` stays uncached

2.0.0 changes it to return `Option<&'static Upstream>` from a `OnceLock`. **Ours
was kept**, and this is the hunk most worth not getting wrong:

> A proxy on the same device — which is what the Android app points this at when
> it chains one carrier through another — binds a fresh port every time it
> starts, so a value cached for the life of the process goes on dialling the
> port of a carrier that has since been replaced.

Taking upstream's would break every chained carrier on the second connect, and
silently: a refused connection from a proxy that is running perfectly well. The
call sites take the borrow instead. Upstream's redacting `Debug` impl came
across, which is an improvement and did not conflict.

### The SOCKS listener keeps its password and its source filter

Upstream split binding from serving (`bind_listener` + `serve(listener, ..)`),
added a handshake timeout, a client semaphore, TCP keepalive, and extracted
`read_request` and `accept_clients`. This fork had added, in the same functions,
a password compared in constant time and a source-address filter — LAN sharing —
plus the three-argument `warn_if_world_reachable` that does not cry wolf at a
share the user set up deliberately.

**Upstream's structure, with ours threaded through it.** The source check runs in
the closure handed to `accept_clients`, before `handle_client`, so a refused peer
never gets to send a password; `handle_client` and `read_request` each take an
`&Access`; the handshake timeout wraps `read_request` exactly as upstream has it.
`serve_connector` and `serve_http` pass `false` for `guarded`, because neither
checks anything.

`credentials_are_ignored_while_the_proxy_is_private` and
`the_listener_stays_on_loopback_until_sharing_is_asked_for` in the bridge's tests
both still pass, which is the check that matters here.

### The MASQUE tunnel split in two

`run_masque_tunnel` became `establish_masque` returning a `MasqueHop`, so two can
be stacked. The embedded path gained 2.0.0's two new transport settings —
`max_datagram`, and the QUIC v2 version-negotiation bait that goes out ahead of
the real handshake — and now sizes its netstack with `masque_tunnel_mtu()`, so a
tunnel carried over HTTP/2 gets 1500 rather than the 1280 that only matters when
a datagram has to stay whole. Upstream's `TUNNEL_MTU` is this fork's
`MASQUE_MTU`, because WireGuard has an MTU of its own here.

### Tor stays off, and its dependencies are not vendored

`tor` is an opt-in Cargo feature, and every use of `arti-client`, `tor-chanmgr`,
`tor-rtcompat` and `liblzma` sits behind `#[cfg(feature = "tor")]` in `tor.rs`,
which carries stubs for the other side. The feature and its six optional crates
are **dropped from the manifest**, the same way and for the same reason
`[lib] crate-type` is already forked:

- Upstream's bundled pluggable transports are Go binaries. One Go runtime per
  process is the rule this project established for IPtProxy in
  `native/chain/README.md`, and the chain already spends it.
- This app already has tor, as `info.guardianproject:tor-android`, in its own
  process, with its transports as separate executables.
- `bridges.rs` duplicates `core/MoatClient.kt`.
- `liblzma` with `features = ["static"]` would mean a C build for every Android
  ABI the first time anyone flipped the feature.
- Left in, they add roughly 4,800 lines to `Cargo.lock` for code never compiled.

`[lints.rust] unexpected_cfgs` names `tor` as a known value so the vendored
`cfg`s stay recognised without offering a switch that would fail to build.

### The fourth protocol is refused rather than approximated

2.0.0 adds `Protocol::MasqueInMasque`. The embedded entry points decided by
"MASQUE, otherwise WireGuard", which would have sent it down the WireGuard path
— provisioning a WARP account and building a tunnel nobody asked for, and
reporting success. They now match exhaustively, so the next protocol upstream
adds is a compile error here rather than a silent one, and `mim` returns a
message saying it is not carried yet.

## What is still owed

**MASQUE-in-MASQUE on Android — done, and untested on a phone.**
`run_masque_in_masque_embedded` carries it behind both endpoints, `prepare`
chooses the outer edge and the inner ones are derived from it at run time, and
it is a `TunnelProtocol` in the picker and the last lane of Automatic's engine
race. It has never established a real nested tunnel on a device; that is the
next thing it needs.

One gap it inherits rather than causes: `export_identity` carries the WARP
identity and its `secondary` sibling, not the MASQUE ones. Nested MASQUE adds a
second MASQUE account, so a user who backs up, reinstalls and connects with it
pays one more Cloudflare registration than the backup was meant to save them.
Fixing that means a new `IDENTITY_EXPORT_VERSION` and a migration, which is its
own change.

**The toolchain moved.** `native/rust-toolchain.toml` and
`.github/actions/android-toolchain/action.yml` both go from 1.88.0 to 1.98.0,
because 2.0.0 declares `rust-version = "1.98"` and cargo refuses older. Note
that clippy at 1.98 flags things in upstream's own code (`account.rs`,
`aethernoize.rs`, `masque.rs`); CI only gates clippy on `native/android-bridge`,
which is clean, and those were left alone rather than fixed into a future
conflict.

## What was verified, and what was not

Green here: `cargo fmt --check` and `cargo clippy --all-targets -- -D warnings`
on the bridge, `cargo test` on both — **307 engine tests and 8 bridge tests**,
up from 237 — and the full Android unit suite and `lintVitalStableRelease`.

Not verified here: **the Android cross-build**, because this machine has no NDK.
CI does it on every push — `assemblePreviewDebug` pulls in `cargoBuildAndroidDebug`
with no ABI filter — so a push proves it.

Also not verified, and the reason this wants a device pass before it ships:

- Aether direct, and Aether behind Psiphon, on Wi-Fi and on mobile data.
- LAN sharing on with a password set: a client on the LAN must be asked for it,
  and a client off the LAN must be refused.
- Two consecutive connects with a carrier chain, to prove the upstream proxy
  port is re-read rather than cached.
- WARP-in-WARP teardown, which is where upstream's own fix landed.
- `docs/DEVICE_TEST_PLAN.md` for the rest.
