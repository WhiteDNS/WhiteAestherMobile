# Patched native dependencies

`boring-sys` 4.22.0 is copied from crates.io and retains its MIT license. Its
build script has one Android-on-Windows compatibility change: when cargo-ndk
provides NDK compiler or `CLANG_PATH` paths without an extension, `.exe` is
added if that file exists. This keeps consecutive CMake configure passes on the
same compiler and lets bindgen locate the NDK Clang executable.

No BoringSSL source or runtime behavior is changed.
