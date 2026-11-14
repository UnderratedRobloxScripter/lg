# LG Remote – Android project

## Why the HTML alone could not fix this
Inside the generated APK the page runs from an https origin. The WebView blocks `ws://` from
https pages (mixed content), and a WebView WebSocket cannot accept the TV's self-signed
certificate on `wss://:3001`. Neither can be changed from HTML/JS, so this is a native project.

## What was changed
- `AndroidManifest.xml`: INTERNET / network-state / Wi-Fi permissions, `usesCleartextTraffic`,
  `networkSecurityConfig`.
- `network_security_config.xml`: cleartext allowed (TVs are bare LAN IPs).
- `MainActivity.kt`: WebView with `MIXED_CONTENT_ALWAYS_ALLOW` + DOM storage, serving
  `assets/index.html` at `https://app.lgremote.local/` (stable origin, so saved TVs and
  pairing keys persist). Injects `lg_ws_shim.js` ahead of the page scripts.
- `LgSocketBridge.kt` + `lg_ws_shim.js`: `wss://<private IP>:port` goes through a native
  OkHttp WebSocket that accepts the TV's self-signed cert. Restricted to RFC1918 IPv4
  addresses; all other sockets are the normal browser WebSocket. OkHttp is the only dependency.
- `assets/index.html`: identical to your file except the failure message in `connectTV()`.
  Inside the app it now reports the real reason (timed out / refused / no route / handshake
  failed) instead of the false "from an https page" text. Ports, pairing payload and
  client-key logic are untouched.

## Build
1. Install Android Studio (Koala or newer, JDK 17).
2. File > Open > the `LGRemote` folder; accept the Gradle sync (it downloads Gradle 8.7).
3. Debug APK: Build > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.
   Or `./gradlew assembleDebug` after Android Studio has generated the wrapper.
4. Install: `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Test
1. Phone and TV on the same Wi-Fi (not a guest network with client isolation).
2. TV: enable LG Connect Apps / Mobile Connection Management.
3. Add the TV by IP, accept the prompt on the TV, confirm buttons work.
4. Debug with `adb logcat -s LGRemote` (page console output) and chrome://inspect.
   If it fails, the status line names the cause.

## Caveats
- Not compiled or run on a device here (no Android SDK in my environment). Please report any build error.
- Newer Android versions may add a runtime "local network" permission; the manifest declares
  it, but if connections fail only on such a device, check App info > Permissions.
- Release signing is needed for distribution outside debug installs.

## No Android Studio? Build in the cloud (GitHub Actions, free)
1. Create a free GitHub account and a new empty repository.
2. Upload the contents of this folder (including the hidden `.github` folder) to the repository.
3. Open the Actions tab, pick "Build APK", and wait a few minutes for the run to finish.
4. Open the finished run, download the `LGRemote-debug-apk` artifact, unzip it, and copy
   `app-debug.apk` to your phone. Allow "install unknown apps" when asked.

## Command line instead (needs JDK 17 + Android command-line tools)
    sdkmanager "platforms;android-34" "build-tools;34.0.0"
    gradle assembleDebug        # Gradle 8.7 installed
