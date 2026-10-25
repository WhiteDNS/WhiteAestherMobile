# Patched native dependencies

The active `boring-sys` 5.3.0 dependency is copied from crates.io into
`boring-sys-5.3/` and retains its MIT license. The previous `boring-sys/` 5.2.0
tree is retained as a rollback reference and is not linked. The active crate's
build script has one Android-on-Windows compatibility change: when cargo-ndk
provides NDK compiler or `CLANG_PATH` paths without an extension, `.exe` is
added if that file exists. This keeps consecutive CMake configure passes on the
same compiler and lets bindgen locate the NDK Clang executable.

The generated NASM include path in `deps/boringssl/CMakeLists.txt` is quoted so
Windows host builds also work in a workspace containing spaces. This changes
build arguments only; cryptographic runtime source is not changed. The engine,
quiche workspace and tokio-boring use the same 5.3 version. Both Android
manifests patch crates.io to the active 5.3 directory.

The downloaded 5.3.0 crate's SHA-256 matches the crates.io index:
`4ebc865c335508b2eb7bdcfd35e644ce00ddf385a8feda9b5b9031d8b0b49c35`.
Only `build/main.rs` and `deps/boringssl/CMakeLists.txt` differ from that package.
