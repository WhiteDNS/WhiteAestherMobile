# Patched native dependencies

`boring-sys` 5.2.0 is copied from crates.io and retains its MIT license. Its
build script has one Android-on-Windows compatibility change: when cargo-ndk
provides NDK compiler or `CLANG_PATH` paths without an extension, `.exe` is
added if that file exists. This keeps consecutive CMake configure passes on the
same compiler and lets bindgen locate the NDK Clang executable.

The generated NASM include path in `deps/boringssl/CMakeLists.txt` is quoted so
Windows host builds also work in a workspace containing spaces. This changes
build arguments only; cryptographic runtime source is not changed. The engine,
quiche workspace and tokio-boring use the same 5.2 version.
