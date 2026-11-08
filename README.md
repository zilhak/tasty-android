# Tasty Android

The Android app for Tasty, built with Kotlin and Jetpack Compose.

## Requirements

- JDK 21 (`JAVA_HOME` set)
- Android SDK with `platforms;android-37.0` and `build-tools;37.0.0`. Set `ANDROID_HOME`; `local.properties` is not committed.

The Gradle wrapper downloads Gradle itself. On Windows, use `gradlew.bat` (or `./gradlew` from Git Bash).

## Commands

| Purpose | Command |
|---|---|
| Check formatting | `./gradlew spotlessCheck` |
| Fix formatting | `./gradlew spotlessApply` |
| Android Lint | `./gradlew lint` |
| Unit tests | `./gradlew test` |
| Debug APK | `./gradlew assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`) |

CI runs the same four checks in this order on every push to `main` and every pull request (`.github/workflows/ci.yml`).

Formatting uses ktlint through Spotless and covers `*.kt` and `*.kts`. Rules live in `.editorconfig`. ktlint keeps `.editorconfig` cached in the Gradle daemon, so run `./gradlew --stop` after changing it.

Android Lint treats warnings as errors. Checks that only report newer upstream versions are disabled.

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
