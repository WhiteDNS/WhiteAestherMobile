<div align="center">

# WhiteAestherMobile

**Android client for the Aether encrypted route engine.**
Finds a working path out of restrictive networks and carries your traffic through it.

[![Release](https://img.shields.io/github/v/release/WhiteDNS/WhiteAestherMobile?style=flat-square&color=34d1a6)](https://github.com/WhiteDNS/WhiteAestherMobile/releases/latest)
[![CI](https://img.shields.io/github/actions/workflow/status/WhiteDNS/WhiteAestherMobile/ci.yml?branch=main&style=flat-square&label=CI)](https://github.com/WhiteDNS/WhiteAestherMobile/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/badge/licence-AGPL--3.0-blue?style=flat-square)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](#requirements)

</div>

<!-- SCREENSHOTS -->

## Install

Grab the APK for your phone from the [latest release](https://github.com/WhiteDNS/WhiteAestherMobile/releases/latest).

| File | Use it if |
| --- | --- |
| `arm64-v8a` | **Almost every phone from 2017 onward.** Start here. |
| `armeabi-v7a` | Older or budget 32-bit devices |
| `x86_64` | Emulators, ChromeOS |
| `universal` | You are not sure. Works everywhere, roughly three times the size. |
| `.aab` | Play Store submission, not for sideloading |

Verify what you downloaded against `SHA256SUMS` in the same release:

```bash
sha256sum -c SHA256SUMS --ignore-missing
```

Android will warn about installing outside the Play Store. That is expected for a
sideloaded APK.

> **راهنمای فارسی:** [docs/GUIDE.fa.md](docs/GUIDE.fa.md) -- installing, first run, and what to change when a
> network blocks the connection.

## First run

**1. Leave Coverage on "Whole device".** Under **Traffic**, this is the default
and what almost everyone wants — every app on the phone is carried through the
tunnel. *Proxy only* runs a local SOCKS5 listener instead and routes nothing by
itself; traffic only goes through it if you point an app at `127.0.0.1:1819`. If
you pick it by accident, it looks like the app connected but did nothing.

**2. Leave the profile on "Adaptive".** Under **Routes**. It balances how hard
the engine searches against how quickly it connects. The other profiles are
narrower:

| Profile | For |
| --- | --- |
| **Adaptive** | Most networks. Start here. |
| **Patchy signal** | Mobile data that keeps dropping — searches harder |
| **Strict network** | Office or campus Wi-Fi that blocks a lot — quieter probing, slower |
| **Manual** | You set the transport and search depth yourself |

**3. Allow background running when asked.** Under **Settings**, a card appears if
Android is still allowed to suspend the app. Without the exemption the tunnel
drops when the screen goes off — some manufacturers are far more aggressive about
this than others.

**4. Expect the first connect to take a moment.** The engine tests real network
paths before accepting one. If a path fails it backs off and retries, and the
status line tells you which attempt it is on.

## When it will not connect

The line under the big status heading is the engine's own message, not a generic
error. It is the first thing to read.

| What you see | What it means |
| --- | --- |
| `... · retry 3 of 8 in 12s` | A path failed and it is trying another. Normal on a hostile network. |
| `Stopped after 8 attempts` | Nothing worked here. Try a different profile or network. |
| `custom endpoint ... failed MASQUE validation` | The address you pinned is not reachable. Switch **Endpoint** back to Automatic. |
| `Connected to a different endpoint` | Your pinned address failed and fallback substituted a working one. |

Worth trying, in order: switch the profile to **Strict network**; set
**Addresses** to *IPv4 only* under Traffic if the network handles IPv6 badly;
turn **Obfuscation** up to *Aggressive*.

Under **Settings → Diagnostics** you can raise the detail level, reproduce the
problem, and send a report. It shows you the exact text before anything is sent,
and replaces IP addresses with placeholders unless you turn that off.

## How it works

Aether probes reachable Cloudflare endpoints, completes an authenticated MASQUE
handshake against them, and only accepts a route once real traffic returns
through it. The app then either raises an Android `VpnService` tunnel that
captures IPv4, IPv6 and DNS, or exposes a loopback SOCKS5 proxy.

- **Transports** — MASQUE over HTTP/3 (QUIC) and HTTP/2 (TLS over TCP, for
  networks that block UDP)
- **Obfuscation** — padding profiles that make tunnel traffic harder to
  fingerprint
- **Endpoints** — discovered automatically, or pinned to a specific `IP:port`
  with optional fallback
- **Identity** — provisioned on first connect, private key kept in app-private
  storage, never leaves the device

## Requirements

Android 8.0 (API 26) or newer, on `arm64-v8a`, `armeabi-v7a` or `x86_64`.

## Build from source

```bash
git clone https://github.com/WhiteDNS/WhiteAestherMobile.git
cd WhiteAestherMobile
./gradlew assembleStableDebug
```

Needs JDK 21, Android SDK 36 with NDK `29.0.14206865` and CMake 3.22.1, and Rust
1.88.0 with the Android targets plus `cargo-ndk`. The Gradle build compiles the
Rust bridge itself.

`./gradlew :app:compileStableDebugKotlin` skips the native build and is the fast
loop when only touching Kotlin.

## Repository layout

| Path | |
| --- | --- |
| `app/` | The Android application |
| `native/aether/` | Vendored Aether engine |
| `native/android-bridge/` | Rust JNI bridge between the two |
| `design/` | Clickable design prototype and the notes from building it |
| `docs/` | Release process and device test plan |

`design/PORT-STATUS.md` records what was verified against the engine and what is
still outstanding — worth reading before changing the connection UI.

## Releasing

CI verifies every pull request and every push to `main`. Publishing happens on
tags alone:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

That builds, tests, signs, verifies every APK, and publishes the release.

## Privacy

No analytics, no telemetry, no accounts. The proxy binds to loopback only,
cleartext traffic is blocked, backups are disabled, and diagnostics reports are
never sent without you reviewing them first. See [PRIVACY.md](PRIVACY.md).

## Licence

[AGPL-3.0](LICENSE), as is the Aether engine it embeds. Third-party components
are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
