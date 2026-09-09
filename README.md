# TapShim

Make the Bose "Spotify" headphone shortcut open whatever you want.

Bose QuietComfort Ultra headphones (and other Bose products with a configurable
shortcut) offer a "Spotify" option: touch and hold the volume strip and Spotify
resumes. TapShim takes over that shortcut, without root, and lets it launch any
app, open a URL, start a specific activity, or fire a broadcast for Tasker.

No Bose account, no Bose app changes, no root. Android 12 or newer.

## How it works

The Bose app never launches Spotify. It writes a "Spotify mode" flag into the
headphone firmware, and the headphones then expose a small Bluetooth serial
(RFCOMM) service. The Spotify app connects to that service whenever the
headphones connect over A2DP and blocks reading from it; each shortcut press is
one short packet.

TapShim does the same thing Spotify does:

1. A manifest receiver watches for A2DP (and LE Audio) connection changes.
2. A foreground service opens an insecure RFCOMM socket to the tap service UUID
   (`9B26D8C0-A8ED-440B-95B0-C4714A518BCC`) and reads frames.
3. Each frame (`0x01`, length, three NUL-terminated strings) becomes a tap, and
   the configured actions run.

Only one app can hold that socket, so Spotify has to be uninstalled.

## Install

Releases are signed APKs on the
[Releases page](https://github.com/pmaxhogan/tapshim/releases).

With [Obtainium](https://github.com/ImranR98/Obtainium) you can track releases
automatically: add `https://github.com/pmaxhogan/tapshim` as a source, or import
[`obtainium.json`](obtainium.json). The direct add link is
`obtainium://add/https://github.com/pmaxhogan/tapshim`.

## Setup

1. With Spotify still installed, open the Bose app, go to the Shortcut settings,
   and choose **Spotify**. The Bose app only lets you pick it while Spotify is
   installed. The choice is stored in the headphones.
2. Uninstall Spotify. TapShim's setup screen has Install and Uninstall buttons
   for this step.
3. Open TapShim and grant:
   - **Nearby devices** (Bluetooth), required.
   - **Display over other apps**, required if you want to launch an app, open a
     URL, or open an activity. Android silently blocks starting activities from
     the background without it. The Tasker broadcast does not need it.
   - **Notifications**, optional; shows the listener while it is connected.
4. Pick what a tap should do. All four actions are independent and can be
   combined:
   - **Launch an app** (default: YouTube Music). Type a package name or use the
     picker.
   - **Send broadcast for Tasker**: action `dev.maxhogan.tapshim.TAP` with extras
     `clientId`, `deviceName`, `manufacturer`, `address`, `rawHex`, `simulated`.
     In Tasker: Event > System > Intent Received, action
     `dev.maxhogan.tapshim.TAP`.
   - **Open a URL**.
   - **Open a specific activity**: `package/class`, typed or picked.
5. Reconnect the headphones. The Status card shows the session and the Event log
   records every connect attempt, error, and tap so problems can be diagnosed
   from the phone alone.

Press **Simulate tap** to run the configured actions without headphones.

## Testing without headphones

Debug builds expose an adb receiver:

```
# run the configured actions as if a tap arrived
adb shell am broadcast -a dev.maxhogan.tapshim.debug.SIMULATE_TAP \
  -n dev.maxhogan.tapshim.debug/dev.maxhogan.tapshim.debug.SimulateTapReceiver

# pretend a device connected / disconnected over A2DP
adb shell am broadcast -a dev.maxhogan.tapshim.debug.CONNECT --es address AA:BB:CC:DD:EE:FF \
  -n dev.maxhogan.tapshim.debug/dev.maxhogan.tapshim.debug.SimulateTapReceiver
adb shell am broadcast -a dev.maxhogan.tapshim.debug.DISCONNECT --es address AA:BB:CC:DD:EE:FF \
  -n dev.maxhogan.tapshim.debug/dev.maxhogan.tapshim.debug.SimulateTapReceiver
```

Press Home before firing `SIMULATE_TAP`; if TapShim is in the foreground the
launch will succeed even without the overlay permission and the test proves
nothing. The `CONNECT` hook only works within a few seconds of TapShim leaving
the foreground, because a plain adb broadcast has no foreground-service
exemption (the real Bluetooth broadcast does).

## Development

```
./gradlew testDebugUnitTest koverVerifyDebug lintDebug assembleDebug
```

- Kotlin, Jetpack Compose, minSdk 31.
- Unit tests (JUnit 4 + Robolectric) cover the protocol, the action planner and
  runner, the config store, the event log, and the receivers. Kover enforces
  85% overall line coverage and 75% per package; the UI, the socket service, and
  the debug receiver are excluded because they need a device.
- CI runs tests, coverage, and lint on every PR. Every push to `main` builds a
  signed release APK and publishes a GitHub release tagged
  `v<major>.<minor>.<commit count>` (major.minor come from `VERSION`).
- Dependabot opens weekly PRs; minor and patch bumps auto-merge once CI passes.

To build a signed release locally, export `TAPSHIM_KEYSTORE_PATH`,
`TAPSHIM_KEYSTORE_PASSWORD`, `TAPSHIM_KEY_ALIAS`, and `TAPSHIM_KEY_PASSWORD`,
then run `./gradlew assembleRelease`.

## Credits

Protocol details come from reading the Bose (10.2.4) and Spotify (9.1.80)
Android apps. Nothing from either app is included here.

## License

MIT, see [LICENSE](LICENSE).
