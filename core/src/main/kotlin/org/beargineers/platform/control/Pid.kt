package org.beargineers.platform.control

import kotlin.math.abs
import kotlin.math.sign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/** Gains of a [Pid] controller. In config: `turret.gains = p=0.02, i=0, d=0.001`, or just `0.02, 0, 0.001`. */
data class PidGains(val p: Double, val i: Double = 0.0, val d: Double = 0.0)

/**
 * A PID controller: turns an error, how far something is from its target, into an output, usually motor power.
 * Call [update] once per loop with `target - measured`, in any unit, and the gains are per that unit:
 *
 * ```
 * val gains by config(PidGains(p = 0.01, d = 0.0005))
 * private val pid = Pid { gains }
 * ...
 * motor.power = pid.update((target - motor.position).inDegrees)
 * ```
 *
 * The gains are read on every update, so config edits take effect right away; changing them clears the integral.
 * After a pause in updates longer than [maxStep], the controller starts over rather than reacting to the gap.
 */
class Pid(
    /** The output stays within ±this */
    private val outputLimit: Double = 1.0,
    /** The integral term alone never gives more than ±this */
    private val integralLimit: Double = outputLimit,
    /** The integral only builds up while the error is smaller than this; farther away, it is cleared */
    private val integralZone: Double = Double.POSITIVE_INFINITY,
    /**
     * Smooths the derivative, which otherwise jumps with every encoder tick: roughly how long a change takes to show
     * fully. Zero turns smoothing off.
     */
    private val derivativeSmoothing: Duration = Duration.ZERO,
    private val maxStep: Duration = 250.milliseconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val gains: () -> PidGains,
) {
    private val start = timeSource.markNow()
    private var lastGains: PidGains? = null
    private var lastUpdate: Duration? = null
    private var lastError = 0.0
    private var integral = 0.0
    private var derivative = 0.0

    /** Parts of the last output, for telemetry and graphs while tuning */
    var proportionalTerm = 0.0
        private set
    var integralTerm = 0.0
        private set
    var derivativeTerm = 0.0
        private set

    /** The output for [error], which is `target - measured` */
    fun update(error: Double): Double {
        val gains = gains()
        if (gains != lastGains) {
            reset()
            lastGains = gains
        }

        val now = start.elapsedNow()
        val step = lastUpdate?.let { now - it }
        lastUpdate = now

        if (step == null || step > maxStep || step <= Duration.ZERO) {
            // First update, or after a pause: nothing to take the derivative or integral over yet
            derivative = 0.0
            integral = 0.0
        } else {
            val dt = step.toDouble(DurationUnit.SECONDS)
            val rawDerivative = (error - lastError) / dt
            val tau = derivativeSmoothing.toDouble(DurationUnit.SECONDS)
            derivative += (rawDerivative - derivative) * (dt / (tau + dt))

            if (gains.i == 0.0 || abs(error) > integralZone) {
                integral = 0.0
            } else {
                val grown = integral + error * dt
                val output = gains.p * error + gains.i * grown + gains.d * derivative
                // Anti-windup: while the output is already at its limit, pushing the same way, don't grow the integral
                if (abs(output) < outputLimit || sign(output) != sign(error)) integral = grown
                val integralBound = integralLimit / abs(gains.i)
                integral = integral.coerceIn(-integralBound, integralBound)
            }
        }
        lastError = error

        proportionalTerm = gains.p * error
        integralTerm = gains.i * integral
        derivativeTerm = gains.d * derivative
        return (proportionalTerm + integralTerm + derivativeTerm).coerceIn(-outputLimit, outputLimit)
    }

    /** Forgets the past: the next [update] starts over, like the first one */
    fun reset() {
        lastUpdate = null
        integral = 0.0
        derivative = 0.0
        proportionalTerm = 0.0
        integralTerm = 0.0
        derivativeTerm = 0.0
    }
}
