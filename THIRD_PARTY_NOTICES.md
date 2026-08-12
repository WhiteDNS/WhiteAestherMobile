# Third-party notices

## Aether

The native engine is vendored from `MatinSenPai/Aether` revision
`21b9872d080bd185600b93ff20b87f3f3e1e7307` and is licensed under AGPL-3.0.
The original license and revision record are included under `native/aether/`.

## BoringSSL Rust bindings

`boring-sys` 4.22.0 is vendored under `native/third-party/boring-sys` under its
MIT license. WhiteAesther changes only its build script to normalize `.exe`
paths from cargo-ndk on Windows; BoringSSL runtime and crypto source are not
modified. Details are in `native/third-party/README.md`.

Other Kotlin, Rust, and web dependencies retain their respective upstream
licenses. Gradle and Cargo lockfiles identify the exact resolved versions.
