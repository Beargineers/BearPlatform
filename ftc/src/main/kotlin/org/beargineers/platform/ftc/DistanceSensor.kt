package org.beargineers.platform.ftc

import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config
import org.beargineers.platform.units.Distance
import org.beargineers.platform.units.cm
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import com.qualcomm.robotcore.hardware.DistanceSensor as SdkDistanceSensor

/**
 * A distance sensor, like REV's 2m distance sensor or the distance of REV's color sensor, as a part of [parent]:
 * `val ball = DistanceSensor(this, "ball")`.
 *
 * It is an I2C device, slow to read, so it is read on the robot's device reader thread every [readInterval], and the
 * loop never waits for it. The newest reading is taken at the start of every loop, with the gamepads, so everything in
 * a loop sees the same [distance]. Each read holds the hubs' connection for about 9 ms: turn [reading] off while
 * nothing needs the sensor.
 *
 * Its settings are config settings (`intake.ball.readInterval = 60ms`); the constructor arguments are the defaults.
 */
class DistanceSensor(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
    readInterval: Duration = 50.milliseconds,
    maxRange: Distance = 200.cm,
) : Component(parent, name) {
    /**
     * How often the sensor is read. REV's 2m sensor measures about every 33 ms, and a read waits for a new
     * measurement, asking the sensor over and over: reading more often than that only keeps the hubs' connection busy
     * waiting. Every read takes connection time from motors and other sensors, so read as rarely as the robot can do
     * with.
     */
    val readInterval by config(readInterval)

    /** Readings farther than this count as nothing in range: the sensor's own range, 200 cm for REV's 2m sensor */
    val maxRange by config(maxRange)

    /** The SDK sensor, for anything this class doesn't cover */
    val device: SdkDistanceSensor by hardware(device)

    private class Reading(val cm: Double, val at: Duration)

    // The newest reading, from the reader thread, and the one this loop uses
    @Volatile
    private var latest: Reading? = null
    private var current: Reading? = null
    private var readInLoop = true
    private var resumedAt = Duration.ZERO

    /**
     * Whether the sensor is read, on by default. Turn it off while nothing needs it, as the component using it knows:
     * `shooterBall.reading = isShooting`. While off, [distance] is null; turned on again, it stays null until the
     * first new reading, taken right away.
     */
    @Volatile
    var reading = true
        set(value) {
            if (value && !field) resumedAt = runtime.elapsed
            field = value
        }

    init {
        robot.host.runner.readEveryTick {
            // Where there is no device reader, like in tests, the loop reads the sensor when it is time
            if (readInLoop && reading) {
                val last = latest
                if (last == null || last.at < resumedAt || runtime.elapsed - last.at >= this.readInterval) latest = read()
            }
            current = latest?.takeIf { reading && it.at >= resumedAt }
        }
    }

    /**
     * How far the sensor sees something, as of this loop; null when nothing is within [maxRange], when the sensor
     * doesn't answer, and before its first reading
     */
    val distance: Distance?
        get() = current?.cm?.takeIf { it <= maxRange.inCm }?.cm

    /** When [distance] was read, on the loop's clock; null before the first reading */
    val readAt: Duration? get() = current?.at

    override fun init() {
        (robot.host as? FtcHost)?.deviceReader?.let { reader ->
            reader.add(path, { readInterval }, { reading }) { latest = read() }
            readInLoop = false
        }
    }

    private fun read() = Reading(device.getDistance(DistanceUnit.CM), runtime.elapsed)
}
