package org.beargineers.platform.ftc

import org.beargineers.platform.component.Robot
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.config.Config
import org.beargineers.platform.units.cm
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private class BallRobot(host: RobotHost) : Robot(host) {
    val ball = DistanceSensor(this, "ball")
}

class DistanceSensorTest {
    private val host = TestFtcHost()
    private val sensor = FakeDistanceSensor()
    private lateinit var robot: BallRobot

    @BeforeTest
    fun setUp() {
        Config.load("")
        host.hardware.add("ball", sensor.device)
    }

    private fun start() {
        robot = BallRobot(host)
        host.runner.init()
    }

    private fun tick(time: Duration = 10.milliseconds) {
        repeat((time / 10.milliseconds).toInt()) {
            host.time += 10.milliseconds
            host.runner.tick()
        }
    }

    @Test
    fun withoutADeviceReaderTheLoopReadsItEveryReadInterval() {
        start()
        assertNull(robot.ball.distance, "no reading before the first loop")
        tick()
        assertEquals(50.0, robot.ball.distance!!.inCm)
        assertEquals(1, sensor.reads)
        tick(40.milliseconds)
        assertEquals(1, sensor.reads)
        tick(10.milliseconds)
        assertEquals(2, sensor.reads, "50 ms after the last read")
    }

    @Test
    fun onTheRobotTheReaderReadsItAndTheLoopTakesTheNewestReading() {
        var now = 0L
        val reader = DeviceReader { now }
        host.reader = reader
        start()
        tick()
        assertNull(robot.ball.distance)
        assertEquals(0, sensor.reads, "the loop doesn't read it")

        assertEquals(0, reader.readNextDue())
        assertNull(robot.ball.distance, "the loop takes it at its start")
        tick()
        assertEquals(50.0, robot.ball.distance!!.inCm)
        assertEquals(10.milliseconds, robot.ball.readAt, "read on the loop's clock, after the first loop")

        sensor.cm = 12.0
        assertEquals(50_000_000, reader.readNextDue(), "not before readInterval")
        now += 50_000_000
        reader.readNextDue()
        tick()
        assertEquals(12.0, robot.ball.distance!!.inCm)
    }

    @Test
    fun nothingInRangeIsNull() {
        start()
        sensor.cm = 6553.5   // the SDK's answer when the sensor doesn't measure
        tick()
        assertNull(robot.ball.distance)

        sensor.cm = 150.0
        Config.load("ball.maxRange = 100cm")
        tick(50.milliseconds)
        assertNull(robot.ball.distance)
        Config.load("")
        tick()
        assertEquals(150.cm.inCm, robot.ball.distance!!.inCm)
    }

    @Test
    fun turnedOffItIsNotReadAndTurnedOnItWaitsForANewReading() {
        start()
        tick()
        assertEquals(1, sensor.reads)

        robot.ball.reading = false
        tick()
        assertNull(robot.ball.distance, "no distance while off")
        tick(200.milliseconds)
        assertEquals(1, sensor.reads)

        sensor.cm = 12.0
        robot.ball.reading = true
        tick()
        assertEquals(12.0, robot.ball.distance!!.inCm, "read right away, not the old reading")
        assertEquals(2, sensor.reads)
    }

    @Test
    fun turnedOffTheReaderSkipsIt() {
        var now = 0L
        val reader = DeviceReader { now }
        host.reader = reader
        start()
        reader.readNextDue()
        tick()
        assertEquals(50.0, robot.ball.distance!!.inCm)

        robot.ball.reading = false
        now += 200_000_000
        reader.readNextDue()
        assertEquals(1, sensor.reads)
        tick()
        assertNull(robot.ball.distance)

        // A reading from before it was turned off doesn't count as new
        robot.ball.reading = true
        tick()
        assertNull(robot.ball.distance)
        sensor.cm = 12.0
        assertEquals(0, reader.readNextDue(), "due at once")
        tick()
        assertEquals(12.0, robot.ball.distance!!.inCm)
    }
}
