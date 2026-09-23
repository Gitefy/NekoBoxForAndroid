# Bundled Vela client

This directory contains only the Rust client executable and the crate sources
required to build it for Android. The server, server deployment tools, protocol
specification, and development workspace documentation are intentionally not
part of this client bundle.

The source crates declare `MIT OR Apache-2.0` in their Cargo manifests.
The executable is built from source for `aarch64-linux-android` by the Android
Gradle build and packaged as `libvela.so` for the existing guarded process
runner. Keep the Cargo lockfile checked in so APK builds use pinned dependencies.
