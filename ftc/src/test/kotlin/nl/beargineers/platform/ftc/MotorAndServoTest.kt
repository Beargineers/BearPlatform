package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.PwmControl
import nl.beargineers.platform.component.Robot
import nl.beargineers.platform.component.RobotHost
import nl.beargineers.platform.config.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private class TurretRobot(host: RobotHost) : Robot(host) {
    val turret = Motor(this, "turret", ticksPerTurn = 384.5, keepZero = true)
}

private class FlywheelRobot(host: RobotHost) : Robot(host) {
    val flywheel = Motor(this, "flywheel", velocityControl = true)
}

private class LatchRobot(host: RobotHost) : Robot(host) {
    val latch = PositionServo(this, "latch")
}

class MotorAndServoTest {
    private val motor = FakeMotor()
    private val servo = FakeServo()

    @BeforeTest
    fun setUp() {
        Config.load("")
        Motor.KeptZeros.forget()
    }

    /** A new opmode: a new host and robot, on the same devices */
    private fun <R : Robot> opMode(robot: (RobotHost) -> R): Pair<TestFtcHost, R> {
        val host = TestFtcHost()
        host.hardware.add("turret", motor.device)
        host.hardware.add("flywheel", motor.device)
        host.hardware.add("latch", servo.device)
        return host to robot(host).also { host.runner.init() }
    }

    private fun TestFtcHost.run(time: Duration) {
        repeat((time / 10.milliseconds).toInt()) {
            this.time += 10.milliseconds
            runner.tick()
        }
    }

    @Test
    fun keepZeroSharesTheZeroWithTheNextOpModes() {
        motor.position = 1000
        val (_, auto) = opMode(::TurretRobot)
        assertEquals(0, auto.turret.ticks)
        motor.position = 1100

        val (_, teleOp) = opMode(::TurretRobot)
        assertEquals(100, teleOp.turret.ticks, "the zero from the first INIT")

        teleOp.turret.resetPosition()
        val (_, next) = opMode(::TurretRobot)
        assertEquals(0, next.turret.ticks, "a new zero, for the following opmodes too")
    }

    @Test
    fun withoutKeepZeroEveryOpModeStartsFromZero() {
        motor.position = 1000
        opMode(::TurretRobot)
        motor.position = 1100
        Config.load("turret.keepZero = no")
        val (_, next) = opMode(::TurretRobot)
        assertEquals(0, next.turret.ticks)
    }

    @Test
    fun velocityControlStopsAMotorWhoseEncoderDoesNotMove() {
        val (host, robot) = opMode(::FlywheelRobot)
        robot.flywheel.power = 0.5
        host.run(300.milliseconds)
        assertEquals(0.5, motor.power, "not yet")

        host.run(400.milliseconds)
        assertEquals(0.0, motor.power)
        robot.flywheel.power = 0.8
        assertEquals(0.0, motor.power, "stays stopped")

        val telemetry = host.runner.telemetry
        host.time += 100.milliseconds
        telemetry.startLoop()
        host.runner.tick()
        assertTrue(telemetry.finishLoop()!!.any { it.startsWith("flywheel: stopped: its encoder doesn't move") })
    }

    @Test
    fun velocityControlLeavesAMovingMotorAlone() {
        val (host, robot) = opMode(::FlywheelRobot)
        robot.flywheel.power = 0.5
        repeat(100) {
            motor.position += 10
            host.run(10.milliseconds)
        }
        assertEquals(0.5, motor.power)
    }

    @Test
    fun pulseWidthsFromConfig() {
        val (host, _) = opMode(::LatchRobot)
        assertEquals(PwmControl.PwmRange.defaultRange.usPulseLower, servo.pwmRange.usPulseLower)
        Config.load("latch.pwmMin = 500\nlatch.pwmMax = 2500")
        host.run(10.milliseconds)
        assertEquals(500.0, servo.pwmRange.usPulseLower)
        assertEquals(2500.0, servo.pwmRange.usPulseUpper)
    }
}
