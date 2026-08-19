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

**1. The core builds and loads. Done.** Fetch the FlClash core and Clash.Meta,
apply the REALITY patch, build one c-shared library per ABI, stage into
`jniLibs`. *Gate met: the library loads beside the Rust engine in one process,
answers actions, and survives ten consecutive ones -- the two-runtime failure
this design avoids showed up on the third call, not the first.*

**2. The loop is closed. Done.** This was the hazard, not the UI. The tun takes
`0.0.0.0/0` and `::/0`, so anything not loopback and not protected is pulled back
into the tunnel creating it. Four paths closed, each differently:

- Aether's own sockets -- already protected per socket
- mihomo's resolvers -- `#aether` on each nameserver, which is mihomo's syntax
  for dialling a resolver through a named proxy
- subscription fetches and health checks -- `dialer-proxy` on the provider
- everything else this process opens -- `addDisallowedApplication` on ourselves,
  which the kernel enforces whether or not a caller remembered to protect

*Gate met on Android 16, probed from `adb shell` because a request from inside
the app proves nothing -- being excluded is the containment under test:*

| | direct | through the chain |
| --- | --- | --- |
| exit address | `124.120.14.203` | `64.110.98.173` |
| Cloudflare edge | BKK | KIX |
| `api.ipify.org` resolves to | real address | `198.19.0.5`, a fake-ip |

*`tun0` came up at `198.18.0.1/30` owned by mihomo, and went away on teardown
with the exit address back to direct. Both topologies verified: nodes dialled
directly, and nodes dialled through MASQUE.*

**3. Rendering and nodes. Renderer done, listing outstanding.**
`chain.rs::render` is ported and drives mihomo through the action protocol rather
than its HTTP control API, which sidesteps the bug the desktop hit where Go's
`net/http` chunk-frames any reply too large to buffer: `/version` at 35 bytes
parsed, the node list did not. A real subscription imports; `getProxies` reads
back the group. What is left is surfacing that list and letting a node be picked.
*Gate: a real subscription imports and lists.*

**4. The dashboard.** Compose equivalent of `src/features/Chain.tsx`.

**5. Notices, size, review.** GPL-3.0 alongside the existing AGPL-3.0 notice, and
the Play tension settled deliberately. Roughly +15 MB per ABI against an 8 MB
arm64 APK today; ABI splits already contain what each device downloads.

## Decisions taken while building it

**No `external-controller`.** The desktop needs one because mihomo is a separate
process there. In-process it would be an open loopback port that nothing uses,
reachable by any page the user opens.

**Dialling directly does not start the engine.** Not an optimisation: this mode
exists for networks where MASQUE is dead, and on exactly those networks the
endpoint scan it used to run first is the thing that never finishes.

**Refuse rather than connect without the chain.** If the chain is on and cannot
start, the session fails and says why. Connecting anyway is the failure that does
not look like one -- the user believes their traffic leaves from their node while
it leaves from Cloudflare.

**mihomo's log goes into the app's.** Pulled from a bounded buffer rather than
pushed, because the events arrive on Go's own threads and pushing would attach
each one to the JVM. Only the log events are kept: the same stream carries a
record of every connection, which would name every host the user visited in a
report they might send us.

## Still not proven

- **A physical device.** Everything above is an emulator, which does not
  exercise the VpnService fd path honestly.
- **Any ABI but `x86_64`.** `arm64-v8a` builds and is staged; nothing has run it.
- **IPv6 containment on a v6-capable network.** The emulator has no IPv6, so the
  `::/0` half of the routing loop is closed by construction and not by test.
- **Battery cost**, measured rather than assumed.
- **Recovery.** What happens when the tunnel drops under a live chain, or the
  network changes, has been reasoned about but not exercised.
