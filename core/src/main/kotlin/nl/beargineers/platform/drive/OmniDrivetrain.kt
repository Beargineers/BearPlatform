package nl.beargineers.platform.drive

import nl.beargineers.platform.component.Component
import nl.beargineers.platform.input.Axis
import nl.beargineers.platform.input.Stick
import nl.beargineers.platform.units.Heading
import nl.beargineers.platform.units.cos
import nl.beargineers.platform.units.sin
import kotlin.math.abs

/**
 * A drivetrain that moves in any direction while turning, like a mecanum, X or swerve drive. Path following and
 * field-centric driving need one.
 *
 * It takes powers relative to the robot, each from -1.0 to 1.0: forward, sideways to the right, and turning
 * counterclockwise, like angles everywhere on the platform. A swerve drive would point its modules in [drive] and steer
 * them in `update()`.
 */
abstract class OmniDrivetrain(parent: Component, name: String) : Drivetrain(parent, name) {
    /**
     * Drives with powers relative to the robot, each from -1.0 to 1.0: [forward], sideways to the [right], and [turn]
     * counterclockwise. When that needs more than full power from a wheel, all powers are scaled down together:
     * the robot moves the same way, only slower.
     */
    abstract fun drive(forward: Double = 0.0, right: Double = 0.0, turn: Double = 0.0)

    override fun stopMoving() = drive(0.0, 0.0, 0.0)

    /**
     * Teleop driving, called every loop: [move] drives forward and sideways, and [turn] turns, clockwise when pushed
     * right. `drive.driveWith(controller1.leftStick, controller1.rightStick.x)`
     *
     * While an action drives, like driving to a target, the sticks leave it alone until one is pushed further than
     * [takeOverAbove]: then the action stops and the sticks drive. A thumb resting on a stick doesn't stop it.
     */
    fun driveWith(move: Stick, turn: Axis, takeOverAbove: Double = 0.5) {
        if (leftToAction(push(move, turn), takeOverAbove)) return
        drive(forward = move.y.value, right = move.x.value, turn = -turn.value)
    }

    /**
     * Field-centric teleop driving, called every loop: pushing [move] forward drives away from the drivers, whichever
     * way the robot faces, and [turn] turns as in the robot-centric [driveWith]. [heading] is the robot's, from its
     * localizer; [driversFace] is the way the drivers look, `field.driversFace(alliance)`. Actions stop as in the
     * robot-centric [driveWith].
     */
    fun driveWith(move: Stick, turn: Axis, heading: Heading, driversFace: Heading, takeOverAbove: Double = 0.5) {
        if (leftToAction(push(move, turn), takeOverAbove)) return
        // The drivers' forward, as an angle from the robot's forward
        val offset = driversFace - heading
        val c = cos(offset)
        val s = sin(offset)
        val away = move.y.value
        val toTheirRight = move.x.value
        drive(forward = away * c + toTheirRight * s, right = toTheirRight * c - away * s, turn = -turn.value)
    }

    private fun push(move: Stick, turn: Axis) = maxOf(abs(move.x.value), abs(move.y.value), abs(turn.value))
}
