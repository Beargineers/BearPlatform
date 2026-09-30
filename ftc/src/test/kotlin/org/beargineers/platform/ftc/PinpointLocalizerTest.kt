package org.beargineers.platform.ftc

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.DeviceStatus
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.Register
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple
import org.beargineers.platform.component.Robot
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.config.Config
import org.beargineers.platform.geometry.Pose
import org.beargineers.platform.localization.Localizer
import org.beargineers.platform.units.cm
import org.beargineers.platform.units.degrees
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A Pinpoint that reports what the test sets: positions in mm, headings in radians */
class FakePinpoint : GoBildaPinpointDriver(fake<I2cDeviceSynchSimple> { _, _ -> Unanswered }, true) {
    var x = 0.0
    var y = 0.0
    var heading = 0.0
    var velocityX = 0.0
    var velocityY = 0.0
    var turning = 0.0
    var reportedStatus = DeviceStatus.READY
    var firmware = 3
    val sent = mutableListOf<String>()

    override fun update() {}
    override fun getDeviceStatus() = reportedStatus
    override fun getDeviceVersion() = firmware
    override fun getPosX(unit: DistanceUnit) = x.also { check(unit == DistanceUnit.MM) }
    override fun getPosY(unit: DistanceUnit) = y.also { check(unit == DistanceUnit.MM) }
    override fun getHeading(unit: AngleUnit) = heading.also { check(unit == AngleUnit.RADIANS) }
    override fun getVelX(unit: DistanceUnit) = velocityX.also { check(unit == DistanceUnit.MM) }
    override fun getVelY(unit: DistanceUnit) = velocityY.also { check(unit == DistanceUnit.MM) }
    override fun getHeadingVelocity(unit: UnnormalizedAngleUnit) = turning.also { check(unit == UnnormalizedAngleUnit.RADIANS) }

    override fun setOffsets(xOffset: Double, yOffset: Double, unit: DistanceUnit) {
        sent += "offsets ${unit.toMm(xOffset)}, ${unit.toMm(yOffset)} mm"
    }

    override fun setEncoderResolution(ticksPerUnit: Double, unit: DistanceUnit) {
        sent += "resolution $ticksPerUnit per ${unit.name}"
    }

    override fun setEncoderDirections(xEncoder: EncoderDirection, yEncoder: EncoderDirection) {
        sent += "directions $xEncoder, $yEncoder"
    }

    override fun setYawScalar(yawOffset: Double) {
        sent += "yaw scalar $yawOffset"
    }

    override fun setBulkReadScope(vararg registers: Register) {
        sent += "bulk read ${registers.joinToString()}"
    }

    override fun resetPosAndIMU() {
        sent += "reset"
    }
}

private class LocalizedRobot(host: RobotHost) : Robot(host) {
    val localizer = PinpointLocalizer(this)
}

class PinpointLocalizerTest {
    private val host = TestFtcHost()
    private val pinpoint = FakePinpoint()
    private lateinit var robot: LocalizedRobot
    private val localizer get() = robot.localizer

    @BeforeTest
    fun setUp() {
        Config.load("localizer.forwardPodLeft = 3.6\nlocalizer.strafePodForward = -10.8")
        Localizer.lastPose = null
        host.hardware.add("pinpoint", pinpoint)
    }

    private fun start() {
        robot = LocalizedRobot(host)
        host.runner.init()
        tick()
    }

    private fun tick() {
        host.time += 10.milliseconds
        host.runner.tick()
    }

    private fun assertPose(expected: Pose, actual: Pose = localizer.pose) {
        val close = abs(expected.x.inCm - actual.x.inCm) < 1e-6 && abs(expected.y.inCm - actual.y.inCm) < 1e-6 &&
            abs((expected.heading - actual.heading).inDegrees) < 1e-6
        assertTrue(close, "expected $expected but was $actual")
    }

    @Test
    fun setsUpThePinpointAtInit() {
        start()
        assertEquals(
            listOf(
                "offsets 36.0, -108.0 mm",
                "resolution 19.89436789 per MM",
                "directions FORWARD, FORWARD",
                "bulk read DEVICE_STATUS, X_POSITION, Y_POSITION, H_ORIENTATION, X_VELOCITY, Y_VELOCITY, H_VELOCITY",
                "reset",
            ),
            pinpoint.sent,
        )
    }

    @Test
    fun olderFirmwareReadsEverything() {
        pinpoint.firmware = 2
        start()
        assertTrue(pinpoint.sent.none { it.startsWith("bulk read") }, "${pinpoint.sent}")
    }

