# Tasty Android

The Android app for Tasty, built with Kotlin and Jetpack Compose.

## Requirements

- JDK 21 (`JAVA_HOME` set)
- Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`. Set `ANDROID_HOME`; `local.properties` is not committed.

- Android NDK `30.0.16248370` (`sdkmanager "ndk;30.0.16248370"`).
- Rust through rustup. `rust/rust-toolchain.toml` pins the version and the Android targets, so rustup installs them on the first build.
- cargo-ndk: `cargo install cargo-ndk --locked`.

The Gradle wrapper downloads Gradle itself. On Windows, use `gradlew.bat` (or `./gradlew` from Git Bash).

## Commands

| Purpose | Command |
|---|---|
| Check formatting | `./gradlew spotlessCheck` |
| Fix formatting | `./gradlew spotlessApply` |
| Android Lint | `./gradlew lint` |
| Unit tests | `./gradlew test` |
| Debug APK | `./gradlew assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`) |
| Rust format, Clippy, tests | `cargo fmt --all --check`, `cargo clippy --workspace --all-targets --locked -- -D warnings`, `cargo test --workspace --locked` (run in `rust/`) |

CI runs the Rust checks, then the same four Gradle checks in this order on every push to `develop`, `release` and `main` and on every pull request into one of them (`.github/workflows/ci.yml`).

Formatting uses ktlint through Spotless and covers `*.kt` and `*.kts`. Rules live in `.editorconfig`. ktlint keeps `.editorconfig` cached in the Gradle daemon, so run `./gradlew --stop` after changing it.

Android Lint treats warnings as errors. Checks that only report newer upstream versions are disabled.

## Rust core

The app runs a local shell through `rust/tasty-mobile-core`, which wraps the desktop app's `tasty-terminal` crate (PTY and terminal emulation) and exposes it to Kotlin with UniFFI. `tasty-terminal` is a git dependency pinned to a commit of github.com/zilhak/tasty; bump `rev` in `rust/Cargo.toml` on purpose.

It also loads or creates the Tasty network device key through `tasty-net-identity`, a git dependency pinned to a develop commit of github.com/zilhak/tasty-network until that repository tags an Android release. To move to a newer commit, set the full sha as `rev` in `rust/Cargo.toml` and run `cargo fetch` in `rust/` with the existing `Cargo.lock`. It records the new commit and adds only the packages that commit needs; `cargo update -p tasty-net-identity` can also change which locked version other packages use, so do not use it for this. Check that the `Cargo.lock` diff touches only `tasty-net-identity` and its new dependencies, run `cargo check --workspace --all-targets --locked`, then commit the manifest and `Cargo.lock` together.

Gradle builds it as part of the app:

- `cargoNdkBuild` builds `libtasty_mobile_core.so` in release mode for `arm64-v8a` and `x86_64`. Pass `-Ptasty.rustAbis=x86_64` to build one ABI while working on an emulator.
- `uniffiBindgen` generates the Kotlin bindings (package `dev.tasty.android.core`) into `app/build/generated/source/uniffi`.

## Run on an emulator

```bash
emulator -avd <avd-name> -no-snapshot -no-audio -no-boot-anim &
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
./gradlew installDebug
adb shell am start -W -n dev.tasty.android/.MainActivity
```

Create an AVD for API 37 first if you have none, for example:

```bash
sdkmanager "system-images;android-37.0;google_apis;x86_64"
avdmanager create avd -n tasty-api37 -k "system-images;android-37.0;google_apis;x86_64"
```

Stop the emulator with `adb emu kill`.
