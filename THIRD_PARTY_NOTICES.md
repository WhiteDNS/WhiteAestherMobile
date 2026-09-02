# Third-party notices

WhiteAestherMobile is [AGPL-3.0](LICENSE). It embeds components under AGPL-3.0
and GPL-3.0; both are listed below with where to get their source.

AGPL-3.0 section 13 expressly permits combining an AGPL-3.0 work with a work
under GPL-3.0 into a single combined work. The AGPL-3.0 parts remain AGPL-3.0
and the GPL-3.0 parts remain GPL-3.0.

## Aether — AGPL-3.0

The native engine is vendored from `CluvexStudio/Aether` revision
`a916ff6fbbb4ebafe8314c53cf3718eb51dcae53`, released as `v1.8.0`. The original
license and revision record are included under `native/aether/`.

The project moved: earlier releases name `MatinSenPai/Aether`, which still
carries the revision shipped up to v1.2.1 but stops at its own `v1.3.0`. Both
hold identical objects for the tags they share.

The vendored copy is not byte-identical to that revision. It is formatted with
`cargo fmt`, its line endings are normalised to LF, and `aether/src/ffi.rs` is
omitted -- nothing here calls upstream's C API, `native/android-bridge` serves
that purpose, and the omitted file does not build against this tree. Source for
the complete original is at the revision named above.

Shipped as `libwhiteaesther_core.so`.

## FlClash core — GPL-3.0

The exit chain's Go library is built from the `core` directory of
[chen08209/FlClash](https://github.com/chen08209/FlClash), revision
`62addf738a76b1a492e19af2dbabdb6d572b9e72`.

## mihomo (Clash.Meta) — GPL-3.0

The proxy engine inside that library is
[chen08209/Clash.Meta](https://github.com/chen08209/Clash.Meta), revision
`80362fc1895dcf60b79b562896653046e0687413`, a fork of
[MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo).

**Modified by WhiteAesther.** One change, kept as a patch rather than a fork so
it is legible on its own: `native/chain/patches/0001-reality-client-version.patch`.
mihomo advertises a hardcoded REALITY client version of 1.8.2 in the ClientHello
session id, while Xray builds those bytes from its own version, so a current
Xray server rejects the handshake outright.

Shipped as `libwhiteaestherchain.so`.

### Getting the source

Neither GPL-3.0 component is committed to this repository — both are fetched at
build time, at the exact revisions above, by `native/chain/setup.ps1`. Those
revisions are pinned in that script, so the source corresponding to any binary
we ship can be obtained by running it, or by fetching the revisions directly
from the upstreams named above. `native/chain/README.md` describes the build.

The GPL-3.0 text is in [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt).

## BoringSSL Rust bindings — MIT

`boring-sys` 4.22.0 is vendored under `native/third-party/boring-sys` under its
MIT license. WhiteAesther changes only its build script to normalize `.exe`
paths from cargo-ndk on Windows; BoringSSL runtime and crypto source are not
modified. Details are in `native/third-party/README.md`.

## Vazirmatn — SIL Open Font License 1.1

The Persian interface is set in Vazirmatn v33.003 by Saber Rastikerdar, from
`rastikerdar/vazirmatn`. Four weights of its UI cut ship in `res/font-fa/`, so
they are used only where the app is set to Persian; the Latin interface keeps
Inter.

Bundled rather than fetched. A font requested at runtime is a request to a third
party naming this device, from an app whose whole purpose is not to make those.

The OFL permits redistribution inside a bundle like this one provided the font
is not sold on its own and the licence travels with it. Unmodified, and named
Vazirmatn -- the OFL's reserved-name clause forbids a modified copy keeping the
name, which is a reason not to modify it rather than a reason to rename.

The licence text is in
[licenses/OFL-1.1-Vazirmatn.txt](licenses/OFL-1.1-Vazirmatn.txt).

## Everything else

Other Kotlin, Rust, and Go dependencies retain their respective upstream
licenses. The Gradle, Cargo, and Go lockfiles identify the exact resolved
versions.
