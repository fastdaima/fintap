package com.fintap

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "fintap.tap"

// Accessibility service, not a foreground service, because:
//  - Android blocks background activity starts; system-bound accessibility services are exempt.
//  - No permanent notification, and the system keeps it alive.
// It subscribes to zero accessibility events (see res/xml/tap_service.xml); it exists only to
// own the accelerometer listener.
//
// Battery: the sensor is registered only while screen is on AND unlocked. Screen off -> unregistered,
// the IMU's own low-power path is untouched by us. While on, samples are batched in the hardware
// FIFO (BATCH_US) so the CPU wakes ~20x/s, not 400x/s.
class TapService : AccessibilityService(), SensorEventListener {
    companion object {
        const val SAMPLE_US = 2500   // 400 Hz. Taps are ~10-30 ms impulses; 200 Hz under-samples the peak.
        const val BATCH_US = 50_000  // hardware FIFO latency; adds <=50 ms to detection
        const val ACTION_REC_START = "com.fintap.REC_START"
        const val ACTION_REC_STOP = "com.fintap.REC_STOP"
        const val REC_MAX_SAMPLES = 400 * 300 // 5 min at 400 Hz
        const val RAW_SAMPLES = 1200          // ~2.6 s at 458 Hz: pre-quiet + both taps + post-quiet
        const val LOG_FLUSH_LINES = 500
    }

    private lateinit var sensors: SensorManager
    private var accel: Sensor? = null
    private var listening = false
    private val detector = TapDetector()

    private var rec: FloatArray? = null // t(s since rec start), x, y, z
    private var recCount = 0
    private var recT0 = 0L
    private var recName = "rec"

    // Raw ring of the last RAW_SAMPLES samples, dumped on FIRE so a misfire can be replayed as a test.
    private val rawT = LongArray(RAW_SAMPLES)
    private val rawXyz = FloatArray(RAW_SAMPLES * 3)
    private var rawHead = 0
    private var rawCount = 0

    // Detector verdicts buffered in memory; written on FIRE, screen-off, or LOG_FLUSH_LINES.
    // Edges are a few per second at most, but the disk is still touched only in bursts.
    private val pendingLog = StringBuilder()
    private var pendingLines = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopListening()
                Intent.ACTION_USER_PRESENT -> startListening()
                Intent.ACTION_SCREEN_ON -> if (!isLocked()) startListening()
            }
        }
    }

    // Shell-only (guarded by DUMP permission): adb shell am broadcast -a com.fintap.REC_START --es name x
    private val recReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_REC_START -> {
                    recName = intent.getStringExtra("name") ?: "rec"
                    rec = FloatArray(REC_MAX_SAMPLES * 4)
                    recCount = 0
                    recT0 = 0L
                    Log.i(TAG, "rec start $recName listening=$listening")
                }
                ACTION_REC_STOP -> writeRecording()
            }
        }
    }

    override fun onServiceConnected() {
        sensors = getSystemService(SensorManager::class.java)
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        detector.log = { Log.i(TAG, it); note(it) }

        val f = IntentFilter()
        f.addAction(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_SCREEN_ON)
        f.addAction(Intent.ACTION_USER_PRESENT)
        registerReceiver(screenReceiver, f)

        val rf = IntentFilter()
        rf.addAction(ACTION_REC_START)
        rf.addAction(ACTION_REC_STOP)
        registerReceiver(recReceiver, rf, android.Manifest.permission.DUMP, null, RECEIVER_EXPORTED)

        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive && !isLocked()) startListening()
        Log.i(TAG, "connected accel=${accel?.name} fifoMax=${accel?.fifoMaxEventCount}")
    }

    override fun onDestroy() {
        stopListening()
        unregisterReceiver(screenReceiver)
        unregisterReceiver(recReceiver)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun startListening() {
        val s = accel ?: return
        if (listening) return
        detector.reset()
        listening = sensors.registerListener(this, s, SAMPLE_US, BATCH_US)
        Log.i(TAG, "listen=$listening")
    }

    private fun stopListening() {
        if (!listening) return
        sensors.unregisterListener(this)
        listening = false
        Log.i(TAG, "stopped")
        flushLog()
    }

    private fun note(line: String) {
        pendingLog.append(DecisionLog.stamp()).append(' ').append(line).append('\n')
        pendingLines++
        if (pendingLines >= LOG_FLUSH_LINES) flushLog()
    }

    private fun flushLog() {
        if (pendingLines == 0) return
        DecisionLog.append(this, pendingLog.toString())
        pendingLog.setLength(0)
        pendingLines = 0
    }

    // Writes the raw ring, oldest first, as t(s from first), x, y, z float32 LE — replayable by TapDetectorTest.
    private fun writeSnapshot(id: Long) {
        val n = rawCount
        val start = (rawHead - n + RAW_SAMPLES) % RAW_SAMPLES
        val t0 = rawT[start]
        val buf = ByteBuffer.allocate(n * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) {
            val i = (start + k) % RAW_SAMPLES
            buf.putFloat((rawT[i] - t0) * 1e-9f)
            buf.putFloat(rawXyz[i * 3])
            buf.putFloat(rawXyz[i * 3 + 1])
            buf.putFloat(rawXyz[i * 3 + 2])
        }
        FileOutputStream(File(DecisionLog.dir(this), "fire-$id.bin")).use { it.write(buf.array()) }
        DecisionLog.pruneSnapshots(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]

        val r = rec
        if (r != null && recCount < REC_MAX_SAMPLES) {
            if (recT0 == 0L) recT0 = e.timestamp
            val i = recCount * 4
            r[i] = (e.timestamp - recT0) * 1e-9f
            r[i + 1] = x
            r[i + 2] = y
            r[i + 3] = z
            recCount++
        }

        rawT[rawHead] = e.timestamp
        rawXyz[rawHead * 3] = x
        rawXyz[rawHead * 3 + 1] = y
        rawXyz[rawHead * 3 + 2] = z
        rawHead = (rawHead + 1) % RAW_SAMPLES
        if (rawCount < RAW_SAMPLES) rawCount++

        if (detector.feed(e.timestamp, x, y, z) == TapDetector.DOUBLE) {
            // Popup first: the user is waiting. Snapshot + log are ~20 KB of writes, done right after.
            val id = System.currentTimeMillis()
            val i = Intent(this, QuickAddActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.putExtra(QuickAddActivity.EXTRA_FIRE_ID, id)
            startActivity(i)
            writeSnapshot(id)
            note("FIRE id=$id snapshot=fire-$id.bin")
            flushLog()
        }
    }

    private fun writeRecording() {
        val r = rec ?: return
        rec = null
        val dir = getExternalFilesDir(null)
        check(dir != null) { "no external files dir" }
        val out = File(dir, "$recName.bin")
        val buf = ByteBuffer.allocate(recCount * 16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until recCount * 4) buf.putFloat(r[i])
        FileOutputStream(out).use { it.write(buf.array()) }
        Log.i(TAG, "rec wrote ${out.path} samples=$recCount")
    }
}
