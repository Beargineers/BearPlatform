package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.Servo
import org.beargineers.platform.component.Component
import org.beargineers.platform.component.Robot
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.config.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Shooter(parent: Component) : Component(parent) {
    val flywheel = Motor(this, "flywheel", direction = DcMotorSimple.Direction.REVERSE, brake = false)
    val turret = Motor(this, "turret", gearRatio = 2.0)
    val latch = PositionServo(this, "latch", device = "latchServo")
}

private class TestRobot(host: RobotHost) : Robot(host) {
    val shooter = Shooter(this)
}

private class RobotWithMissingMotor(host: RobotHost) : Robot(host) {
    val intake = Motor(this, "intake")
}

class HardwareTest {
    private val host = TestFtcHost()
    private val flywheel = FakeMotor()
    private val turret = FakeMotor()
    private val latch = FakeServo()

    @BeforeTest
    fun setUp() {
        Config.load("")
        host.hardware.add("flywheel", flywheel.device)
        host.hardware.add("turret", turret.device)
        host.hardware.add("latchServo", latch.device)
    }

    private fun startRobot(): TestRobot = TestRobot(host).also { host.runner.init() }

    @Test
    fun motorSettingsStartFromConstructorDefaults() {
        startRobot()
        assertEquals(DcMotorSimple.Direction.REVERSE, flywheel.direction)
        assertEquals(DcMotor.ZeroPowerBehavior.FLOAT, flywheel.zeroPowerBehavior)
        assertEquals(DcMotor.RunMode.RUN_WITHOUT_ENCODER, flywheel.mode)
        assertEquals(DcMotorSimple.Direction.FORWARD, turret.direction)
        assertEquals(DcMotor.ZeroPowerBehavior.BRAKE, turret.zeroPowerBehavior)
    }

    @Test
    fun motorSettingsFollowConfig() {
        startRobot()
        Config.load("shooter.flywheel.direction = F\nshooter.flywheel.brake = yes")
        host.runner.tick()
        assertEquals(DcMotorSimple.Direction.FORWARD, flywheel.direction)
        assertEquals(DcMotor.ZeroPowerBehavior.BRAKE, flywheel.zeroPowerBehavior)
    }

    @Test
    fun positionAndVelocityAreMeasuredAfterGearing() {
        val robot = startRobot()
        turret.position = 769 // two motor turns of 384.5 ticks
        turret.ticksPerSecond = 384.5
        assertEquals(360.0, robot.shooter.turret.position.inDegrees, 1e-9)
        assertEquals(180.0, robot.shooter.turret.velocity.inDegreesPerSecond, 1e-9)
        assertEquals(435.0 / 2, robot.shooter.turret.maxVelocity.inRpm, 1e-9)
    }

    @Test
    fun resetPositionMakesANewZero() {
        turret.position = 1000
        val robot = startRobot()
        assertEquals(0, robot.shooter.turret.ticks)
        turret.position = 1100
        robot.shooter.turret.resetPosition()
        turret.position = 1150
        assertEquals(50, robot.shooter.turret.ticks)
    }

    @Test
    fun powerIsClampedAndOnlySentWhenItChanges() {
        val motor = startRobot().shooter.flywheel
        val writesAfterInit = flywheel.powerWrites

        repeat(3) { motor.power = 0.5 }
        assertEquals(writesAfterInit + 1, flywheel.powerWrites)

        motor.power = 1.7
        assertEquals(1.0, flywheel.power)
        motor.power = 0.12345
        assertEquals(0.123, flywheel.power)
    }

    @Test
    fun stopTurnsMotorsOff() {
        startRobot().shooter.flywheel.power = 0.8
        host.runner.stop()
        assertEquals(0.0, flywheel.power)
    }

    @Test
    fun motorWithoutEncoderResolutionExplainsTheFix() {
        turret.motorType.ticksPerRev = 0.0
        val robot = startRobot()
        val thrown = assertFailsWith<IllegalStateException> { robot.shooter.turret.position }
        assertTrue("Pick its motor type in the robot configuration" in thrown.message!!, thrown.message)
    }

    @Test
    fun missingDeviceFailsAtInit() {
        RobotWithMissingMotor(host)
        val thrown = assertFailsWith<IllegalStateException> { host.runner.init() }
        assertEquals("intake: the robot configuration has no DcMotorEx named 'intake'", thrown.message)
    }

    @Test
    fun hardwareIsNotAvailableBeforeInit() {
        val robot = TestRobot(TestFtcHost(hardwareAvailable = false))
        val thrown = assertFailsWith<IllegalStateException> { robot.shooter.flywheel.power = 0.5 }
        assertEquals("shooter.flywheel: hardware is available from init() on, not in constructors", thrown.message)
    }

    @Test
    fun servoOnlyMovesWhenTold() {
        val servo = startRobot().shooter.latch
        assertEquals(0, latch.positionWrites)

        repeat(3) { servo.position = 0.3 }
        assertEquals(1, latch.positionWrites)
        servo.position = 1.5
        assertEquals(1.0, latch.position)
    }

    @Test
    fun servoDirectionFollowsConfig() {
        startRobot()
        assertEquals(Servo.Direction.FORWARD, latch.direction)
        Config.load("shooter.latch.direction = R")
        host.runner.tick()
        assertEquals(Servo.Direction.REVERSE, latch.direction)
    }
}
