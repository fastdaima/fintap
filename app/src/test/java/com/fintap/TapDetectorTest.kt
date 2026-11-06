package com.fintap

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

// Synthetic traces at 458 Hz shaped like the recorded taps: phone tilted in hand (gravity on y/z),
// small tremor, tap = 1-sample jump to peak, fast decay, then a slow recoil lobe.
class TapDetectorTest {
    private val hz = 458f
    private val dtNs = (1e9 / hz).toLong()

    private class Trace(val n: Int) {
        val x = FloatArray(n)
        val y = FloatArray(n) { 7.0f }
        val z = FloatArray(n) { 6.5f }
    }

    private fun trace(seconds: Float): Trace {
        val t = Trace((seconds * hz).toInt())
        var seed = 12345L
        for (i in 0 until t.n) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val r = ((seed ushr 33) % 1000).toFloat() / 1000f - 0.5f
            t.x[i] += r * 0.2f
            t.z[i] += r * 0.3f
        }
        return t
    }

    private fun addTap(t: Trace, atS: Float, amp: Float, sign: Int = 1, lateral: Float = 0.3f, decay: Float = 0.6f) {
        val i0 = (atS * hz).toInt()
        t.z[i0] += sign * amp * 0.3f
        t.x[i0] += amp * lateral * 0.3f
        var v = amp
        for (k in 1 until 20) {
            t.z[i0 + k] += sign * v
            t.x[i0 + k] += v * lateral
            v *= decay
        }
        // Slow recoil: ~-0.25 amp over 30 ms. Small per-sample change, so it must not count as an edge.
        for (k in 20 until 34) t.z[i0 + k] -= sign * amp * 0.25f * kotlin.math.sin(PI.toFloat() * (k - 20) / 14f)
    }

    private fun run(t: Trace): Int {
        val d = TapDetector()
        var fires = 0
        for (i in 0 until t.n) {
            if (d.feed(1_000_000_000L + i * dtNs, t.x[i], t.y[i], t.z[i]) == TapDetector.DOUBLE) fires++
        }
        return fires
    }

    @Test fun backDoubleTapFires() {
        val t = trace(2f)
        addTap(t, 0.5f, 18f)
        addTap(t, 0.76f, 20f)
        assertEquals(1, run(t))
    }

    @Test fun singleTapDoesNot() {
        val t = trace(2f)
        addTap(t, 0.5f, 18f)
        assertEquals(0, run(t))
    }

    @Test fun tripleTapDoesNot() {
        val t = trace(2f)
        addTap(t, 0.5f, 18f)
        addTap(t, 0.76f, 18f)
        addTap(t, 1.02f, 18f)
        assertEquals(0, run(t))
    }

    @Test fun weakThirdTapStillCancels() {
        val t = trace(2f)
        addTap(t, 0.5f, 18f)
        addTap(t, 0.76f, 18f)
        addTap(t, 1.02f, 6f)
        assertEquals(0, run(t))
    }

    @Test fun screenDoubleTapDoesNot() {
        val t = trace(2f)
        addTap(t, 0.5f, 18f, sign = -1)
        addTap(t, 0.76f, 18f, sign = -1)
        assertEquals(0, run(t))
    }

    @Test fun lateralKnockDoesNot() {
        val t = trace(2f)
        addTap(t, 0.5f, 14f, lateral = 0.9f)
        addTap(t, 0.76f, 14f, lateral = 0.9f)
        assertEquals(0, run(t))
    }

    @Test fun slowDecayBumpDoesNot() {
        val t = trace(2f)
        addTap(t, 0.5f, 16f, decay = 0.9f)
        addTap(t, 0.76f, 16f, decay = 0.9f)
        assertEquals(0, run(t))
    }

    @Test fun gapTooShortOrLongDoesNot() {
        val a = trace(2f); addTap(a, 0.5f, 18f); addTap(a, 0.62f, 18f)
        val b = trace(3f); addTap(b, 0.5f, 18f); addTap(b, 1.1f, 18f)
        assertEquals(0, run(a))
        assertEquals(0, run(b))
    }

    @Test fun noQuietBeforeDoesNot() {
        val t = trace(2f)
        addTap(t, 0.4f, 8f, sign = -1) // screen touch 100 ms before
        addTap(t, 0.5f, 18f)
        addTap(t, 0.76f, 18f)
        assertEquals(0, run(t))
    }

    @Test fun flatOnTableDoesNot() {
        val t = trace(2f)
        for (i in 0 until t.n) { t.y[i] -= 7.0f; t.z[i] += 3.3f } // gravity all on z
        addTap(t, 0.5f, 18f)
        addTap(t, 0.76f, 18f)
        assertEquals(0, run(t))
    }

    // Replays real recordings: FINTAP_REC_DIR=/path ./gradlew testDebugUnitTest
    // File naming is the label: pos<N>_*.bin has N real double-taps (may fire <= N), neg_*.bin must never fire.
    @Test fun replayRecordings() {
        val dirName = System.getenv("FINTAP_REC_DIR")
        assumeTrue(dirName != null)
        val files = File(dirName!!).listFiles { f -> f.name.endsWith(".bin") }!!.sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        val failures = ArrayList<String>()
        for (f in files) {
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val n = b.remaining() / 16
            val d = TapDetector()
            val fireTimes = ArrayList<String>()
            for (i in 0 until n) {
                val ts = b.float
                val x = b.float
                val y = b.float
                val z = b.float
                if (d.feed(1_000_000_000L + (ts * 1e9).toLong(), x, y, z) == TapDetector.DOUBLE) {
                    fireTimes.add("%.2fs".format(ts))
                }
            }
            val want = when {
                f.name.startsWith("neg") -> 0
                f.name.startsWith("pos") -> f.name.removePrefix("pos").takeWhile { it.isDigit() }.toInt()
                else -> -1
            }
            val line = "${f.name}: samples=$n fired=${fireTimes.size} labeled=$want at $fireTimes"
            println(line)
            // Never-misfire is the hard gate: more fires than labeled pairs fails. Misses only print.
            if (want >= 0 && fireTimes.size > want) failures.add(line)
        }
        assertEquals("replay mismatches:\n" + failures.joinToString("\n"), 0, failures.size)
    }
}
