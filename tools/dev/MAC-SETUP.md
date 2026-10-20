# Building Granger on the Mac Studio

Set up 2026-10-19 for Plan M1 on Neal's Mac Studio (M2 Max, 64 GB, macOS 26, Apple silicon). Android Studio is not needed; everything below is command-line.

## Installed versions

| Tool | Version | Installed with |
|---|---|---|
| JDK | OpenJDK 17.0.20.1 (Homebrew) | `brew install openjdk@17` |
| Android SDK command-line tools | latest cask | `brew install --cask android-commandlinetools` |
| Android SDK Platform | `platforms;android-37.0` (API 37, revision 2) | `sdkmanager` |
| Build-tools | `build-tools;37.0.0` | `sdkmanager` |
| NDK | `ndk;30.0.16248370` (r30, the 2026 LTS) | `sdkmanager` |
| CMake | `cmake;3.22.1` (AGP's default) | `sdkmanager` |
| Platform tools (adb) | adb 1.0.41, platform-tools 37.0.1 | `brew install --cask android-platform-tools` |
| ffmpeg | 9.0.2 (fixtures only) | `brew install ffmpeg` |
| Gradle | 9.8.1 (wrapper) | `./gradlew` downloads it |
| AGP / Kotlin | 9.4.1 / 2.4.21 | `gradle/libs.versions.toml` |
| Media3 | 1.11.1 (pinned in the catalog) | Gradle |

The SDK lives at `/opt/homebrew/share/android-commandlinetools` (the cask's default root).

## Install from scratch

```bash
brew install openjdk@17 ffmpeg
brew install --cask android-commandlinetools android-platform-tools

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$PATH"

yes | sdkmanager --licenses
sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "ndk;30.0.16248370" "cmake;3.22.1"
```

`sdkmanager` names API 37 `platforms;android-37.0`; `compileSdk = 37` resolves to it.

## Shell setup

Add these to your shell profile (`~/.zshrc`), or export them in each terminal before running Gradle. Gradle must run on JDK 17; macOS's `/usr/bin/java` is only a stub.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

`adb`, `sdkmanager` and `ffmpeg` are already on the PATH through `/opt/homebrew/bin`.

The repo also needs a `local.properties` (git-ignored, never committed):

```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

## Debug keystore

A debug build only installs over the existing app on the Pixel 6 if it is signed with the same debug key as the Linux machine. Copy `~/.android/debug.keystore` from the Linux machine to `~/.android/debug.keystore` on the Mac **before** the first `installDebug`. Otherwise Gradle creates a new key, and the install fails with a signature mismatch; uninstalling to get past that wipes Neal's library.

Verify on both machines:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256
```

The two SHA-256 lines must match. **Verified 2026-10-19:** Neal copied the Linux keystore to the Mac. Both read `A8:7A:5B:62:7A:03:B0:84:7A:CB:72:77:A2:72:38:4C:2D:F6:9A:E1:B2:18:38:57:C1:8D:49:2C:4B:AF:1C:0F`.

## The phone

Connect the Pixel 6 over USB and run `adb devices`. On the first connection it lists the phone as `unauthorized` until Neal accepts the "Allow USB debugging" prompt on the phone (tick "Always allow from this computer"). Then it shows `device`.

### Wi-Fi installs (the default from 2026-10-20)

Builds go to the phone over Wi-Fi with adb's wireless debugging; USB is only needed for the DHU. The Mac and the phone must be on the same network.

Once (Neal, on the phone): Settings → System → Developer options → **Wireless debugging** on (tick "Always allow on this network"), then **Pair device with pairing code**. It shows an `ip:port` and a six-digit code. On the Mac, while that screen is still open:

```
tools/dev/phone-wifi.sh pair 192.168.1.23:37123 123456
```

After that:

```
tools/dev/phone-wifi.sh install    # finds the phone (mDNS), connects, ./gradlew :app:installDebug
tools/dev/phone-wifi.sh connect    # just connects; then ANDROID_SERIAL="$(tools/dev/phone-wifi.sh serial)" adb …
```

The connect port changes whenever Wireless debugging is turned off and on or the phone restarts. The script finds the current port each time, so nothing has to be re-paired. If the phone isn't found, check that Wireless debugging is still on (Android turns it off on a new network) and that the Mac isn't on a guest network that isolates devices. The Homebrew `adb` and the SDK's (used by Gradle) are the same version (37.0.1), so they share one server. Keep them in step: a version mismatch restarts the server and drops the Wi-Fi connection.

Device-test rules (from the plan's Global Constraints): never run `:app:connectedDebugAndroidTest` (it wipes the library), never `adb reboot`, and after any library-module device test check `adb shell pm list packages app.granger`.

## Android Auto: the Desktop Head Unit

The Desktop Head Unit (DHU) shows Android Auto in a window on the Mac, driven by the phone over USB.

Install **2.1**, which is on the preview channel. 2.0 (the stable channel) disconnects from Android Auto 17.8 right after the TLS
handshake ("Failed to read from transport"):

```bash
sdkmanager --channel=1 "extras;google;auto"     # → $ANDROID_HOME/extras/google/auto, 2.1-mac-arm64
```

On the phone, once (Neal): Settings → Android Auto → tap **Version** about 10 times → allow developer settings → ⋮ →
**Developer settings** → **Unknown sources** on (Granger isn't from Play). Then, each session: ⋮ → **Start head unit server**.

On the Mac:

```bash
adb forward tcp:5277 tcp:5277
cd "$ANDROID_HOME/extras/google/auto" && ./desktop-head-unit
```

The DHU reads commands from its terminal and exits when stdin closes, so from a script or a background shell keep stdin open
(`sleep 86400 | ./desktop-head-unit`). The first connection runs Android Auto's setup on the phone; accept its prompts. If it
won't connect after a disconnect, stop and start the head unit server on the phone and run `adb forward` again.

## Case-insensitive file system

macOS volumes are case-insensitive by default, so two classes in one package whose names differ only in case overwrite each other's `.class` files in `build/`. `core/library` had both `Bookmark` and `BookMark`. `BookMark` is now `BookAnnotation` (its table is still `book_marks`, so the schema didn't change), and the build runs from the normal checkout. Don't reintroduce names that differ only in case.

Baseline on 2026-10-19 (commit `80b20e9` from a case-sensitive volume, then again after the rename from the normal checkout): `./gradlew testDebugUnitTest` BUILD SUCCESSFUL, 835 tests, 0 failures, 0 skipped (`:app` 409, `:core:library` 154, `:core:downloads` 79, `:core:books` 54, `:core:ai` 52, `:core:archive` 38, `:core:catalog` 28, `:core:zim` 21). No Linux test count was recorded to compare against.

`gradle.properties` still has the Linux machine's limits (`org.gradle.workers.max=2`, `-Xmx2g`, in-process Kotlin). They work on the Mac but are slower than they need to be.
