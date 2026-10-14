package nl.beargineers.platform.control

import kotlin.math.sign

/**
 * The power a mechanism needs to move at a velocity, from how it behaves: [kS] to overcome friction and start moving,
 * plus [kV] per unit of velocity, plus [kA] per unit of acceleration. Measure them once and a [Pid] only has to correct
 * what is left, which makes it much easier to tune. In config: `flywheel.feedforward = kS=0.04, kV=0.00017`.
 *
 * Velocity and acceleration can be in any unit, like rpm; the constants are per that unit.
 */
data class Feedforward(val kS: Double = 0.0, val kV: Double = 0.0, val kA: Double = 0.0) {
    fun power(velocity: Double, acceleration: Double = 0.0): Double {
        val direction = if (velocity != 0.0) sign(velocity) else sign(acceleration)
        return kS * direction + kV * velocity + kA * acceleration
    }
}
