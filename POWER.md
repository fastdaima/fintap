# finTap — power and heat

Phone: Nothing A059 (Android 16), 5000 mAh battery (4711 mAh learned), accelerometer LSM6DSV.
Measured 2026-11-06 over USB with adb. Every number is marked **measured** or **estimate**.

## Short answer

- **Battery:** roughly 0.1% of the battery per day with 5 hours of screen-on use. You will not see it in Settings → Battery.
- **Heat:** no. The app adds a few milliwatts. A phone gets warm at about 1,000–3,000 mW (games, camera, charging).
- **Screen off or locked:** zero. The app does no work at all.

## What runs, and when

| Phone state | What finTap does | Cost |
|---|---|---|
| Screen off / locked | Nothing. Accelerometer listener unregistered. Process idle in memory. | 0 CPU (**measured**) |
| Unlocked, screen on | Reads accelerometer at 458 Hz, batched in the sensor chip's buffer and handed to the app every 50 ms (~20 wakeups/s). ~20 math operations per sample, no memory allocation. | 0.5% of one small CPU core (**measured**) |
| Back double-tap / opening the app | Opens the add popup or dashboard, like opening any small app. Cold start ~170 ms. | Same as any app for the seconds you use it |

## Measurements

**CPU while listening (unlocked, screen on, home screen, no touches on the app):**
48 CPU ticks (480 ms of CPU time) in 96.5 s = **0.50% of one core**.
The thread ran on cpu0, the low-power cluster (max 1.8 GHz), not the performance cores (2.4/2.5 GHz).

**CPU while locked / screen off:**
0 ticks in 60 s = **0%**. The system sensor log confirms the listener is added on unlock and removed on screen-off:

```
17:34:35 + lsm6dsv Accelerometer  samplingPeriod=2500us batchingPeriod=50000us  com.fintap.TapService
17:35:45 - lsm6dsv Accelerometer  com.fintap.TapService
```

**Memory:** 25 MB PSS. The accessibility service keeps the process loaded so the back tap works instantly. Idle RAM costs no meaningful power.

**Temperature:** unchanged across the 96 s listening window (battery 38.4 °C → 38.4 °C, CPU0 57.4 °C → 57.4 °C). The phone was charging over USB, which is why it was warm. This only shows no visible change. A few mW is far below what the temperature sensors can resolve.

## Power estimate (milliwatts)

| Part | Estimate | Basis |
|---|---|---|
| CPU (0.5% of a small core) | ~0.5–2 mW | small-core active power ~100–200 mW × 0.5%, plus wakeup overhead |
| Accelerometer at 458 Hz | ≤ 1.2 mW | datasheet upper bound (≤ 0.65 mA × 1.8 V). The accelerometer is already on for other system features, so the extra cost is likely lower. |
| **Total, screen on** | **~2–3 mW** | |
| Whole phone, screen on | ~1,000–3,000 mW | typical for display + system |

The app adds about **0.1–0.3%** to what the phone already draws with the screen on.

**Per day:** 3 mW × 5 h screen-on = 15 mWh. Battery ≈ 4711 mAh × 3.87 V ≈ 18,200 mWh, so **≈ 0.1% of a full charge per day**.

## Why it stays this low (design choices)

1. **Sensor only while unlocked.** Screen off means listener off. The CPU is never kept awake for us, and we hold no wakelock.
2. **Hardware batching.** The sensor chip stores samples and hands them over 20 times a second instead of 458. Fewer CPU wakeups matters more than the math itself.
3. **Tiny per-sample work.** About 20 operations per sample, no memory allocation, no logging on the normal path.
4. **No foreground service, no notification, no network, no GPS, no gyroscope.** The gyroscope would have helped catch side knocks, but it costs about 1 mW more, so it was left out.
5. **Accelerometer only, at the rate the gesture needs.** Taps last 10–30 ms, so 458 Hz is needed. Lower rates miss the peak.

## Limits of this measurement

- One 96 s screen-on sample and one 60 s locked sample, not a full day.
- Milliwatt figures are estimates from CPU time and the datasheet. Without root you can't measure power per app directly, and the phone was on USB power.
- Real-world check: after a normal day on battery, open Settings → Battery → Battery usage and look for finTap. Expect it near the bottom, around 0%.
