package nl.beargineers.platform.ftc

import nl.beargineers.platform.component.Component
import nl.beargineers.platform.config.config
import kotlin.time.Duration

/**
 * A sensor too slow to read in the loop, like an I2C distance or color sensor: it is read on the robot's device reader
 * thread every [readInterval], and the loop never waits for it. The newest reading is taken at the start of every
 * loop, with the gamepads, so everything in a loop sees the same one. Subclasses say how to [read] the sensor and
 * what of the reading they offer.
 *
 * Where there is no device reader, like in tests, the loop reads the sensor itself when it is time.
 */
abstract class BackgroundSensor<R : Any>(parent: Component, name: String, readInterval: Duration) : Component(parent, name) {
    /**
     * How often the sensor is read. Every read takes the hubs' connection from motors and other sensors, so read as
     * rarely as the robot can do with.
     */
    val readInterval by config(readInterval)

    private class Reading<R>(val value: R, val at: Duration)

    // The newest reading, from the reader thread, and the one this loop uses
    @Volatile
    private var latest: Reading<R>? = null
    private var current: Reading<R>? = null
    private var readInLoop = true
    private var resumedAt = Duration.ZERO

    /**
     * Whether the sensor is read, on by default. Turn it off while nothing needs it, as the component using it knows:
     * `shooterBall.reading = isShooting`. While off, there is no reading; turned on again, there is none until the
     * first new one, taken right away.
     */
    @Volatile
    var reading = true
        set(value) {
            if (value && !field) resumedAt = runtime.elapsed
            field = value
        }

    init {
        robot.host.runner.readEveryTick {
            if (readInLoop && reading) {
                val last = latest
                if (last == null || last.at < resumedAt || runtime.elapsed - last.at >= this.readInterval) latest = take()
            }
            current = latest?.takeIf { reading && it.at >= resumedAt }
        }
    }

    /** This loop's reading; null while not [reading] and before the first reading */
    protected val value: R? get() = current?.value

    /** When this loop's reading was taken, on the loop's clock; null when there is none */
    val readAt: Duration? get() = current?.at

    /** Reads the sensor; on the device reader's thread, or in the loop where there is none */
    protected abstract fun read(): R

    override fun init() {
        (robot.host as? FtcHost)?.deviceReader?.let { reader ->
            reader.add(path, { readInterval }, { reading }) { latest = take() }
            readInLoop = false
        }
    }

    private fun take() = Reading(read(), runtime.elapsed)
}
