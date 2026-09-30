# Building SaveIt

## Prerequisites

- **JDK 17 or newer** (Android Studio's bundled JBR works): `java -version`
- **Android SDK** with platform **android-36** and build-tools **35+**
  - Easiest: install Android Studio and open the project once; it installs everything.
  - Command line only: install the [command-line tools](https://developer.android.com/studio#command-tools), then

    ```bash
    export ANDROID_HOME=$HOME/Android/Sdk
    sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
    yes | sdkmanager --licenses
    ```
  - Or create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.
- Network access to `dl.google.com`/`maven.google.com`, Maven Central and `services.gradle.org` for the first build.

## Debug build

```bash
./gradlew assembleDebug
```

Output (ABI splits are enabled):

```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk     # almost every phone from the last ~8 years
app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk   # older 32-bit phones
app/build/outputs/apk/debug/app-universal-debug.apk     # all ABIs incl. x86/x86_64 emulators (largest)
```

The debug build installs as `app.saveit.debug`, so it can live next to the release build.

## Release build (signed)

1. Create a signing key once. This writes `saveit-release.jks` and `keystore.properties`, both gitignored:

   ```bash
   ./tools/make-keystore.sh
   ```

   Or do it by hand:

   ```bash
   keytool -genkeypair -v -keystore saveit-release.jks -alias saveit -keyalg RSA -keysize 4096 -validity 10000
   ```

   and create `keystore.properties`:

   ```properties
   storeFile=saveit-release.jks
   storePassword=...
   keyAlias=saveit
   keyPassword=...
   ```

   **Back up both files.** Every future update must be signed with the same key, or Android refuses to install
   it over the old version.

2. Build:

   ```bash
   ./gradlew assembleRelease
   ```

   Output: `app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a,universal}-release.apk`.
   Without `keystore.properties` the release APK is signed with the debug key and Gradle prints a warning.

   R8/minification is **off** for release on purpose. The engine uses reflection (Jackson) and ships Python and
   ffmpeg as native-library archives. `app/proguard-rules.pro` has the keep rules to start from if you enable it.

## Verify the signature

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Check that the native engine is packaged: the listing must show `libpython.so`, `libpython.zip.so`,
`libffmpeg.so`, `libffmpeg.zip.so`, `libffprobe.so` and `libqjs.so` for your ABI.

```bash
unzip -l app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep "lib/arm64-v8a/"
```

## Install

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Or copy the APK to the phone, open it, and allow *Install unknown apps* for the file manager or browser you
opened it from. Use `arm64-v8a` for almost every modern phone, or the `universal` APK if unsure.

## Tests, lint, full check

```bash
./gradlew assembleDebug lintDebug test
```

`test` runs the `:core` unit tests: URL detection and normalization, short-link resolution, each platform's
parser, error classification, and download-pipeline tests that merge real ffmpeg-generated audio/video. The
pipeline tests need `ffmpeg` on the PATH and are skipped otherwise.

Without an Android SDK you can still build and test the extraction core:

```bash
./gradlew -p tools/jvm-build test
```

## Real-URL verification

The verifier runs the same resolver and downloader the app uses (with desktop yt-dlp and ffmpeg) and checks each
output with ffprobe: non-zero size, correct extension, video *and* audio streams, plausible duration, plausible
image dimensions, and the expected item count for multi-item posts.

```bash
pipx install "yt-dlp==2025.11.12"        # same version the app bundles; newer is fine too
sudo apt install ffmpeg                  # or: brew install ffmpeg
./gradlew -p tools/jvm-build :verifier:installDist
verifier/build/install/verifier/bin/verifier verifier/test-matrix.tsv
verifier/build/install/verifier/bin/verifier --url "https://www.reddit.com/r/.../comments/..."
```

Rows marked `ASK` in `verifier/test-matrix.tsv` need a real public post URL. Options: `--quality 720|480|m4a|mp3`,
`--cookies cookies.txt` (Netscape format) for login-only rows, `--no-engine` to test only the Kotlin fallback
extractors. The report is written to `verification-output/report.md`.

The same check runs on GitHub Actions (*Actions → Verify real URLs → Run workflow*). Some platforms block cloud
IPs, so a NETWORK/RATE_LIMITED result there may still pass from a home connection.

## CI

`.github/workflows/android.yml` runs `test lintDebug assembleDebug assembleRelease` on every push, verifies the APK
signatures, and uploads the APKs as build artifacts. To sign CI release builds with your key, add the repository
secrets `SAVEIT_KEYSTORE_BASE64` (`base64 -w0 saveit-release.jks`) and `SAVEIT_KEYSTORE_PASSWORD`.
