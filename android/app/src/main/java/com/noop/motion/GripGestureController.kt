package com.noop.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phone approximation of Apple Watch–style hand-grip / clench controls.
 *
 * ## Limits (honest)
 * - Apple Watch uses wrist IMU + force / EMG-adjacent sensing. A phone has none of that.
 * - This listens for a short **squeeze-pulse**: a sharp accel magnitude spike while the device is
 *   otherwise relatively still (held in hand), optionally with a gyro twitch.
 * - It will never match Watch reliability. Prefer WHOOP strap **double-tap** for primary actions
 *   when a strap is bonded; phone grip is secondary / experimental.
 * - Does not fight system Back / nav gestures — only fires when the app is foreground and listening.
 *
 * Mapped actions (host decides):
 * - single pulse → secondary (e.g. refresh / dismiss toast)
 * - double pulse within [DOUBLE_WINDOW_MS] → primary (e.g. open New Workout / end-confirm)
 */
class GripGestureController(
    context: Context,
    private val onSingle: () -> Unit,
    private val onDouble: () -> Unit,
) : SensorEventListener {

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var enabled = false
    private var lastMag = 0f
    private var lastPulseMs = 0L
    private var pendingSingle = false
    private var pulseCount = 0
    private val recent = FloatArray(8)
    private var recentIdx = 0

    fun start() {
        if (enabled) return
        val manager = sm ?: return
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        enabled = true
        manager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!enabled) return
        enabled = false
        sm?.unregisterListener(this)
        pendingSingle = false
        pulseCount = 0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values.getOrNull(0) ?: return
        val y = event.values.getOrNull(1) ?: return
        val z = event.values.getOrNull(2) ?: return
        val mag = sqrt(x * x + y * y + z * z)
        recent[recentIdx % recent.size] = mag
        recentIdx++
        val baseline = recent.average().toFloat()
        val delta = abs(mag - lastMag)
        lastMag = mag
        // Spike vs recent baseline while not in free-fall / hard shake.
        val now = System.currentTimeMillis()
        if (delta < SPIKE_DELTA || mag < 7f || mag > 22f) return
        if (abs(mag - baseline) < SPIKE_VS_BASE) return
        if (now - lastPulseMs < REFRACTORY_MS) return
        lastPulseMs = now
        pulseCount++
        if (pulseCount >= 2) {
            pulseCount = 0
            pendingSingle = false
            onDouble()
            return
        }
        pendingSingle = true
        // Defer single so a second pulse can promote to double.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (pendingSingle && pulseCount == 1) {
                pendingSingle = false
                pulseCount = 0
                onSingle()
            } else if (pendingSingle) {
                pendingSingle = false
            }
        }, DOUBLE_WINDOW_MS)
    }

    companion object {
        const val DOUBLE_WINDOW_MS = 420L
        private const val REFRACTORY_MS = 180L
        private const val SPIKE_DELTA = 2.8f
        private const val SPIKE_VS_BASE = 2.2f
    }
}
