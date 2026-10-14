package nl.beargineers.platform.drive

import kotlin.math.abs

/**
 * Powers of a mecanum drive's four wheels, from -1.0 to 1.0. The wheels are mounted as their kit shows, like
 * goBILDA's Strafer: to move right, the left front and right back wheels spin forward and the other two backward.
 */
data class MecanumPowers(val leftFront: Double, val rightFront: Double, val leftBack: Double, val rightBack: Double) {
    companion object {
        /**
         * Powers that move the robot [forward], sideways to the [right] and [turn] it counterclockwise, each from
         * -1.0 to 1.0. When a wheel would need more than full power, all are scaled down together, so the robot
         * still moves the same way, only slower.
         */
        fun of(forward: Double, right: Double, turn: Double): MecanumPowers {
            val leftFront = forward + right - turn
            val rightFront = forward - right + turn
            val leftBack = forward - right - turn
            val rightBack = forward + right + turn
            val scale = maxOf(1.0, maxOf(abs(leftFront), abs(rightFront), abs(leftBack), abs(rightBack)))
            return MecanumPowers(leftFront / scale, rightFront / scale, leftBack / scale, rightBack / scale)
        }
    }
}
