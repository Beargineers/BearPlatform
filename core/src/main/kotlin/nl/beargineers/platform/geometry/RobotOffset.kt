package nl.beargineers.platform.geometry

import nl.beargineers.platform.units.Angle
import nl.beargineers.platform.units.Distance
import nl.beargineers.platform.units.cm
import nl.beargineers.platform.units.degrees
import nl.beargineers.platform.units.hypot

/**
 * A move or a location in the robot's own frame: [forward] along its heading, [right] to its right side,
 * and [turn] counterclockwise.
 *
 * Use it for relative moves: `pose + RobotOffset(forward = 1.tiles, turn = 90.degrees)`, and for where things
 * are mounted on the robot: `(pose + RobotOffset(forward = 20.cm, right = -5.cm)).point` is where the camera is.
 */
data class RobotOffset(
    val forward: Distance = 0.cm,
    val right: Distance = 0.cm,
    val turn: Angle = 0.degrees,
) {
    /** Straight-line length of the move, ignoring [turn] */
    val length: Distance get() = hypot(forward, right)

    override fun toString(): String = "(forward $forward, right $right, turn $turn)"
}
