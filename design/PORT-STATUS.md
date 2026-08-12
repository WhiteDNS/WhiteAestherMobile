# Compose port — status

Approved direction: `design/index.html` (Inter, default typeface).
Branch: `design/ui-v2`.

`./gradlew :app:compileStableDebugKotlin` passes. It does **not** trigger the
Rust/NDK build, so it is the fast loop for this work.

## Done

The port is built. Four tabs -- Home / Routes / Traffic / Settings -- with
Endpoint under Routes and Diagnostics, Identity and About under Settings.

- **Fonts.** `res/font/` has `inter_{regular,medium,semibold,bold}.ttf` and
  `plex_mono_{regular,medium}.ttf`. Inter ships as a variable woff2 and Android
  wants plain TTF, so `design/make-android-fonts.py` instantiates each
  weight. Re-run it if the weights change.
- **`ui/theme/Type.kt`**, **`ui/theme/Theme.kt`** -- Inter/mono families, the
  `AetherType` styles, and `AetherColors` for both palettes via
  `LocalAetherColors`. `WhiteAestherTheme(themeMode)` resolves System/Light/Dark.
- **`ui/AetherComponents.kt`** -- the icon set (built from the prototype's own
  path data) and every shared surface: cards, rows, segmented control, choice
  cards, option rows, the Advanced disclosure, attention card, buttons.
- **`ui/ConnectOrb.kt`** -- the four-state control on a Canvas.
- **`ui/Screens.kt`** -- all seven screens.
- **`ui/WhiteAestherApp.kt`** -- shell and tab bar.
- **`service/EngineLog.kt`** -- bounded in-memory log, written from
  `EngineStatusStore.update`.
- **`AppSettings` / `SettingsRepository`** -- `themeMode` and `showAdvanced`
  persisted. `toNativeJson` untouched.
- **`MainActivity`** -- theme mode applied, log entries passed in, and
  `ACTION_SEND` / clipboard for the diagnostics report.
- **Instrumentation tests** rewritten for the new navigation.

### Decisions worth keeping

- **Profiles are presets over real fields.** `MasqueTransport` is only H3/H2, so
  the prototype's WireGuard and WARP-in-WARP options do not exist and are not
  offered. `activeProfile()` reads back as Manual when nothing matches.
- **The orb's sweep is indeterminate.** The engine reports a stage and a message,
  not progress, so a filling ring would be a number the app invented. Everything
  on Home is either measured locally (uptime) or straight from `EngineStatus`.
- **The transport race and latency sparkline from the prototype are not built.**
  The engine exposes no per-transport probe results or live RTT. They would have
  been fabricated data.
- **The proxy port editor is restored** -- the redesign had dropped it.
- **Obfuscation offers three profiles**, matching `from_profile`. The DNS row is
  read-only and says so.

## Outstanding

Identity & access, About and the proxy port editor were the review gaps; all
three are now built. What is left:

- **Obfuscation "Off" needs a warning, not just a label.** It genuinely disables
  obfuscation, which on a censored network turns a working connection into a
  failing one. The option row currently says "Faster, but easier to block" --
  that is honest but understated for a destructive choice.
- **DNS stays read-only** until the engine gains a config key. See below.
- **Identity has nothing live to report.** The screen states how the identity
  works and where the key lives, which is true and useful, but there is no signal
  from the engine for whether an identity has actually been provisioned yet. If
  the bridge ever exposes one, the screen should show it.
- **No screenshot pass on a real device.** Everything here was verified by
  compiling and by the instrumentation tests, not by looking at it running.

## Engine-side work -- possible, but starts in Rust

These came out of review as "can we add this?". None is a UI task; each needs
native changes first. Listed with what was actually verified in the source, so
the next session does not re-derive it.

### A. WARP+ license key

**Verdict: possible, but it touches the critical path -- treat with care.**

`account.rs` already does WARP device registration: `Registration`,
`AccountData`, `AccountInfo`, `DeviceUpdate`, plus `TeamRegistration` for Zero
Trust. That is the plumbing a license would hook into, and `DeviceUpdate` is the
struct it would most likely extend.

