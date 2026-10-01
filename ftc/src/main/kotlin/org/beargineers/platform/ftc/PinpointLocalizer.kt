package org.beargineers.platform.ftc

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.DeviceStatus
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.Register
import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config
import org.beargineers.platform.config.optionalConfig
import org.beargineers.platform.geometry.Point
import org.beargineers.platform.geometry.Pose
import org.beargineers.platform.geometry.PoseVelocity
import org.beargineers.platform.localization.Localizer
import org.beargineers.platform.units.Distance
import org.beargineers.platform.units.mm
import org.beargineers.platform.units.perSecond
import org.beargineers.platform.units.radians
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Localization by goBILDA's Pinpoint odometry computer, with two odometry pods: one measuring forward motion and one
 * sideways. The Pinpoint is named "pinpoint" in the robot configuration. Settings start with `localizer.`
 *
 * INIT calibrates the Pinpoint's gyro, which takes a quarter second with the robot standing still. Until then, and
 * while the Pinpoint reports a problem, the pose stays where it was and the Driver Station shows the problem.
 */
class PinpointLocalizer(parent: Component, name: String = "localizer", device: String = "pinpoint") :
    Localizer(parent, name) {

    /** How far left of the robot's center the pod measuring forward motion is; negative when right of it */
    val forwardPodLeft by config<Distance>()

    /** How far forward of the robot's center the pod measuring sideways motion is; negative when behind it */
    val strafePodForward by config<Distance>()

    /** Which goBILDA pods: FOUR_BAR or SWINGARM */
    val pods by config(Pods.FOUR_BAR)

    /** Encoder ticks per millimeter, for pods other than goBILDA's; overrides [pods] */
    val ticksPerMm by optionalConfig<Double>()

    /** Set to REVERSED when pushing the robot forward makes x go down */
    val forwardPodDirection by config(EncoderDirection.FORWARD)

    /** Set to REVERSED when pushing the robot to its left makes y go down */
    val strafePodDirection by config(EncoderDirection.FORWARD)

    /**
     * Corrects the gyro when turning the robot a number of full turns reads a little more or less. The new value is
     * the old one times the real turns over the turns read; the Pinpoint's own is around 1.002.
     */
    val yawScalar by optionalConfig<Double>()

    /** The SDK driver, for anything this class doesn't cover */
    val pinpoint: GoBildaPinpointDriver by hardware(device)

    enum class Pods { FOUR_BAR, SWINGARM }

    /**
     * How often the Pinpoint is read, on the robot's device reader thread. Each read takes about 5.5 ms of the hubs'
     * connection, which motors and other sensors share: reading more often slows them, reading less often makes the
     * pose older when the robot sees it. The Pinpoint keeps track much faster either way. On a mecanum robot, path
     * following was as fast and as accurate at 10, 15 and 20 ms, with the connection 55, 37 and 28% busy reading.
     */
    val readInterval by config(15.milliseconds)

    /** What the Pinpoint reported last; the pose only moves while it is READY */
    var status: DeviceStatus = DeviceStatus.NOT_READY
        private set

    /** One reading of the Pinpoint as it counts: mm and radians, from where it calibrated */
    private class Reading(
        val status: DeviceStatus,
        val x: Double, val y: Double, val heading: Double,
        val velocityX: Double, val velocityY: Double, val turning: Double,
    )

    // The newest reading, from the reader thread, and the one the pose was last worked out from
    @Volatile
    private var latest: Reading? = null
    private var used: Reading? = null
    private var readInLoop = true

    private var current = Pose.ZERO
    private var currentVelocity = PoseVelocity.ZERO

    // The Pinpoint counts from where it calibrated. This turns its pose into the field pose, so setting the pose
    // needs no write to the Pinpoint, which would race with its readings.
    private var fieldFromPinpoint = Pose.ZERO
    private var requested: Pose? = null
    private var badReads = 0

    // What the Pinpoint was last sent; NaN and null for nothing yet
    private var appliedForwardPodLeftMm = Double.NaN
    private var appliedStrafePodForwardMm = Double.NaN
    private var appliedTicksPerMm = Double.NaN
    private var appliedForwardDirection: EncoderDirection? = null
    private var appliedStrafeDirection: EncoderDirection? = null
    private var appliedYawScalar: Double? = null

    override var pose: Pose
        get() = current
        set(value) {
            current = value
            requested = value
        }

    override val velocity: PoseVelocity get() = currentVelocity

    override fun init() {
        applySettings()
        // Older firmware reads all registers; version 3 reads only these, which is quicker
        if (pinpoint.deviceVersion >= 3) {
            pinpoint.setBulkReadScope(
                Register.DEVICE_STATUS,
                Register.X_POSITION, Register.Y_POSITION, Register.H_ORIENTATION,
                Register.X_VELOCITY, Register.Y_VELOCITY, Register.H_VELOCITY,
            )
        }
        pinpoint.resetPosAndIMU()
        pose = lastPose ?: Pose.ZERO

        // On the robot, the Pinpoint is read on the device reader's thread, so the loop doesn't wait for it
        (robot.host as? FtcHost)?.deviceReader?.let { reader ->
            reader.add("pinpoint", { readInterval }) { latest = readDevice() }
            readInLoop = false
        }
    }

    override fun update() {
        if (readInLoop) latest = readDevice()
        val reading = latest
        if (reading != null && reading !== used) {
            used = reading
            use(reading)
        }
        if (status != DeviceStatus.READY && status != DeviceStatus.FAULT_BAD_READ) show("Pinpoint", status)

        show("Pose") { current }
        graph("x cm", current.x.inCm)
        graph("y cm", current.y.inCm)
        graph("heading deg", current.heading.inDegrees)
        graph("speed cm/s", currentVelocity.speed.inCmPerSecond)
        graph("turn deg/s", currentVelocity.turn.inDegreesPerSecond)
    }

    override fun stop() {
        lastPose = current
        if (badReads > 0) log("$badReads bad reads from the Pinpoint")
    }

    /** Sends changed settings and reads the Pinpoint: on the device reader's thread, or in the loop where there is none */
    private fun readDevice(): Reading {
        applySettings()
        pinpoint.update()
        return Reading(
            pinpoint.deviceStatus,
            pinpoint.getPosX(DistanceUnit.MM),
            pinpoint.getPosY(DistanceUnit.MM),
            pinpoint.getHeading(AngleUnit.RADIANS),
            pinpoint.getVelX(DistanceUnit.MM),
            pinpoint.getVelY(DistanceUnit.MM),
            pinpoint.getHeadingVelocity(UnnormalizedAngleUnit.RADIANS),
        )
    }

    /** Works out the field pose from a new [reading], on the loop's thread */
    private fun use(reading: Reading) {
        val previous = status
        status = reading.status
        if (status != previous && status != DeviceStatus.READY && status != DeviceStatus.FAULT_BAD_READ) {
            log("Pinpoint reports $status")
        }
        // A bad read keeps the previous reading, which is still good to use
        if (status == DeviceStatus.FAULT_BAD_READ) badReads++
        if (status != DeviceStatus.READY && status != DeviceStatus.FAULT_BAD_READ) return

        val raw = Pose(reading.x.mm, reading.y.mm, reading.heading.radians)
        requested?.let { wanted ->
            val turn = wanted.heading - raw.heading
            fieldFromPinpoint = Pose(wanted.point - raw.point.rotateBy(turn), turn)
            requested = null
        }

        val turn = fieldFromPinpoint.heading.toAngle()
        current = Pose(fieldFromPinpoint.point + raw.point.rotateBy(turn), raw.heading + turn)

        val moving = Point(reading.velocityX.mm, reading.velocityY.mm).rotateBy(turn)
        currentVelocity = PoseVelocity(moving.x.perSecond, moving.y.perSecond, reading.turning.radians.perSecond)
    }

    /**
     * Sends settings to the Pinpoint when they change, so they can be tuned on the settings page. Runs before every
     * read, so it compares plain values and creates nothing.
     */
    private fun applySettings() {
        val forwardPodLeftMm = forwardPodLeft.inMm
        val strafePodForwardMm = strafePodForward.inMm
        if (forwardPodLeftMm != appliedForwardPodLeftMm || strafePodForwardMm != appliedStrafePodForwardMm) {
            pinpoint.setOffsets(forwardPodLeftMm, strafePodForwardMm, DistanceUnit.MM)
            appliedForwardPodLeftMm = forwardPodLeftMm
            appliedStrafePodForwardMm = strafePodForwardMm
        }

        val resolution = ticksPerMm ?: when (pods) {
            Pods.FOUR_BAR -> FOUR_BAR_TICKS_PER_MM
            Pods.SWINGARM -> SWINGARM_TICKS_PER_MM
        }
        if (resolution != appliedTicksPerMm) {
            pinpoint.setEncoderResolution(resolution, DistanceUnit.MM)
            appliedTicksPerMm = resolution
        }

        val forwardDirection = forwardPodDirection
        val strafeDirection = strafePodDirection
        if (forwardDirection != appliedForwardDirection || strafeDirection != appliedStrafeDirection) {
            pinpoint.setEncoderDirections(forwardDirection, strafeDirection)
            appliedForwardDirection = forwardDirection
            appliedStrafeDirection = strafeDirection
        }

        val yaw = yawScalar
        if (yaw != null && yaw != appliedYawScalar) {
            pinpoint.setYawScalar(yaw)
            appliedYawScalar = yaw
        }
    }

    private companion object {
        // From goBILDA's driver
        const val FOUR_BAR_TICKS_PER_MM = 19.89436789
        const val SWINGARM_TICKS_PER_MM = 13.26291192
    }
}
