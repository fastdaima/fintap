# finTap

Fast expense tracker for Android. Add an expense in two seconds, see where the money went today, this week, this month.

- **Add fast:** a small popup with the keyboard already up: name, price, done. Opens in ~170 ms.
- **Dashboard:** totals for today, this week (from Monday) and this month, plus today's entries.
- **Detailed lists:** from the side menu. Tap any entry to change the name or price, or delete it.
- **Back tap (experimental, off by default):** double-tap the back of the phone to open the add popup.
- **Private:** everything stays on the phone. No internet permission, no accounts, no analytics.
- **Small:** ~120 KB APK, one tiny dependency.

Amounts are in ₹ with Indian digit grouping (1,23,456.50).

## Install

Download the latest `finTap-<version>.apk` from [Releases](https://github.com/fastdaima/fintap/releases) and open it on your phone. Android will ask you to allow installs from your browser or file manager.

Also available through [IzzyOnDroid](https://apt.izzysoft.de/fdroid/) (once accepted).

Two home-screen icons are installed:

- **finTap:** the dashboard and lists.
- **finTap Add:** opens the add popup directly. Put it on your home screen, or point a gesture or shortcut at it.

Requires Android 10 or newer.

## Back tap (experimental)

Off by default. To turn it on: open finTap → side menu → **Back tap (experimental)** → **Open Accessibility settings** → **finTap back tap** → On.

"Clear storage" for finTap in Android settings also switches back tap off; turn it on again the same way.

If the switch is greyed out with "Restricted setting": go to **Settings → Apps → finTap → ⋮ (top right) → Allow restricted settings**, then try again. Android blocks accessibility services for apps installed outside the Play Store until you allow this.

**Why an accessibility service?** Android only lets an app open a screen from the background in a few cases, and a system-bound accessibility service is one of them. finTap's service subscribes to no accessibility events and cannot read your screen. It only listens to the motion sensor.

**When it listens:** only while the phone is unlocked and the screen is on. With the screen off it does nothing. See [POWER.md](POWER.md): about 0.5% of one low-power CPU core while listening, ~0.1% of the battery per day.

**How it decides:** a finger tap on the back gives a sharp, short push toward the screen side (+z). A tap on the screen pushes the other way. Hand movement is slow. It needs two sharp taps 150–400 ms apart, a still phone before them, and nothing after (so triple taps and drumming don't count). See `app/src/main/java/com/fintap/TapDetector.kt`.

**Limits, honestly:**

- It was tuned on one phone (Nothing A059, 458 Hz accelerometer) with one person's taps. Other phones and hands may miss taps or misfire.
- An accelerometer can't tell a back tap from any other knock that pushes the phone toward you.
- If it misfires for you, turn it off. Reports with the decision log are very welcome (below).

## Reporting a back-tap misfire or miss

finTap keeps a small decision log in `Android/data/io.github.fastdaima.fintap/files/`:

- `decisions.log` lists every tap judgment, why it was accepted or rejected, and whether you saved or dismissed each popup.
- `fire-<id>.bin` holds about 2.6 s of raw motion data before each trigger (newest 20 kept).

Attach these to a [GitHub issue](https://github.com/fastdaima/fintap/issues), with your phone model and what you were doing. A misfire recording becomes a permanent test case.

## Build

Needs JDK 17 and the Android SDK (platform 35).

```sh
./gradlew testDebugUnitTest      # unit tests + synthetic back-tap tests
FINTAP_REC_DIR=$PWD/calib ./gradlew testDebugUnitTest   # also replay real recordings in calib/
./gradlew assembleRelease        # app/build/outputs/apk/release/
```

Release signing reads `~/.fintap/keystore.properties` (or the path in `FINTAP_KEYSTORE_PROPS`):

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file, the release APK is built unsigned.

## Privacy

finTap has no internet permission. Expenses are stored in a local SQLite database on the phone and never leave it. The back-tap decision log stays on the phone unless you share it yourself.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
