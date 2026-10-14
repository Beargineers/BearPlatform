package nl.beargineers.platform.drive

import nl.beargineers.platform.component.Component

/**
 * What moves the robot around. Drivetrains come in two kinds, which take different commands and suit different
 * teleop controls, so each kind has its own class:
 *
 * - [OmniDrivetrain] moves in any direction while turning, like mecanum, X and swerve drives. Path following and
 *   field-centric driving need one.
 * - [TankDrivetrain] drives forward and back and turns, with the wheels of each side turning together, like tank and
 *   six-wheel drives.
 *
 * Actions that drive the robot take the drivetrain over (see [takeOver]), and teleop driving leaves them alone until a
 * stick is pushed far.
 */
abstract class Drivetrain(parent: Component, name: String) : Component(parent, name) {
    /** Stops the wheels */
    abstract fun stopMoving()

    /**
     * For teleop driving, called every loop: whether an action drives and the sticks should leave it alone. They do
     * until [push], how far the furthest pushed of them is, goes past [takeOverAbove]: then the action stops, and the
     * sticks drive. A thumb resting on a stick doesn't stop it.
     */
    protected fun leftToAction(push: Double, takeOverAbove: Double): Boolean {
        if (!isBusy) return false
        if (push <= takeOverAbove) return true
        cancelAction()
        return false
    }
}