    @Test
    fun settingsChangedOnThePageGoToThePinpoint() {
        start()
        pinpoint.sent.clear()
        tick()
        assertEquals(emptyList(), pinpoint.sent, "unchanged settings aren't sent again")

        Config.load(
            "localizer.forwardPodLeft = -2\nlocalizer.strafePodForward = 5\nlocalizer.pods = S\n" +
                "localizer.strafePodDirection = R\nlocalizer.yawScalar = 1.0013"
        )
        tick()
        assertEquals(
            listOf(
                "offsets -20.0, 50.0 mm",
                "resolution 13.26291192 per MM",
                "directions FORWARD, REVERSED",
                "yaw scalar 1.0013",
            ),
            pinpoint.sent,
        )
    }

    @Test
    fun poseIsWhereItWasSetPlusWhatThePinpointMeasuredSince() {
        start()
        pinpoint.x = 500.0
        pinpoint.y = 200.0
        pinpoint.heading = Math.toRadians(30.0)
        tick()
        localizer.pose = Pose(100.cm, 50.cm, 90.degrees)
        tick()
        assertPose(Pose(100.cm, 50.cm, 90.degrees))

        // 10 cm forward and a 45° turn, as the Pinpoint sees it: it faced 30° where the field says 90°
        pinpoint.x += 100 * Math.cos(Math.toRadians(30.0))
        pinpoint.y += 100 * Math.sin(Math.toRadians(30.0))
        pinpoint.heading = Math.toRadians(75.0)
        tick()
        assertPose(Pose(100.cm, 60.cm, 135.degrees))
    }

    @Test
    fun velocityIsAlongTheField() {
        start()
        pinpoint.heading = Math.toRadians(30.0)
        tick()
        localizer.pose = Pose(0.cm, 0.cm, 90.degrees)
        pinpoint.velocityX = 100 * Math.cos(Math.toRadians(30.0))
        pinpoint.velocityY = 100 * Math.sin(Math.toRadians(30.0))
        pinpoint.turning = 1.0
        tick()
        assertEquals(0.0, localizer.velocity.x.inCmPerSecond, 1e-9)
        assertEquals(10.0, localizer.velocity.y.inCmPerSecond, 1e-9)
        assertEquals(1.0, localizer.velocity.turn.inRadiansPerSecond, 1e-9)
    }

    @Test
    fun poseHoldsWhileThePinpointCalibrates() {
        pinpoint.reportedStatus = DeviceStatus.CALIBRATING
        start()
        localizer.pose = Pose(30.cm, 40.cm, 0.degrees)
        pinpoint.x = 1000.0
        tick()
        assertPose(Pose(30.cm, 40.cm, 0.degrees))
        assertEquals(DeviceStatus.CALIBRATING, localizer.status)

        // Once ready, the pose set during calibration is where the Pinpoint's reading starts
        pinpoint.reportedStatus = DeviceStatus.READY
        tick()
        assertPose(Pose(30.cm, 40.cm, 0.degrees))
        pinpoint.x += 100.0
        tick()
        assertPose(Pose(40.cm, 40.cm, 0.degrees))
    }

    @Test
    fun faultsShowOnTheDriverStation() {
        pinpoint.reportedStatus = DeviceStatus.FAULT_X_POD_NOT_DETECTED
        start()
        val telemetry = host.runner.telemetry
        host.time += 100.milliseconds
        telemetry.startLoop()
        tick()
        assertTrue("Pinpoint: FAULT_X_POD_NOT_DETECTED" in telemetry.finishLoop()!!)
    }

    @Test
    fun poseCarriesOverToTheNextOpMode() {
        assertNull(Localizer.lastPose)
        start()
        localizer.pose = Pose(10.cm, 20.cm, 45.degrees)
        tick()
        host.runner.stop()
        assertEquals(Pose(10.cm, 20.cm, 45.degrees), Localizer.lastPose)

        // A new opmode: new robot, and the Pinpoint recalibrated to zero
        pinpoint.x = 0.0
        pinpoint.heading = 0.0
        val next = TestFtcHost().also { it.hardware.add("pinpoint", pinpoint) }
        val nextRobot = LocalizedRobot(next)
        next.runner.init()
        next.runner.tick()
        assertPose(Pose(10.cm, 20.cm, 45.degrees), nextRobot.localizer.pose)
    }
}
