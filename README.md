# Syncbook Android

Native Android client for Syncbook. Rust `yrs` owns the document and sync
protocol; Kotlin only renders blocks and sends user actions through UniFFI.

## Toolchain

The reference build used:

- JDK 17.0.13
- Gradle 8.10.2 (via the checked-in Gradle wrapper)
- Android SDK with API 35
- Android NDK 27.2.12479018
- Rust stable 1.97.1
- Rust targets `aarch64-linux-android` and `x86_64-linux-android`
- cargo-ndk 3.5.4
- UniFFI 0.32.0
- yrs 0.27.3

Set `ANDROID_HOME` and `ANDROID_NDK_HOME`, or put `sdk.dir` and `ndk.dir`
in an uncommitted `local.properties` file.

## Build and test

```bash
./gradlew test
./gradlew assembleDebug
(cd rust && cargo fmt --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test)
```

Gradle generates Kotlin UniFFI bindings during the build and builds both
Android native ABIs plus the host library used by JVM tests.

## Server URL

The app stores one base URL. The default Android emulator URL is:

```text
http://10.0.2.2:8080
```

HTTP requests use `<base>/api/...`. The app passes the `http(s)` base URL to
OkHttp for WebSocket upgrades at `<base>/ws` and `<base>/ws/user`; HTTPS
connections use `wss`.

## Cross-client smoke test

Keep the Syncbook Compose stack running, then run:

```bash
export CROSS_CLIENT_BASE_URL=http://localhost:8080
./gradlew testDebugUnitTest --tests com.ccarpo.syncbook.CrossClientSmokeTest
```

The test authenticates through the nginx-proxied origin, connects Rust-backed
documents through `/ws`, verifies remote changes reach the rendered block
model, checks server-derived metadata, and verifies Android-created task-item
content.
