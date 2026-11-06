package com.fintap

import kotlin.math.abs
import kotlin.math.hypot

// Back double-tap detector over raw accelerometer samples (m/s², device axes, z out of screen).
// Pure: no Android imports, so it runs in JVM tests and replays recorded traces.
//
// What a fingertip strike on the back looks like on this phone (LSM6DSV, 458 Hz, recorded):
//   z jumps +13..+24 within 1-2 samples, falls to <0.55 of peak 3 samples later, lateral <0.35 of z.
//   Hand recoil afterwards is large but SLOW, so everything keys on edges (change over 3 samples),
//   never on magnitude. A screen tap is the same edge with negative sign.
// Misfire defence is layered: every rule alone rejects all recorded non-back-tap pairs.
//   shape (sharp, strong, fast decay, mostly z) + timing (gap window fit to the owner's cadence)
//   + context (quiet before, nothing between, nothing after = not a triple / not drumming).
// Windows are counted in samples and were fit at ~458 Hz.
//
// Cost per sample: ~20 flops, zero allocation.
class TapDetector {
    companion object {
        const val NONE = 0
        const val TAP = 1    // one valid back tap (informational)
        const val DOUBLE = 2 // fire

        const val MS = 1_000_000L

        const val EDGE = 4f           // |change over 3 samples| that counts as an event on any axis
        const val RISE_MIN = 11f      // tap peak above pre-edge baseline (recorded taps: 12.7..24.2)
        const val RISE_MAX = 60f      // above = hard impact (set down on table recorded at 155)
        const val LAT_RATIO = 0.5f    // lateral peak / z peak (recorded taps <= 0.35)
        const val DECAY = 0.6f        // value 3 samples after peak / peak (taps <= 0.55, bumps >= 0.63)
        const val SHARP_SAMPLES = 3   // peak must land within this many samples of the edge
        const val SHAPE_SAMPLES = 10  // samples collected before classifying an edge
        const val FLAT_GZ = 9.3f      // |gravity z| above = lying flat; a finger can't reach the back
        const val REFRACTORY_NS = 100 * MS // ignore ringing/recoil after an event
        const val MIN_GAP_NS = 150 * MS    // tap1 -> tap2 onset. Owner measured 189..290 ms across sessions;
        const val MAX_GAP_NS = 400 * MS    // gap alone does NOT separate taps from bumps (170..220), shape does.
        const val PRE_QUIET_NS = 300 * MS  // no event before tap 1
        const val POST_QUIET_NS = 350 * MS // no event after tap 2 (catches a third tap at owner cadence)
        const val COOLDOWN_NS = 1500 * MS
    }

    var log: ((String) -> Unit)? = null

    // Last 4 samples, for the 3-sample edge.
    private val rx = FloatArray(4)
    private val ry = FloatArray(4)
    private val rz = FloatArray(4)
    private var count = 0L
    private var lastT = 0L
    private var gz = 0f

    // Edge under classification.
    private var cand = false
    private var candT = 0L
    private var candN = 0
    private var baseX = 0f
    private var baseY = 0f
    private var baseZ = 0f
    private val candZ = FloatArray(SHAPE_SAMPLES)
    private var candLat = 0f

    private var refractUntil = 0L
    private var lastEvent = Long.MIN_VALUE / 2   // any tap or disturbance
    private var lastDisturb = Long.MIN_VALUE / 2 // events that are not valid back taps
    private var tap1 = 0L
    private var tap2 = 0L
    private var cooldownUntil = 0L

    fun reset() {
        count = 0L
        cand = false
        tap1 = 0L
        tap2 = 0L
        lastEvent = Long.MIN_VALUE / 2
        lastDisturb = Long.MIN_VALUE / 2
    }

