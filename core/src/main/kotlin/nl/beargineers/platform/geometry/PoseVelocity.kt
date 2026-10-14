package nl.beargineers.platform.geometry

import nl.beargineers.platform.units.AngularVelocity
import nl.beargineers.platform.units.LinearVelocity
import nl.beargineers.platform.units.cm
import nl.beargineers.platform.units.degrees
import nl.beargineers.platform.units.perSecond

/** How fast the robot moves along field [x] and [y], and how fast it [turn]s (counterclockwise is positive) */
data class PoseVelocity(val x: LinearVelocity, val y: LinearVelocity, val turn: AngularVelocity) {
    /** Speed over the ground, regardless of direction */
    val speed: LinearVelocity get() = kotlin.math.hypot(x.inCmPerSecond, y.inCmPerSecond).cm.perSecond

    override fun toString(): String = "($x, $y) $turn"

    companion object {
        val ZERO: PoseVelocity = PoseVelocity(0.cm.perSecond, 0.cm.perSecond, 0.degrees.perSecond)
    }
}
