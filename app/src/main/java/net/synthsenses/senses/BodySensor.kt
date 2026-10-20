package net.synthsenses.senses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Proprioception: how the body is moving, how it's held, and what fields it sits in.
 *
 * Everything here is a low-rate listener keeping a rolling window, so a sample
 * reflects the last few seconds rather than one instant. Power cost is close to
 * nothing compared to the camera — the step counter in particular is batched in
 * hardware and essentially free.
 *
 * Two sleeper senses worth knowing about:
 *
 *  magneticAnomaly — deviation of field magnitude from a slow-moving baseline.
 *  Earth's field is ~25-65 µT depending where you are; motors, speakers, laptops
 *  and large steel objects push it well outside that. A fridge door swinging past
 *  registers. It's a crude "something electrical or metal is close" sense.
 *
 *  pressureDeltaPerMin — absolute pressure is boring weather data, but the rate
 *  of change catches lifts, stairwells, and doors opening in a sealed room. A
 *  barometer is a surprisingly good door sensor.
 */
class BodySensor(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "BodySensor"
        private const val WINDOW = 64
        private const val MAG_BASELINE_ALPHA = 0.002f  // very slow — minutes, not seconds
    }

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ambient
    private var lux: Float? = null
    private var proximityCm: Float? = null
    private var proximityMax: Float = 5f

    // field
    private var magneticUt: Float? = null
    private var magneticBaseline: Float? = null

    // pressure
    private var pressureHpa: Float? = null
    private var pressurePrev: Float? = null
    private var pressurePrevAtMs = 0L
    private var pressureDelta: Float? = null

    // motion
    private val accelWindow = ArrayDeque<Float>()
    private val gyroWindow = ArrayDeque<Float>()
    private var gravity = FloatArray(3)
    private var haveGravity = false
    private var heading: Int? = null

    // steps
    private var stepsRaw: Long? = null
    private var stepsAtLastSample: Long? = null

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        // Driven off BODY_SENSORS so the inventory `status` reports cannot
        // drift from what is actually registered. The step counter also needs
        // ACTIVITY_RECOGNITION from API 29; without it the sensor is there and
        // simply never fires.
        for (spec in BODY_SENSORS) register(spec.type)

        sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            proximityMax = it.maximumRange.takeIf { m -> m > 0f } ?: 5f
        }

        val absent = missingSensors { sensors.getDefaultSensor(it)?.name }
        Log.i(TAG, if (absent.isEmpty()) "every sensor this app uses is present"
                   else "this phone has no ${absent.joinToString(", ")}")
    }

    /**
     * What this phone actually has, by the hardware's own names. Answers the
     * question a spec sheet only guesses at, and the `status` command is the
     * one channel that reaches a PC with no adb on it.
     */
    fun inventory(): JSONObject = inventoryJson { sensors.getDefaultSensor(it)?.name }

    private fun register(type: Int) {
        val s = sensors.getDefaultSensor(type)
        if (s == null) {
            Log.d(TAG, "no sensor of type $type on this device")
            return
        }
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() = sensors.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> lux = e.values[0]

            Sensor.TYPE_PROXIMITY -> proximityCm = e.values[0]

            Sensor.TYPE_PRESSURE -> {
                val p = e.values[0]
                val now = System.currentTimeMillis()
                val prev = pressurePrev
                if (prev == null) {
                    pressurePrev = p
                    pressurePrevAtMs = now
                } else if (now - pressurePrevAtMs >= 10_000L) {
                    val minutes = (now - pressurePrevAtMs) / 60_000f
                    if (minutes > 0f) pressureDelta = (p - prev) / minutes
                    pressurePrev = p
                    pressurePrevAtMs = now
                }
                pressureHpa = p
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                val mag = magnitude(e.values)
                magneticUt = mag
                val base = magneticBaseline
                magneticBaseline =
                    if (base == null) mag
                    else base + MAG_BASELINE_ALPHA * (mag - base)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val dev = abs(magnitude(e.values) - SensorManager.GRAVITY_EARTH)
                push(accelWindow, dev)
            }

            Sensor.TYPE_GYROSCOPE -> push(gyroWindow, magnitude(e.values))

            Sensor.TYPE_GRAVITY -> {
                gravity = e.values.copyOf()
                haveGravity = true
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val deg = Math.toDegrees(orientation[0].toDouble()).roundToInt()
                heading = ((deg % 360) + 360) % 360
            }

            Sensor.TYPE_STEP_COUNTER -> stepsRaw = e.values[0].toLong()
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit

    fun sample(): Body {
        val accelRms = rms(accelWindow)
        val gyroRms = rms(gyroWindow)

        val covered = proximityCm?.let { it < proximityMax * 0.5f }
        val posture = posture(covered)

        val steps = stepsRaw
        val delta = if (steps != null) {
            val prev = stepsAtLastSample
            stepsAtLastSample = steps
            if (prev == null) null else (steps - prev).coerceAtLeast(0L)
        } else null

        val anomaly = magneticUt?.let { now ->
            magneticBaseline?.let { base -> abs(now - base) }
        }

        return Body(
            motion = classifyMotion(accelRms, gyroRms),
            posture = posture,
            accelRms = accelRms,
            gyroRms = gyroRms,
            headingDeg = heading,
            steps = steps,
            stepsDelta = delta,
            lux = lux,
            covered = covered,
            magneticUt = magneticUt,
            magneticAnomaly = anomaly,
            pressureHpa = pressureHpa,
            pressureDeltaPerMin = pressureDelta
        )
    }

    /**
     * Thresholds are empirical and phone-dependent. Watch accel_rms and gyro_rms
     * in the percept stream while you actually do each activity and retune — these
     * came from reasoning about magnitudes, not from your phone in your pocket.
     *
     * Every value this returns is a member of `Vocabulary.MOTION`, and a new
     * one has to be appended there before it can be returned here. Consumers
     * that cannot carry strings send the index into that list instead.
     */
    private fun classifyMotion(accelRms: Float, gyroRms: Float): String = when {
        accelRms < 0.08f && gyroRms < 0.04f -> Vocabulary.STILL
        // rotation without much translation is a hand turning the phone over
        gyroRms > 0.5f && accelRms < 0.9f -> Vocabulary.HANDLED
        accelRms < 0.55f -> Vocabulary.HANDLED
        accelRms < 3.0f -> Vocabulary.WALKING
        // a vehicle is sustained acceleration with little rotation: a walk
        // swings the phone, a car does not
        gyroRms < 0.35f -> Vocabulary.VEHICLE
        else -> Vocabulary.WALKING
    }

    /** Likewise, always a member of `Vocabulary.POSTURE`. */
    private fun posture(covered: Boolean?): String {
        if (covered == true && (lux ?: 0f) < 5f) return Vocabulary.POCKET
        if (!haveGravity) return Vocabulary.POSTURE_UNKNOWN
        val gz = gravity[2]
        val g = SensorManager.GRAVITY_EARTH
        return when {
            gz > 0.80f * g -> Vocabulary.FACE_UP
            gz < -0.80f * g -> Vocabulary.FACE_DOWN
            abs(gz) < 0.35f * g -> Vocabulary.UPRIGHT
            else -> Vocabulary.TILTED
        }
    }

    private fun push(window: ArrayDeque<Float>, v: Float) {
        synchronized(window) {
            window.addLast(v)
            while (window.size > WINDOW) window.removeFirst()
        }
    }

    private fun rms(window: ArrayDeque<Float>): Float = synchronized(window) {
        if (window.isEmpty()) 0f
        else sqrt(window.sumOf { (it * it).toDouble() } / window.size).toFloat()
    }

    private fun magnitude(v: FloatArray): Float =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