    fun feed(t: Long, x: Float, y: Float, z: Float): Int {
        if (count > 0L) {
            val dt = t - lastT
            if (dt <= 0L) return NONE // duplicate timestamp in a batch flush
            if (dt > 50 * MS) {
                // Stream gap (re-register, FIFO overflow): history is meaningless.
                reset()
            }
        }
        lastT = t
        gz = if (count == 0L) z else gz + (z - gz) * 0.005f // ~0.5 s time constant at 458 Hz
        val slot = (count and 3L).toInt()
        rx[slot] = x; ry[slot] = y; rz[slot] = z
        count++
        if (count < 4L) return NONE
        val old = ((count - 4L) and 3L).toInt() // sample 3 steps back

        if (cand) {
            candZ[candN] = z - baseZ
            val lat = hypot(x - baseX, y - baseY)
            if (lat > candLat) candLat = lat
            candN++
            if (candN == SHAPE_SAMPLES) {
                cand = false
                refractUntil = candT + REFRACTORY_NS
                classify()
            }
        } else if (t >= refractUntil) {
            val dz = z - rz[old]
            val dl = hypot(x - rx[old], y - ry[old])
            if (dz >= EDGE) {
                cand = true
                candT = t
                baseX = rx[old]; baseY = ry[old]; baseZ = rz[old]
                candZ[0] = dz
                candLat = dl
                candN = 1
            } else if (-dz >= EDGE || dl >= EDGE) {
                refractUntil = t + REFRACTORY_NS
                disturb(t, if (-dz >= EDGE) "down-edge (screen side)" else "lateral edge")
            }
        }

        if (tap1 != 0L && tap2 == 0L && t - tap1 > MAX_GAP_NS) tap1 = 0L
        if (tap2 != 0L && t - tap2 >= POST_QUIET_NS) {
            tap1 = 0L
            tap2 = 0L
            lastEvent = t
            cooldownUntil = t + COOLDOWN_NS
            log?.invoke("FIRE")
            return DOUBLE
        }
        return NONE
    }

    private fun classify() {
        var peak = candZ[0]
        var peakN = 0
        for (i in 1 until SHAPE_SAMPLES) {
            if (candZ[i] > peak) { peak = candZ[i]; peakN = i }
        }
        val after = if (peakN + 3 < SHAPE_SAMPLES) candZ[peakN + 3] else peak
        val reason = when {
            candT < cooldownUntil -> "cooldown"
            peakN > SHARP_SAMPLES -> "slow rise"
            peak < RISE_MIN -> "weak"
            peak > RISE_MAX -> "impact"
            candLat > LAT_RATIO * peak -> "lateral"
            after > DECAY * peak -> "slow decay"
            abs(gz) > FLAT_GZ -> "flat"
            else -> null
        }
        log?.invoke(
            "edge peak=%.1f at+%d decay=%.2f lat=%.2f gz=%.1f -> %s"
                .format(peak, peakN, after / peak, candLat / peak, gz, reason ?: "TAP")
        )
        if (reason != null) {
            disturb(candT, reason)
            return
        }
        onTap(candT)
    }

    private fun disturb(t: Long, why: String) {
        if (tap1 != 0L || tap2 != 0L) log?.invoke("cancel pair: $why")
        tap1 = 0L
        tap2 = 0L
        lastEvent = t
        lastDisturb = t
    }

    private fun onTap(t: Long) {
        val sinceEvent = t - lastEvent
        lastEvent = t
        if (tap2 != 0L) {
            log?.invoke("cancel pair: third tap")
            tap1 = 0L
            tap2 = 0L
            lastDisturb = t
            return
        }
        if (tap1 != 0L) {
            val gap = t - tap1
            if (gap in MIN_GAP_NS..MAX_GAP_NS && lastDisturb < tap1) {
                tap2 = t
                return
            }
            log?.invoke("cancel pair: gap ${gap / MS} ms")
            tap1 = 0L
        }
        tap1 = if (sinceEvent >= PRE_QUIET_NS) t else 0L
        if (tap1 == 0L) log?.invoke("tap not after quiet (${sinceEvent / MS} ms)")
    }
}
