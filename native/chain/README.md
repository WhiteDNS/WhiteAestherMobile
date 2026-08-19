# Chain core

The Go library that carries mihomo, for the exit chain. Not built by Gradle by
default: it is slow, it needs the Go toolchain, and the chain is off by default.

```powershell
pwsh -File native/chain/setup.ps1      # fetch and patch, once
pwsh -File native/chain/build.ps1      # build all three ABIs
```

`third_party/` and `build/` are not committed. They are large, regenerable, and
separately licensed.

## One library, deliberately

Go supports one runtime per process. Two `-buildmode=c-shared` libraries are two
runtimes exporting the same symbols -- `crosscall2`, `x_cgo_init`, `main.main`
and about fifty more -- into a single linker namespace, and a call binding to the
wrong copy enters a runtime that has never heard of the calling goroutine. It
survives a couple of connect cycles and then dies inside a cgo callback with
`unknown caller pc`.

Our engine is Rust, so Rust plus this one Go library is fine. A second Go library
is not, however tempting it is to keep our own code out of a GPL-3.0 library.

## Exports

`invokeMethod` carries the action protocol; `startTUN` takes an already-open file
descriptor, which is what makes the in-process design work at all. Also
`stopTun`, `quickSetup`, `setEventListener`, `getTraffic`, `getTotalTraffic`.

## Patch

`patches/0001-reality-client-version.patch` -- mihomo advertises a hardcoded
REALITY client version of 1.8.2 in the ClientHello session id while Xray builds
those bytes from its own version, so a current Xray server rejects the handshake.
Re-apply whenever Clash.Meta moves.

## Licence

mihomo is GPL-3.0. This app is AGPL-3.0 and the combination is distributable, but
the notice obligation is real: see `THIRD_PARTY_NOTICES.md`.

## Checking the bridge code

`chain.rs` and `chain_jni.rs` are `#[cfg(unix)]`: `dlopen` is a Unix API and
the host build on Windows has no chain at all. A plain `cargo clippy` on a
Windows desktop therefore compiles none of it and passes on code CI rejects.
Check against an Android target instead:

```bash
cd native/android-bridge
cargo ndk -t arm64-v8a clippy --locked --all-targets -- -D warnings
```

CI runs on Linux, where the host build is Unix, so it does see this code.
