package org.beargineers.platform.drive

import org.beargineers.platform.component.Component
import org.beargineers.platform.input.Axis
import org.beargineers.platform.input.Stick
import org.beargineers.platform.units.Heading
import org.beargineers.platform.units.cos
import org.beargineers.platform.units.sin
import kotlin.math.abs

/**
 * What moves the robot around, like a mecanum drive. Teleop driving and path following work through it, so they
 * don't depend on the kind of drivetrain.
 *
 * It takes powers relative to the robot, each from -1.0 to 1.0: forward, sideways to the right, and turning
 * counterclockwise, like angles everywhere on the platform.
 *
 * Another kind of drivetrain implements [drive]: a tank drive has [canStrafe] false and ignores `right`; a swerve
 * drive points its modules in [drive] and steers them in `update()`.
 */
abstract class Drivetrain(parent: Component, name: String) : Component(parent, name) {
    /** Whether it can move sideways without turning, like mecanum and swerve drives; tank drives can't */
    abstract val canStrafe: Boolean

    /**
     * Drives with powers relative to the robot, each from -1.0 to 1.0: [forward], sideways to the [right], and [turn]
     * counterclockwise. When that needs more than full power from a wheel, all powers are scaled down together:
     * the robot moves the same way, only slower.
     */
    abstract fun drive(forward: Double = 0.0, right: Double = 0.0, turn: Double = 0.0)

    fun stopMoving() = drive(0.0, 0.0, 0.0)

    /**
     * Teleop driving, called every loop: [move] drives forward and sideways, and [turn] turns, clockwise when pushed
     * right. `drive.driveWith(driver1.leftStick, driver1.rightStick.x)`
     *
     * While an action drives, like driving to a target, the sticks leave it alone until one is pushed further than
     * [takeOverAbove]: then the action stops and the sticks drive. A thumb resting on a stick doesn't stop it.
     */
    fun driveWith(move: Stick, turn: Axis, takeOverAbove: Double = 0.5) {
        if (leftToAction(move, turn, takeOverAbove)) return
        drive(forward = move.y.value, right = move.x.value, turn = -turn.value)
    }

    /**
     * Field-centric teleop driving, called every loop: pushing [move] forward drives away from the drivers, whichever
     * way the robot faces, and [turn] turns as in the robot-centric [driveWith]. [heading] is the robot's, from its
     * localizer; [driversFace] is the way the drivers look, `field.driversFace(alliance)`. Actions stop as in the
     * robot-centric [driveWith].
     */
    fun driveWith(move: Stick, turn: Axis, heading: Heading, driversFace: Heading, takeOverAbove: Double = 0.5) {
        if (leftToAction(move, turn, takeOverAbove)) return
        // The drivers' forward, as an angle from the robot's forward
        val offset = driversFace - heading
        val c = cos(offset)
        val s = sin(offset)
        val away = move.y.value
        val toTheirRight = move.x.value
        drive(forward = away * c + toTheirRight * s, right = toTheirRight * c - away * s, turn = -turn.value)
    }

    /** Whether an action drives and the sticks should leave it alone; stops the action when they shouldn't */
    private fun leftToAction(move: Stick, turn: Axis, takeOverAbove: Double): Boolean {
        if (!isBusy) return false
        if (maxOf(abs(move.x.value), abs(move.y.value), abs(turn.value)) <= takeOverAbove) return true
        cancelAction()
        return false
    }
}
