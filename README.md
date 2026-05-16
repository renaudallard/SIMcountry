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

Rules are keyed by ISO 3166-1 alpha-2 country code (a single rule for "US"
covers all seven United States MCCs). Optional MCC and MNC narrowing
fields let you pin a rule to one specific MCC inside a country or to a
single operator. A separate "Default SIMs" card at the top of the Rules
tab sets the SIMs to use everywhere no country override matches.

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
6. Open the Rules tab. The top "Default SIMs" card sets the SIMs to use
   everywhere no country override applies. Tap the `+` button to add a
   country override: a searchable sheet lists every assigned country.
   For multi-MCC countries (USA, India, ...) an extra dropdown lets you
   restrict the rule to one specific MCC, and an MNC field narrows it
   further to a single operator.

## How a switch happens

1. `CountryWatcherService` runs as a `specialUse` foreground service and
   registers a `TelephonyCallback.ServiceStateListener` per active
   subscription. A `SubscriptionManager.OnSubscriptionsChangedListener` keeps
   that set in sync when SIMs are inserted, removed, or activated, and also
   picks up new subs that become visible after `READ_PHONE_STATE` is granted
   at runtime. All telephony callbacks run on a dedicated single-thread
   executor (`simcountry-telephony`).
2. On each service state change it computes the country (MCC and optional
   MNC) of the default data subscription and feeds it to `CountryWatcher`.
3. `CountryWatcher` applies debounce + hysteresis:
   - candidate MCC must be stable for `stabilitySec` (default 60s)
   - switching back to a country we just left requires
     `reverseHysteresisSec` (default 120s)
   - at most one switch per `minSwitchIntervalSec` (default 300s)
4. When the watcher settles on a new country, `RuleMatcher` resolves the
   registered MCC to its ISO country code and picks the most specific
   rule that still applies: ISO+MCC+MNC wins over ISO+MCC, then
   ISO+MNC, then the bare ISO-only rule. Aspects left unset on the
   matched rule fall back to `RulesDoc.defaults`. `CountryWatcherService`
   then invokes the daemon for each aspect that has a SIM assigned.
5. `OverrideDetector` records what we applied. If the default data SubId is
   later changed without our involvement, the MCC is suppressed for
   `overrideSuppressionSec` (default 1h) and surfaces an undo in Status. Only
   the most recent switch carries an in-flight override check; the previous
   one is cancelled.
6. `KeyguardGate` skips switches while the device is locked.
7. If the daemon disconnects and reconnects, the service re-applies the
   current settled country against the latest rules so a switch that fired
   while the daemon was down is not lost.

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
  telephony/             CountryWatcher, SimRegistry, OverrideDetector,
                         KeyguardGate, Mcc (E.212 country dataset)
  rules/                 CountryRule (ISO-keyed), RulesStore (JSON, with
                         v1 -> v2 migration), RuleMatcher
  service/               CountryWatcherService (foreground), BootReceiver
  ui/                    Compose screens; rules tab hosts the Defaults
                         card, country picker, and rule editor
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
  The required callback `PendingIntent` is minted on a
  `com.android.shell` package context, so the call goes through on devices
  where the shell UID can mint PendingIntents for that package. On devices
  where the shell UID is not granted `WRITE_EMBEDDED_SUBSCRIPTIONS` the
  call is still rejected with `SecurityException`; the app then switches
  the default to whatever profile is already active and logs the failure.
- **Daemon does not survive a reboot in v0.1.** Re-run the ADB command after
  every reboot. v0.2 will add Wireless-ADB self-restart.
- **App-process death is recoverable but not automatic.** If the app
  process is killed while the daemon is alive, the in-memory Binder
  reference is lost; the daemon stays idle until the next ADB run. After
  the daemon reattaches, the watcher re-applies the current country
  automatically.

## Security

- The `SimControlProvider` exposes exactly one call method, `attachShell`,
  and accepts it only when the caller's UID is `shell` (2000) or `root`
  (0). Other apps cannot push a fake daemon, and there is no read-back
  method that would let another app fetch the privileged Binder.
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

v0.1.2: functional skeleton with post-review fixes applied, plus an
ISO-keyed rule schema, an E.212 country picker, and a Default SIMs card.
Unit tests cover the rule matcher (8 cases including specificity scoring)
and country-watcher state machine. The full switching flow has not yet
been validated on physical hardware; manual device testing on a Pixel
running Android 13+ is mandatory before any release.

---

## Support this project

If you find SIMcountry useful, you can support development:

[![PayPal](https://img.shields.io/badge/PayPal-Donate-blue.svg?logo=paypal)](https://www.paypal.me/RenaudAllard)
