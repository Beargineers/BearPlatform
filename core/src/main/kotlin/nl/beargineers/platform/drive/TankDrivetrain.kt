package nl.beargineers.platform.drive

import nl.beargineers.platform.component.Component
import nl.beargineers.platform.input.Axis
import kotlin.math.abs

/**
 * A drivetrain whose wheels on each side turn together, like a tank or six-wheel drive: it drives forward and back and
 * turns, but can't move sideways.
 *
 * It takes powers relative to the robot, each from -1.0 to 1.0: forward, and turning counterclockwise, like angles
 * everywhere on the platform. The left wheels get `forward - turn` and the right ones `forward + turn`.
 */
abstract class TankDrivetrain(parent: Component, name: String) : Drivetrain(parent, name) {
    /**
     * Drives with powers relative to the robot, each from -1.0 to 1.0: [forward], and [turn] counterclockwise. When
     * that needs more than full power from a side, both are scaled down together: the robot moves the same way, only
     * slower.
     */
    abstract fun drive(forward: Double = 0.0, turn: Double = 0.0)

    override fun stopMoving() = drive(0.0, 0.0)

    /**
     * Teleop driving with tank controls, called every loop: [left] drives the left wheels and [right] the right ones,
     * forward when pushed forward. `drive.driveWith(controller1.leftStick.y, controller1.rightStick.y)`
     *
     * While an action drives, like a turn to a heading, the sticks leave it alone until one is pushed further than
     * [takeOverAbove]: then the action stops and the sticks drive. A thumb resting on a stick doesn't stop it.
     */
    fun driveWith(left: Axis, right: Axis, takeOverAbove: Double = 0.5) {
        if (leftToAction(maxOf(abs(left.value), abs(right.value)), takeOverAbove)) return
        drive(forward = (left.value + right.value) / 2, turn = (right.value - left.value) / 2)
    }
}
