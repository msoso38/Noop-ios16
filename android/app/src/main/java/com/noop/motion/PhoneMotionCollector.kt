package com.noop.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Phone IMU capture during live workouts: accelerometer + gyroscope + magnetometer when present.
 *
 * WHOOP open BLE exposes ~1 Hz gravity (already banked). This collector fills the phone-side gap
 * for sport-ID features while a manual workout is active. Not a watch-grade force sensor.
 */
class PhoneMotionCollector(context: Context) : SensorEventListener {

    data class Sample(
        val tsMs: Long,
        val ax: Float? = null,
        val ay: Float? = null,
        val az: Float? = null,
        val gx: Float? = null,
        val gy: Float? = null,
        val gz: Float? = null,
        val mx: Float? = null,
        val my: Float? = null,
        val mz: Float? = null,
    )

    data class Features(
        val sampleCount: Int,
        val meanAccelMag: Double,
        val stdAccelMag: Double,
        val meanGyroMag: Double,
        val stdGyroMag: Double,
        val meanMagMag: Double,
        val hasGyro: Boolean,
        val hasMag: Boolean,
    )

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val samples = ArrayList<Sample>(2048)
    private var running = false

    // Latest partial axes (sensors fire independently).
    private var ax: Float? = null
    private var ay: Float? = null
    private var az: Float? = null
    private var gx: Float? = null
    private var gy: Float? = null
    private var gz: Float? = null
    private var mx: Float? = null
    private var my: Float? = null
    private var mz: Float? = null
    private var lastEmitMs = 0L

    fun start() {
        if (running) return
        val manager = sm ?: return
        samples.clear()
        running = true
        lastEmitMs = 0L
        val delay = SensorManager.SENSOR_DELAY_GAME
        manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            manager.registerListener(this, it, delay)
        }
        manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            manager.registerListener(this, it, delay)
        }
        manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            manager.registerListener(this, it, delay)
        }
    }

    fun stop(): Features {
        if (!running) return emptyFeatures()
        running = false
        sm?.unregisterListener(this)
        return summarize(samples.toList()).also { samples.clear() }
    }

    fun snapshotFeatures(): Features = summarize(samples.toList())

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values.getOrNull(0); ay = event.values.getOrNull(1); az = event.values.getOrNull(2)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values.getOrNull(0); gy = event.values.getOrNull(1); gz = event.values.getOrNull(2)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                mx = event.values.getOrNull(0); my = event.values.getOrNull(1); mz = event.values.getOrNull(2)
            }
            else -> return
        }
        val now = System.currentTimeMillis()
        // Downsample to ~20 Hz for durable feature stats (keeps memory bounded).
        if (now - lastEmitMs < 50L) return
        lastEmitMs = now
        if (samples.size >= 12_000) samples.removeAt(0)
        samples.add(
            Sample(
                tsMs = now,
                ax = ax, ay = ay, az = az,
                gx = gx, gy = gy, gz = gz,
                mx = mx, my = my, mz = mz,
            ),
        )
    }

    companion object {
        fun emptyFeatures() = Features(
            sampleCount = 0,
            meanAccelMag = 0.0,
            stdAccelMag = 0.0,
            meanGyroMag = 0.0,
            stdGyroMag = 0.0,
            meanMagMag = 0.0,
            hasGyro = false,
            hasMag = false,
        )

        fun summarize(rows: List<Sample>): Features {
            if (rows.isEmpty()) return emptyFeatures()
            val accel = rows.mapNotNull { s ->
                val x = s.ax ?: return@mapNotNull null
                val y = s.ay ?: return@mapNotNull null
                val z = s.az ?: return@mapNotNull null
                sqrt((x * x + y * y + z * z).toDouble())
            }
            val gyro = rows.mapNotNull { s ->
                val x = s.gx ?: return@mapNotNull null
                val y = s.gy ?: return@mapNotNull null
                val z = s.gz ?: return@mapNotNull null
                sqrt((x * x + y * y + z * z).toDouble())
            }
            val mag = rows.mapNotNull { s ->
                val x = s.mx ?: return@mapNotNull null
                val y = s.my ?: return@mapNotNull null
                val z = s.mz ?: return@mapNotNull null
                sqrt((x * x + y * y + z * z).toDouble())
            }
            fun mean(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sum() / v.size
            fun std(v: List<Double>): Double {
                if (v.size < 2) return 0.0
                val m = mean(v)
                return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
            }
            return Features(
                sampleCount = rows.size,
                meanAccelMag = mean(accel),
                stdAccelMag = std(accel),
                meanGyroMag = mean(gyro),
                stdGyroMag = std(gyro),
                meanMagMag = mean(mag),
                hasGyro = gyro.isNotEmpty(),
                hasMag = mag.isNotEmpty(),
            )
        }
    }
}