Missing: there is no `license` field anywhere -- not in `account.rs`,
`config.rs`, `cli.rs` or `apifront.rs` -- and no key in `toNativeJson`. The path
is Rust (add the field, `PUT` it to the account endpoint, re-fetch the account so
the new quota applies), then a bridge key, then `AppSettings` and the input UI.

Why this one is riskier than the settings above: `noize` has a catch-all fallback
and `proxyPort` is a local bind, so neither can stop you connecting. A license
goes through registration -> account data -> peer config -> connect. Wired into
that flow and failing, you do not get "no WARP+", you get "no connection". It is
also a network call to Cloudflare, and `account.rs` already retries
(`API_ATTEMPTS = 5` with capped backoff), so a rejected key could add seconds to
every connect attempt.

**Constraint to hold to:** applying the license must be a separate, optional step
that is allowed to fail without blocking the connection. Register and connect
exactly as today; apply the key as a follow-on that reports success or failure to
the UI and changes nothing else on failure. Never inside the connect path's error
propagation.

Also, a license key is a credential:
- it must not sit in DataStore in plaintext next to the UI preferences
- it must never reach the diagnostics report -- the redaction pass sketched in
  the prototype only covers IP addresses and Wi-Fi names

**Before building:** trace how `AccountData` actually reaches the peer config.
Only the struct surface of `account.rs` has been read, not the flow, and that
flow is where the risk lives.

### B. Full noize control

**Verdict: the capability is real but unreachable; do not expose it raw.**

`NoizeConfig` in `native/aether/aether/src/noize.rs` has seven tunables:

```rust
jc_before_hs, jc_after_i1,   // junk packet counts
jmin, jmax,                  // junk size range
i1, i2,                      // packet-spec templates, e.g. "<b 0d0a0d0a><t><r 24>"
junk_interval                // e.g. 4ms
```

But the only constructor is `from_profile(name)`, and the bridge just forwards
`noize: String` into the `AETHER_NOIZE` env var, which feeds it. There is no
parser from a string into arbitrary field values -- `parse_cps` only parses the
`i1`/`i2` specs the presets already hardcode. So today the reachable set really
is Off / Firewall / GFW.

Reaching the rest means adding a Rust parser so `AETHER_NOIZE` can carry field
values, plus a bridge key, plus UI.

**Recommendation: add new named presets instead of raw parameter entry.** A
malformed `i1`/`i2` spec produces a broken handshake, which means no connection
at all -- the same class of failure as turning obfuscation off, but far easier to
trigger by accident. One or two extra presets, tuned by someone who can test
against a real censored network, gets the same capability with no way for a user
to hand-craft a broken spec. If raw entry is wanted at all, it belongs behind the
Manual profile with an explicit "this can stop you connecting" warning.

### C. Engine bug found while checking the above

`native/aether/aether/src/cli.rs:31` documents the flag as:

```
--noize <profile>   obfuscation profile (off, light/firewall, balanced, gfw/aggressive, ...)
```

`light`, `balanced` and `aggressive` are not branches in `from_profile`; they all
fall through to `firewall`. Either implement them or fix the help text -- as it
stands it advertises profiles that silently do nothing, which is what made the
supported set look larger than it is.

## Mapping the prototype onto the engine

| Prototype | Real setting |
| --- | --- |
| Profile cards | preset over `scanStrategy` + `transport` |
| Endpoint Automatic / Specific | `EndpointMode.AUTOMATIC` vs `CUSTOM_FIRST` |
| "Fall back automatically" | `CUSTOM_FIRST` vs `CUSTOM_ONLY` |
| Coverage whole-device / proxy | `EngineMode.TUN` / `PROXY` |
| Addresses | `dualStack` |
| "Check the connection works" | `validationEnabled` |
| Obfuscation | `noizeProfile` |
| Local proxy port | `proxyPort` |
| Discovery depth | `ScanStrategy` |

Coverage, DNS and obfuscation exist in `AppSettings` but have no UI in the
shipped app; the prototype gives them one under Traffic. Confirm that is wanted
before exposing them.
