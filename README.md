# SIMcountry

Copyright (c) 2026 Renaud Allard <renaud@allard.it>. BSD 2-clause; see
`LICENSE`.

Android app that switches the default SIM (data, voice, SMS, each
independently) based on the country of the registered cellular network.

Example: default SIM in the EU. When the phone registers on a Swiss network
(MCC 228), SIMcountry switches data to the Swiss eSIM and leaves voice on the
EU SIM. When the device returns to an EU MCC, the default rule (or no rule)
applies and the SIMs revert.

Both physical SIMs and eSIMs are supported, including eSIM profiles that are
currently inactive; the app will request activation before switching the
default to them.

## Privilege model

Changing the default SIM via `SubscriptionManager.setDefault*SubId` and
switching eSIM profiles via `EuiccManager.switchToSubscription` require
signature permissions (`MODIFY_PHONE_STATE`, `WRITE_EMBEDDED_SUBSCRIPTIONS`)
that no sideloaded APK can be granted. SIMcountry therefore ships a small
daemon inside the same APK, launched once by the user from a PC over ADB.
The daemon runs as the shell UID (2000), which the platform grants
`MODIFY_PHONE_STATE` by default. It publishes a Binder service that the app
proxies into via a ContentProvider handover. No third-party app required.

This is the same architectural pattern as Shizuku, implemented in-tree so the
app has no external runtime dependency.

### v0.1 scope

- One-time ADB command per boot. After a reboot, run the command again.
- v0.2 will add Wireless-ADB self-restart so the daemon survives reboots
  without re-running anything.

## Setup

1. Build (or install) the debug APK on the target device.
2. Open the app once so the package is registered.
3. From a PC with the device connected via USB and USB Debugging enabled,
   run the command shown in the app's Status screen. The template is:

```
adb shell sh -c 'APK=$(pm path it.allard.simcountry.debug | sed "s/^package://;1q"); \
  exec /system/bin/app_process -Djava.class.path="$APK" /system/bin \
    --nice-name=simcountry-daemon \
    it.allard.simcountry.daemon.DaemonEntrypoint it.allard.simcountry.debug'
```

For a release build, replace `it.allard.simcountry.debug` with
`it.allard.simcountry`.

4. The Status screen banner turns green and shows the daemon's pid and
   version.
5. Open the SIMs tab and tap refresh: the daemon enumerates all
   subscriptions, including inactive eSIM profiles.
6. Open the Rules tab and add at least one rule. MCC values are 3 digits
   (Switzerland 228, France 208, Germany 262, Italy 222, UK 234, USA 310,
   etc.).

## How a switch happens

1. `CountryWatcherService` runs as a `specialUse` foreground service and
   registers a `TelephonyCallback.ServiceStateListener` per active
   subscription.
2. On each service state change it computes the country (MCC and optional
   MNC) of the default data subscription and feeds it to `CountryWatcher`.
3. `CountryWatcher` applies debounce + hysteresis:
   - candidate MCC must be stable for `stabilitySec` (default 60s)
   - switching back to a country we just left requires
     `reverseHysteresisSec` (default 120s)
   - at most one switch per `minSwitchIntervalSec` (default 300s)
4. When the watcher settles on a new country, `RuleMatcher` finds the rule
   (MCC+MNC exact wins over MCC-only) and `CountryWatcherService` invokes
   the daemon for each aspect listed in the rule.
5. `OverrideDetector` records what we applied. If the default data SubId is
   later changed without our involvement, the MCC is suppressed for
   `overrideSuppressionSec` (default 1h) and surfaces an undo in Status.
6. `KeyguardGate` skips switches while the device is locked.

## Build

Requires JDK 21 and the Android SDK with platform 35 installed.

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
ANDROID_HOME=$HOME/Android/Sdk \
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests:

```
./gradlew testDebugUnitTest
```

## Project layout

```
app/src/main/aidl/it/allard/simcountry/ipc/
  ISimControl.aidl       cross-process control surface
  SubInfo.aidl           parcelable

app/src/main/java/it/allard/simcountry/
  daemon/                runs as shell UID; calls hidden telephony APIs
  ipc/                   ContentProvider handover, client wrapper
  telephony/             CountryWatcher, SimRegistry, OverrideDetector, KeyguardGate
  rules/                 CountryRule, RulesStore (JSON), RuleMatcher
  service/               CountryWatcherService (foreground), BootReceiver
  ui/                    Compose screens (Status, Rules, SIMs)
  data/                  AppContainer (manual DI)
```

## Permissions

Declared:

- `READ_PHONE_STATE`: runtime, required to observe service state
- `POST_NOTIFICATIONS`: runtime, foreground service notification
- `RECEIVE_BOOT_COMPLETED`: restart the watcher after reboot
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`
- `QUERY_ALL_PACKAGES`: debug-only convenience

The daemon, running as shell UID, uses framework permissions tied to that
UID (notably `MODIFY_PHONE_STATE`).

## Limitations

- **eSIM profile activation** uses `EuiccManager.switchToSubscription`.
  On some devices the shell UID is not granted
  `WRITE_EMBEDDED_SUBSCRIPTIONS` and the call is rejected with
  `SecurityException`. In that case the app still switches the default to
  an already-active profile, but cannot activate a disabled profile by
  itself.
- **Daemon does not survive a reboot in v0.1.** Re-run the ADB command after
  every reboot. v0.2 will add Wireless-ADB self-restart.
- **Single-process orchestration in v0.1.** If the app process is killed
  while the daemon is alive, the app will rediscover the daemon's Binder
  the next time the daemon attaches (typically after the next ADB run).

## Security

- The `SimControlProvider` only accepts an attached Binder if the caller's
  UID is `shell` (2000) or `root` (0). Other apps cannot push a fake daemon.
- The daemon process exits if launched as a non-shell, non-root UID.
- ADB debugging must be enabled on the device for setup; the user is
  responsible for keeping it off in untrusted environments.

## Roadmap

- v0.2: Wireless-ADB self-restart so the daemon survives reboots without
  re-running anything from a PC.
- v0.2: Per-aspect monitoring (watch each aspect's sub's MCC, not only the
  data sub's MCC).
- Later: i18n country picker, broader test coverage on real OEM devices,
  release signing instructions.

## Status

v0.1: functional skeleton. Unit tests cover the rule matcher and
country-watcher state machine. The full switching flow has not yet been
validated on physical hardware; manual device testing on a Pixel running
Android 13+ is mandatory before any release.
