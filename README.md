<p align="center">
  <img src="branding/readme_banner_hero.png" alt="SIMcountry" />
</p>

<p align="center">
  Per-country default SIM switching for Android, with an in-tree Shizuku-style daemon.<br/>
  Switches data, voice, and SMS independently when the registered cellular network changes country.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/minSdk-33-3DDC84?logo=android&logoColor=white" alt="minSdk 33" />
  <img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.0" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/build-Gradle%208.10-02303A?logo=gradle&logoColor=white" alt="Gradle 8.10" />
  <img src="https://img.shields.io/badge/license-BSD--2--Clause-blue" alt="BSD 2-Clause license" />
  <img src="https://img.shields.io/github/downloads/renaudallard/SIMcountry/total?logo=github&logoColor=white&label=downloads" alt="GitHub downloads" />
</p>

---

## What it does

Set one default SIM, then add country overrides. When the phone registers on a Swiss network (MCC 228), SIMcountry switches data to your Swiss eSIM and leaves voice on the EU SIM. Back on an EU MCC, the defaults take over again. Physical SIMs and eSIMs are equal citizens; inactive eSIM profiles can be activated as part of the switch.

## Highlights

- **Per-aspect control.** Switch data, voice, and SMS independently or together.
- **Physical SIMs and eSIMs.** Including eSIM profiles that are currently inactive (where the device allows it).
- **No third-party app required.** The privileged daemon ships inside the same APK and binds over a Binder handover.
- **Country picker.** Searchable list of every ISO 3166-1 country with its E.212 MCCs.
- **Specificity-aware matching.** Optional MCC and MNC narrowing within a country; the most specific rule wins.
- **Hysteresis built in.** Debounce, reverse hysteresis, minimum switch interval, and per-MCC suppression after a manual override.
- **No external runtime dependency.** No Shizuku, no root, no Magisk.
- **Wireless-ADB autostart.** One-time pairing with the six-digit code from Developer options; the daemon then comes back on every boot without a PC.

---

## Privilege model

Changing the default SIM through `SubscriptionManager.setDefault*SubId` and switching eSIM profiles through `EuiccManager.switchToSubscription` require signature-only permissions (`MODIFY_PHONE_STATE`, `WRITE_EMBEDDED_SUBSCRIPTIONS`) that no sideloaded APK can be granted. SIMcountry ships a small daemon in the same APK; the user starts it once from a PC over ADB. The daemon runs as the shell UID (2000), which the platform grants `MODIFY_PHONE_STATE` by default, and publishes a Binder service that the app proxies into through a ContentProvider handover. No third-party app is required.

This is the same architectural pattern as Shizuku, implemented in-tree so the app has no external runtime dependency.

### Autostart

After the one-time ADB command starts the daemon for the first time, the app can be paired with Wireless Debugging so future boots re-launch the daemon automatically:

1. In the device's Developer options, enable **Wireless debugging** and tap **Pair device with pairing code**.
2. In SIMcountry's **Status** tab, tap **Pair Wireless ADB** and type the six-digit code from the dialog.
3. From then on, the `BootReceiver` runs the same daemon-start command over Wireless ADB on every `BOOT_COMPLETED`, presenting the RSA key authorised during pairing.

To undo the pairing, tap **Forget pairing** in the Status tab; SIMcountry deletes its RSA key and generates a new one. The device itself still trusts the old key in its Wireless Debugging list (Android does not expose a per-key revoke to non-system apps), so open Developer options and tap **Revoke pairings** if you also want to clean up the device side.

The pairing handshake follows AOSP's `pairing_connection` wire format: TLSv1.3 with ALPN `adb`, SPAKE2 over Ed25519 with the M and N constants from BoringSSL, AES-128-GCM-encrypted PeerInfo.

---

## Setup

1. Install the release APK on the target device.
2. Open the app once so the package is registered.
3. From a PC with the device connected over USB and USB Debugging enabled, run the command shown in the app's Status screen. The template is:

```sh
adb shell 'APK=$(pm path it.allard.simcountry | sed "s/^package://;1q"); \
  exec "$(dirname "$APK")/lib/arm64/libsimcountryd.so" --foreground'
```

