package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorSimple.Direction
import nl.beargineers.platform.component.Robot
import nl.beargineers.platform.component.RobotHost
import nl.beargineers.platform.config.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class DriveRobot(host: RobotHost) : Robot(host) {
    val drive = MecanumDrive(this, ticksPerTurn = 384.5)
}

class MecanumDriveTest {
    private val host = TestFtcHost()
    private val leftFront = FakeMotor()
    private val rightFront = FakeMotor()
    private val leftBack = FakeMotor()
    private val rightBack = FakeMotor()
    private val motors = listOf(leftFront, rightFront, leftBack, rightBack)
    private lateinit var robot: DriveRobot

    @BeforeTest
    fun setUp() {
        Config.load("")
        host.hardware.add("leftFront", leftFront.device)
        host.hardware.add("rightFront", rightFront.device)
        host.hardware.add("leftBack", leftBack.device)
        host.hardware.add("rightBack", rightBack.device)
        robot = DriveRobot(host)
        host.runner.init()
    }

    private fun tick(times: Int = 1) {
        repeat(times) {
            host.time += 10.milliseconds
            host.runner.tick()
        }
    }

    private fun powers() = motors.map { it.power }

    @Test
    fun leftMotorsRunReversed() {
        assertEquals(listOf(Direction.REVERSE, Direction.FORWARD, Direction.REVERSE, Direction.FORWARD), motors.map { it.direction })
        Config.load("drive.leftFront.direction = F")
        tick()
        assertEquals(Direction.FORWARD, leftFront.direction)
    }

    @Test
    fun velocityControlForAllWheels() {
        assertTrue(motors.all { it.mode == DcMotor.RunMode.RUN_WITHOUT_ENCODER })

        val other = TestFtcHost()
        val wheels = List(4) { FakeMotor() }
        listOf("leftFront", "rightFront", "leftBack", "rightBack").zip(wheels) { name, motor -> other.hardware.add(name, motor.device) }
        object : Robot(other) {
            val drive = MecanumDrive(this, velocityControl = true)
        }
        other.runner.init()
        assertTrue(wheels.all { it.mode == DcMotor.RunMode.RUN_USING_ENCODER })
    }

    @Test
    fun drivesTheWheels() {
        robot.drive.drive(forward = 0.5, right = 0.25)
        assertEquals(listOf(0.75, 0.25, 0.25, 0.75), powers())
        robot.drive.stopMoving()
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), powers())
    }

    @Test
    fun maxPowerAndStrafeScaleFromConfig() {
        Config.load("drive.maxPower = 0.5\ndrive.strafeScale = 2")
        robot.drive.drive(right = 0.25)
        assertEquals(listOf(0.25, -0.25, -0.25, 0.25), powers())
    }

    @Test
    fun nominalVoltageMakesUpForTheBattery() {
        host.voltage = 13.0
        robot.drive.drive(forward = 0.5)
        assertEquals(0.5, leftFront.power, "off unless set")

        Config.load("drive.nominalVoltage = 12")
        robot.drive.drive(forward = 0.5)
        assertEquals(0.5 * 12 / 13, leftFront.power, 1e-9)
        host.voltage = 10.0
        robot.drive.drive(forward = 0.5)
        assertEquals(0.6, leftFront.power)
        robot.drive.drive(forward = 1.0)
        assertEquals(1.0, leftFront.power, "full power stays full")
    }

    @Test
    fun wheelsStopAtTheEnd() {
        robot.drive.drive(forward = 0.5)
        host.runner.stop()
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), powers())
    }

    @Test
    fun checkWheelsSpinsOneWheelAtATime() {
        host.runner.start { robot.drive.checkWheels() }
        val seen = mutableListOf<List<Double>>()
        repeat(450) {
            tick()
            if (seen.lastOrNull() != powers()) seen += powers()
        }
        assertEquals(
            listOf(
                listOf(0.3, 0.0, 0.0, 0.0),
                listOf(0.0, 0.3, 0.0, 0.0),
                listOf(0.0, 0.0, 0.3, 0.0),
                listOf(0.0, 0.0, 0.0, 0.3),
                listOf(0.0, 0.0, 0.0, 0.0),
            ),
            seen,
        )
        assertFalse(robot.drive.isBusy)
    }

    @Test
    fun checkWheelsShowsWhichOne() {
        host.runner.start { robot.drive.checkWheels() }
        val telemetry = host.runner.telemetry
        telemetry.startLoop()
        tick()
        assertTrue("Checking wheel: leftFront: should push the robot forward" in telemetry.finishLoop()!!)
    }
}
