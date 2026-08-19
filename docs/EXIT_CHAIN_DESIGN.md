# Exit chain on Android — design

Status: **proposal, not implemented.** Written 2026-08-19, after the feature
shipped on the desktop client (WhiteAesther 1.5.1).

## The problem this solves

Cloudflare WARP is explicit that it does not change your country. It egresses
near you and geolocates the exit address to your region, so someone in Iran
connects successfully and still looks like they are in Iran. The tunnel is doing
its job — the traffic is private — but the address a website reads is not the
one the user wanted.

The only fix is a second hop after the tunnel: send the tunnel's output through a
node of the user's own, so the address the world sees is the node's.

Measured on the desktop client, one machine, one moment:

| route | address | location | warp |
|---|---|---|---|
| tunnel only | 104.28.214.151 | Singapore | on |
| through the chain | 64.110.98.173 | Japan (KIX) | off |

## Why the desktop design does not port

The desktop is two processes glued together by loopback sockets:

```
apps → mihomo (mixed :1820) → dialer-proxy → aether (socks5 :1819) → MASQUE → node
```

`aether.exe` and `mihomo.exe` are ordinary child processes, the OS system proxy
points at mihomo, and every node is dialled *through* the tunnel because each
proxy-provider carries `dialer-proxy: aether`.

None of that mechanism exists here:

- **There is no system proxy on Android.** Traffic is captured by a `tun`
  interface owned by `AetherVpnService`.
- **The engine is not a subprocess.** It is vendored Rust compiled into the app
  (`native/aether`, `native/android-bridge`) and called over JNI:
  `NativeAetherBridge.run(configJson, peer, tunFd, listener)`. The service
  builds the tun, detaches the fd, and hands it to the engine, which owns it.
- **A tun changes what "direct" means.** On the desktop, anything that bypasses
  the chain merely leaks. Here it *loops*: a packet that escapes the chain is
  captured by the tun and fed back in.

So the chain cannot be bolted in front of the existing arrangement. The order has
to invert.

## The shape it has to take

```
tun → mihomo → dialer-proxy → aether (EmbeddedEndpoint::Socks) → MASQUE → node
```

mihomo becomes the entry point and owns the tun; Aether drops behind it to a
plain SOCKS5 listener.

Two things already in the tree make this less work than it sounds:

- `native/android-bridge/src/lib.rs` already selects
  `aether::EmbeddedEndpoint::Socks` whenever `config.mode != "tun"`, and closes
  the tun fd on that path.
- `AppSettings` already has `EngineMode.TUN` / `EngineMode.PROXY` and a
  `proxyPort` defaulting to `1819` — the same port the desktop uses.

The engine therefore needs no change to run in the position this design puts it
in. The work is in the service, the second hop, and the routing rules that keep
traffic out of the loop.

## Where mihomo runs

This is the decision the rest of the design hangs on.

### Chosen: mihomo as a native library in the app process

Compile mihomo with `gomobile bind` into an `.aar` and call it from Kotlin, the
way ClashMetaForAndroid does. One process, and the tun fd is handed over as an
ordinary integer.

### Rejected: mihomo as an executable in `jniLibs`

Shipping `libmihomo.so` and `exec`ing it from `nativeLibraryDir` is the usual
trick for bundling a Go binary in an APK, and it does work — since API 29 an app
may only execute files from the APK's native library directory, which is exactly
what that layout provides. It was rejected for two reasons, in order of weight:

1. **The tun fd cannot cross the process boundary cheaply.** The fd exists in the
   app process. Handing it to a child means `SCM_RIGHTS` over a unix socket plus
   a mihomo configuration that accepts a pre-opened descriptor. That is a second
   mechanism to build and debug before any of the actual feature works.
2. **A child process cannot be `protect()`ed.** `VpnService.protect()` is a
   method on the service object in this process. Every socket a child opens that
   is not loopback is captured by the tun. See the routing loop below — the
   in-process design lets the existing `nativeSetSocketProtector` keep doing its
   job.

### Rejected: no mihomo, second hop written in Rust

Would mean implementing vless, vmess, trojan, shadowsocks, hysteria2 and tuic.
Not a serious option; it is years of work to reach parity with a mature core.

## The routing loop is the central hazard

On the desktop, a connection that misses the chain merely goes out in the clear —
bad, but it terminates. Here, the tun has `addRoute("0.0.0.0", 0)` and
`addRoute("::", 0)`, so **any** socket that is not either loopback or explicitly
protected is pulled back into the tunnel that is trying to create it.

