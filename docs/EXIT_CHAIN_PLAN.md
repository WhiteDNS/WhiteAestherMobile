# Exit chain on Android — plan

Supersedes the open questions in `EXIT_CHAIN_DESIGN.md`. That document reasoned
from first principles about whether this was possible; this one records what was
found by looking, and what we decided to build.

## What changed against the design

The design marked two questions as load-bearing and unverified. Both are
answered, and one of its conclusions was wrong.

**`gomobile bind` is the wrong mechanism.** `E:\whitevpn2` tried splitting Go
across two `-buildmode=c-shared` libraries and recorded the failure: Go supports
one runtime per process, two libraries export the same 52 runtime symbols into a
single linker namespace, and a call that binds to the other copy enters a runtime
that has never heard of the calling goroutine. It survived two connect cycles and
died in a cgo callback on the third.

The rule that follows: **exactly one Go library in the process.** Ours is Rust
(`libwhiteaesther_core.so`), so Rust plus one Go library is fine. A second Go
library is not.

**A pre-opened fd works.** FlClash's core exports `startTUN` taking a descriptor,
and whitevpn2 proved the path on Android: ten consecutive connect cycles in one
process, exit verified TH to JP, clean teardown. Emulator only, one ABI.

**Aether needs no change.** Verified in this tree: the bridge already selects
`aether::EmbeddedEndpoint::Socks` whenever `mode != "tun"` and closes the tun fd
on that path (`native/android-bridge/src/lib.rs:415`).

## Shape

```
tun -> mihomo (Go .so) -> dialer-proxy -> aether (Rust .so, socks5 :1819) -> MASQUE -> node
```

mihomo owns the tun; Aether drops behind it to the SOCKS listener it already
supports.

## Decisions

**Reuse the build, keep the surface thin.** Take whitevpn2's proven pipeline and
mihomo patch, and expose only start/stop/action. Configuration rendering and the
node model port from the desktop's `src-tauri/src/chain.rs` into Kotlin, so the
two WhiteAesther clients stay one feature rather than diverging into two.

**Ship `through_tunnel`, default on, as on desktop.** Nodes are dialled through
the tunnel by default, which is what hides the node address and SNI from the
local network. Turning it off dials them directly. That is not a nicety: on a
network that resets MASQUE the chain is otherwise impossible, and the reports we
have from MCI in Iran are exactly that network. It may be the answer to those
reports rather than something waiting behind them.

## Stages, each with a gate

**1. The core builds and loads.** Fetch the FlClash core and Clash.Meta, apply
the REALITY patch, build one c-shared library per ABI, stage into `jniLibs`.
*Gate: the app loads it and an action call answers.*

**2. The loop is closed.** This is the hazard, not the UI. The tun takes
`0.0.0.0/0` and `::/0`, so anything not loopback and not protected is pulled back
into the tunnel creating it. Three paths to close: Aether's own sockets (already
protected), mihomo's DNS, and subscription and health-check traffic. The app must
also exclude itself with `addDisallowedApplication`, or the subscription fetch
races its own tunnel. One hard-coded node, no UI.
*Gate: the exit address is the node's, and nothing recurses.*

**3. Rendering and nodes.** Port `chain.rs::render` to Kotlin and drive mihomo
through `invokeAction` rather than its HTTP control API. That sidesteps the bug
the desktop hit, where Go's `net/http` chunk-frames any reply too large to
buffer: `/version` at 35 bytes parsed, the node list did not.
*Gate: a real subscription imports and lists.*

**4. The dashboard.** Compose equivalent of `src/features/Chain.tsx`.

**5. Notices, size, review.** GPL-3.0 alongside the existing AGPL-3.0 notice, and
the Play tension settled deliberately. Roughly +15 MB per ABI against an 8 MB
arm64 APK today; ABI splits already contain what each device downloads.

## Still not proven

- A physical device. whitevpn2's proof is an emulator, which does not exercise
  the VpnService fd path honestly.
- Any ABI but one. `arm64-v8a` builds there; nothing has run it.
- IPv6 containment on a v6-capable network.
- Battery cost, measured rather than assumed.