`--foreground` keeps the daemon attached to the shell so its log lines are visible. Without it the binary daemonises (double-fork + setsid + reopen stdio against `/dev/null`) and detaches from the spawning shell session, which is what the Wireless-ADB autostart wants.

For a debug build, replace `it.allard.simcountry` with `it.allard.simcountry.debug`.

4. The Status banner turns green and shows the daemon's pid and version.
5. Open the **SIMs** tab and tap refresh: the daemon enumerates every subscription it can see, including inactive eSIM profiles.
6. Open the **Rules** tab. The top **Default SIMs** card sets the SIMs to use everywhere no country override applies. Tap the `+` button to add a country override: a searchable sheet lists every assigned country. For multi-MCC countries (USA, India, ...) a dropdown lets you restrict the rule to one specific MCC, and an MNC field narrows it further to a single operator.

---

## How a switch happens

1. `CountryWatcherService` runs as a `specialUse` foreground service and registers a `TelephonyCallback.ServiceStateListener` per active subscription. A `SubscriptionManager.OnSubscriptionsChangedListener` keeps that set in sync when SIMs are inserted, removed, or activated, and also picks up new subs that become visible after `READ_PHONE_STATE` is granted at runtime. Callbacks run on a dedicated single-thread executor (`simcountry-telephony`).
2. On each service-state change it computes the country (MCC and optional MNC) of the default data subscription and feeds it to `CountryWatcher`.
3. `CountryWatcher` applies debounce and hysteresis:

   | Knob | Default |
   | :--- | ---: |
   | Candidate stability | 60 s |
   | Reverse hysteresis | 120 s |
   | Minimum switch interval | 300 s |
   | Override suppression after manual change | 1 h |

4. When the watcher settles on a new country, `RuleMatcher` resolves the registered MCC to its ISO country code and picks the most specific rule that still applies: ISO+MCC+MNC wins over ISO+MCC, then ISO+MNC, then the bare ISO-only rule. Aspects left unset on the matched rule fall back to `RulesDoc.defaults`. `CountryWatcherService` then invokes the daemon for each aspect that has a SIM assigned.
5. `OverrideDetector` records what we applied. If the default data SubId is later changed without our involvement, the MCC is suppressed for `overrideSuppressionSec` and an undo banner appears in Status. Only the most recent switch carries an in-flight override check; older ones are cancelled.
6. `KeyguardGate` skips switches while the device is locked.
7. If the daemon disconnects and reconnects, the service re-applies the current settled country against the latest rules so a switch that fired while the daemon was down is not lost.

---

## Build

Requires JDK 21, the Android SDK with platform 35 installed, NDK 27.2.12479018 (installed via `sdkmanager 'ndk;27.2.12479018'`), and a Rust toolchain with the `aarch64-linux-android` target (`rustup target add aarch64-linux-android`). The Gradle build invokes `cargo` to compile the native daemon (`daemon-native/`) and stages it under `app/src/main/jniLibs/arm64-v8a/libsimcountryd.so`.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
ANDROID_HOME=$HOME/Android/Sdk \
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests:

```sh
./gradlew testDebugUnitTest
```

---

## Project layout

```
daemon-native/           Rust ELF that runs as shell UID. Speaks libbinder_ndk
                         to ISub / IPhoneSubInfo / IEuiccController, hosts
                         a JSON-over-TCP control socket on 127.0.0.1:39351.

app/src/main/java/it/allard/simcountry/
  daemon/autorestart/    Wireless-ADB self-restart: ADB protocol, RSA
                         key, TLS+ALPN, mDNS, Ed25519 math, SPAKE2,
                         pairing handshake, autostart coordinator
  ipc/                   SimControlSocketClient: typed JSON client for
                         the native daemon's control socket
  telephony/             CountryWatcher, SimRegistry, OverrideDetector,
                         KeyguardGate, Mcc (E.212 country dataset)
  rules/                 CountryRule (ISO-keyed), RulesStore (JSON, with
                         v1 -> v2 migration), RuleMatcher
  service/               CountryWatcherService (foreground), BootReceiver
  ui/                    Compose screens; rules tab hosts the Defaults
                         card, country picker, and rule editor
  data/                  AppContainer (manual DI)
```

