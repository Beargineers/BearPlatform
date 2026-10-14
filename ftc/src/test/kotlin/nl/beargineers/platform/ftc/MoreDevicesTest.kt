package nl.beargineers.platform.ftc

import com.qualcomm.hardware.broadcom.BroadcomColorSensor
import com.qualcomm.robotcore.hardware.DcMotorSimple
import nl.beargineers.platform.component.Robot
import nl.beargineers.platform.component.RobotHost
import nl.beargineers.platform.config.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private class DevicesRobot(host: RobotHost) : Robot(host) {
    val sample = ColorSensor(this, "sample")
    val arm = AnalogInput(this, "arm", device = "armAngle")
    val roller = CRServo(this, "roller")
}

class MoreDevicesTest {
    private val host = TestFtcHost()
    private val color = FakeColorSensor()
    private val analog = FakeAnalogInput()
    private val crServo = FakeCRServo()
    private lateinit var robot: DevicesRobot

    @BeforeTest
    fun setUp() {
        Config.load("")
        host.hardware.add("sample", color.device)
        host.hardware.add("armAngle", analog.device)
        host.hardware.add("roller", crServo.device)
    }

    private fun start() {
        robot = DevicesRobot(host)
        host.runner.init()
    }

    private fun tick(time: Duration = 10.milliseconds) {
        repeat((time / 10.milliseconds).toInt()) {
            host.time += 10.milliseconds
            host.runner.tick()
        }
    }

    @Test
    fun hueSaturationAndValue() {
        fun check(rgb: Rgb, hue: Double, saturation: Double) {
            assertEquals(hue, rgb.hue, 1e-9, "hue of $rgb")
            assertEquals(saturation, rgb.saturation, 1e-9, "saturation of $rgb")
        }
        check(Rgb(0.02, 0.0, 0.0), 0.0, 1.0)
        check(Rgb(0.02, 0.02, 0.0), 60.0, 1.0)
        check(Rgb(0.0, 0.02, 0.0), 120.0, 1.0)
        check(Rgb(0.0, 0.0, 0.02), 240.0, 1.0)
        check(Rgb(0.02, 0.0, 0.01), 330.0, 1.0)
        check(Rgb(0.02, 0.01, 0.01), 0.0, 0.5)
        check(Rgb(0.01, 0.01, 0.01), 0.0, 0.0)
        check(Rgb(0.0, 0.0, 0.0), 0.0, 0.0)
        // Dim or bright, the same color
        check(Rgb(0.003, 0.001, 0.006), Rgb(0.03, 0.01, 0.06).hue, Rgb(0.03, 0.01, 0.06).saturation)
        assertEquals(0.06, Rgb(0.03, 0.01, 0.06).value)
    }

    @Test
    fun theColorSensorMeasuresEvery25MsAndIsReadEveryReadInterval() {
        start()
        assertEquals(BroadcomColorSensor.LSMeasurementRate.R25ms, color.parameters.lightSensorMeasRate)
        assertEquals(BroadcomColorSensor.PSMeasurementRate.R25ms, color.parameters.proximityMeasRate)

        color.red = 0.03f
        color.green = 0.01f
        color.blue = 0.06f
        tick()
        assertEquals(1, color.colorReads)
        assertEquals(Rgb(0.03, 0.01, 0.06).hue, robot.sample.color!!.hue, 1e-6)
        assertNull(robot.sample.distance, "distance isn't read unless asked for")
        assertEquals(0, color.distanceReads)
        tick(40.milliseconds)
        assertEquals(1, color.colorReads)
        tick()
        assertEquals(2, color.colorReads)
    }

    @Test
    fun theColorSensorsDistanceWhenAskedFor() {
        Config.load("sample.readDistance = yes")
        start()
        tick()
        assertNull(robot.sample.distance, "the sensor's answer for nothing")
        color.cm = 3.0
        tick(50.milliseconds)
        assertEquals(3.0, robot.sample.distance!!.inCm)
        assertEquals(2, color.distanceReads)
    }

    @Test
    fun theColorSensorPauses() {
        start()
        tick()
        robot.sample.reading = false
        tick(200.milliseconds)
        assertNull(robot.sample.color)
        assertEquals(1, color.colorReads)
    }

    @Test
    fun analogInputs() {
        start()
        analog.voltage = 1.65
        assertEquals(1.65, robot.arm.voltage)
        assertEquals(0.5, robot.arm.fraction, 1e-9)
        assertEquals(3.3, robot.arm.maxVoltage)
        val reads = analog.reads
        tick(100.milliseconds)
        assertEquals(reads, analog.reads, "loops don't read what nothing asks for")
    }

    @Test
    fun crServosSendPowerOnlyWhenItChangesEnough() {
        Config.load("roller.direction = R\nroller.pwmMin = 500\nroller.pwmMax = 2500")
        start()
        assertEquals(DcMotorSimple.Direction.REVERSE, crServo.direction)
        assertEquals(500.0, crServo.pwmRange.usPulseLower)
        assertEquals(2500.0, crServo.pwmRange.usPulseUpper)

        robot.roller.power = 0.5
        robot.roller.power = 0.502
        assertEquals(1, crServo.powerWrites)
        robot.roller.power = 2.0
        assertEquals(1.0, crServo.power, "clamped and exact")
        robot.roller.power = 0.0
        assertEquals(3, crServo.powerWrites)

        robot.roller.power = 0.7
        host.runner.stop()
        assertEquals(0.0, crServo.power, "stops with the opmode")
        assertTrue(crServo.powerWrites == 5)
    }
}