Three specific paths have to be closed:

1. **Aether's own sockets to Cloudflare.** Already handled — the engine is
   in-process and `nativeSetSocketProtector` protects them. This is the single
   strongest argument for keeping mihomo in-process too rather than splitting.
2. **mihomo's DNS.** The desktop config points `nameserver` at DoH over
   `https://1.1.1.1/dns-query`. If mihomo resolves that directly here, the query
   is captured by the tun. It must either be forced through the same
   `dialer-proxy` as everything else, or answered by the engine.
3. **Subscription downloads and health checks.** Same reasoning. On the desktop
   these already travel `dialer-proxy`; that must not be dropped when the config
   is ported.

The desktop config renderer (`src-tauri/src/chain.rs::render`) is the right
starting point — it already sets `dialer-proxy` per provider, which is what makes
a subscription of any size inherit the tunnel without parsing a single entry —
but it must be re-reviewed against a tun rather than copied.

**The app's own traffic must also be excluded** from the tun
(`Builder.addDisallowedApplication`), or the subscription fetch races its own
tunnel.

## What has to be ported, and one bug that comes with it

- **The config renderer.** Plain YAML generation; a direct port.
- **The control-API client.** This is where the care is needed. mihomo's control
  API is Go's `net/http`, which sets `Content-Length` only for a reply small
  enough to buffer and frames anything larger in chunks. The desktop client
  originally read neither, so `/version` (35 bytes) succeeded — the chain
  reported itself up and carried traffic — while the node list, the one reply
  that is always large, reached the JSON parser as `8a0c\r\n{"proxies":...` and
  produced `expected value at line 1 column 1`. **Any port must decode chunked
  transfer encoding**, or it will reproduce that failure exactly. If mihomo is
  bound via gomobile, this disappears — the calls become Go function calls and
  there is no HTTP client to get wrong.
- **The dashboard.** Subscriptions, pasted configs, per-node test and select.
  Compose equivalents of `src/features/Chain.tsx`.

## Cost

- **Binary size.** mihomo is ~15 MB per ABI compressed. With three ABIs the APK
  grows accordingly; ABI splits are already enabled
  (`WHITEAESTHER_DISABLE_ABI_SPLITS`), so per-device downloads stay close to one
  architecture's worth.
- **Battery and memory.** Two engines instead of one, with the tun stack in Go
  rather than in the Rust engine that owns it today. This is a real cost on a
  device running a VPN all day, and it is why the chain must stay **off by
  default** and be a deliberate choice.

## Licensing — settle before writing code

mihomo is **GPL-3.0**. This app already vendors Aether under **AGPL-3.0** and
records it in `THIRD_PARTY_NOTICES.md`.

Two obligations, one risk:

- The GPL-3.0 licence text and a pointer to the corresponding source must ship in
  the app, alongside the existing notices.
- GPL-3.0 has a well-known tension with Google Play's distribution terms
  (the installation-information requirement versus Play's signing and delivery).
  GPL-licensed apps do ship on Play, but this is a decision to take deliberately
  rather than discover at review time.

The same notices obligation is outstanding on the desktop client and should be
closed there first, since it is the smaller job and the wording can be reused.

## Staging

1. **Prove the loop is closed** before building any UI. mihomo in-process, one
   hard-coded node, `dialer-proxy` at the engine's SOCKS port, and a check that
   the exit address is the node's and that nothing recurses.
2. **Config rendering and node listing**, with the chunked-encoding lesson above
   applied or designed out.
3. **The dashboard**, matching the desktop's Exit chain screen.
4. **Notices and store review.**

## Open questions, honestly marked

These were **not** verified while writing this and must be settled before the
estimate means anything:

- Whether `gomobile bind` produces a usable `.aar` for the mihomo tree at the
  version pinned on the desktop (`v1.19.30`), and what its build requirements
  are on the CI images currently used here.
- Whether mihomo's tun stack accepts a pre-opened file descriptor on the
  in-process path without patching.
- What mihomo's actual DNS behaviour is when every outbound is forced through a
  `dialer-proxy`, and whether `fake-ip` remains the right mode behind a tun.
- Battery cost measured rather than assumed.

The desktop implementation to read alongside this is
`src-tauri/src/chain.rs` and `src/features/Chain.tsx` in `WhiteDNS/WhiteAesther`.
