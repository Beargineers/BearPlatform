package org.beargineers.platform.drive

import org.beargineers.platform.component.Component
import org.beargineers.platform.input.Axis
import org.beargineers.platform.input.Stick
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
        val forward = move.y.value
        val right = move.x.value
        val counterclockwise = -turn.value
        if (isBusy) {
            if (maxOf(abs(forward), abs(right), abs(counterclockwise)) <= takeOverAbove) return
            cancelAction()
        }
        drive(forward, right, counterclockwise)
    }
}