---

## Permissions

| Permission | Type | Purpose |
| :--- | :--- | :--- |
| `READ_PHONE_STATE` | runtime | observe service state and registered network |
| `POST_NOTIFICATIONS` | runtime | foreground-service notification |
| `INTERNET` | normal | loopback TCP to the native daemon's control socket, and to adbd's Wireless-ADB endpoint for autostart |
| `RECEIVE_BOOT_COMPLETED` | normal | restart the watcher after reboot |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | normal | host the country watcher |

The daemon, running as shell UID, uses framework permissions tied to that UID (notably `MODIFY_PHONE_STATE`).

---

## Limitations

- **eSIM profile activation** uses `EuiccManager.switchToSubscription`. The required callback `PendingIntent` is minted on a `com.android.shell` package context, so the call goes through on devices where the shell UID can mint PendingIntents for that package. On devices where the shell UID is not granted `WRITE_EMBEDDED_SUBSCRIPTIONS` the call is rejected with `SecurityException`; the daemon logs the failure and the subsequent `setDefault*` call against the still-inactive subscription has no effect, so the rule produces no change for that aspect.
- **Autostart depends on a one-time Wireless-ADB pairing.** After pairing, the daemon comes back on every boot through the `BootReceiver` hook. Before pairing, the daemon has to be started from a PC with the ADB command shown in the Status screen.
- **If adbd ever forgets our paired key**, the connect path falls through to the legacy AUTH dance and we send `AUTH(RSAPUBLICKEY)`. adbd then shows an **Always allow from this computer?** dialog *on the device* and waits for the user to tap **Allow** before completing the handshake. From boot that prompt won't be answered, so the autostart silently fails. Re-pair from the **Pair Wireless ADB** screen and the next boot will work again.
- **App-process death is recoverable but not automatic.** If the app process is killed while the daemon is alive, the in-memory Binder reference is lost; the daemon stays idle until the next ADB run. After the daemon reattaches, the watcher re-applies the current country automatically.

---

## Security

- The `SimControlProvider` exposes exactly one call method, `attachShell`, and accepts it only when the caller's UID is `shell` (2000) or `root` (0). Other apps cannot push a fake daemon, and there is no read-back method that would let another app fetch the privileged Binder.
- The daemon process exits if launched as a non-shell, non-root UID.
- ADB debugging must be enabled on the device for setup; the user is responsible for keeping it off in untrusted environments.

---

## Status

v0.2.1: native Rust daemon replacing the original Kotlin daemon (Android 16 broke `app_process` for shell uid), JSON-over-TCP control socket on `127.0.0.1:39351` with an APK-hash auth handshake, libbinder_ndk client for `ISub` / `IPhoneSubInfo` / `IEuiccController`, an ISO-keyed rule schema with an E.212 country picker and a Default SIMs card, plus a Wireless-ADB autostart flow that auto-recovers when Wi-Fi or Wireless Debugging come back online and surfaces an offline notification linking to Developer options when the daemon is down. Verified end-to-end on Motorola Lhotse / Android 16 (default-data sub-id switching, autostart over Wireless ADB, post-update auto-restart). Unit tests cover the rule matcher, the country-watcher state machine, the ADB protocol codec, the RSA legacy key serializer, Ed25519 group laws (including off-curve point rejection), the AES-GCM stream layer, and SPAKE2 client/server round trips: 48 tests in total.

---

## Support this project

If you find SIMcountry useful, you can support development:

[![PayPal](https://img.shields.io/badge/PayPal-Donate-blue.svg?logo=paypal)](https://www.paypal.me/RenaudAllard)

## License

BSD 2-Clause "Simplified" License. Copyright (c) 2026, Renaud Allard <renaud@allard.it>. See [LICENSE](LICENSE) for the full text. Every Kotlin source file carries the same header.
