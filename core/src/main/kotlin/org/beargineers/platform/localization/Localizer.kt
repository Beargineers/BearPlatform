package org.beargineers.platform.localization

import org.beargineers.platform.component.Component
import org.beargineers.platform.geometry.Pose
import org.beargineers.platform.geometry.PoseVelocity

/**
 * Knows where the robot is on the field, like odometry pods with a Pinpoint. Create it first on the robot, so it
 * updates before everything that drives by it: `val localizer = PinpointLocalizer(this)`.
 *
 * The pose carries over from one opmode to the next: TeleOp starts where autonomous ended, unless it sets the pose.
 * An implementation starts from [lastPose] in `init()` and saves its pose there in `stop()`.
 */
abstract class Localizer(parent: Component, name: String = "localizer") : Component(parent, name) {
    /**
     * Where the robot's center is on the field, and which way it faces. Set it to tell the localizer where the robot
     * is, like the starting pose.
     */
    abstract var pose: Pose

    /** How fast the robot moves along field x and y, and turns */
    abstract val velocity: PoseVelocity

    companion object {
        /** The pose at the end of the last opmode, while the robot app runs; null after the app starts */
        @Volatile
        var lastPose: Pose? = null
    }
}
