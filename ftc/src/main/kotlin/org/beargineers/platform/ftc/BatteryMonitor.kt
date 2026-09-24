package org.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.VoltageSensor
import org.beargineers.platform.config.ConfigScope
import org.beargineers.platform.config.config
import org.beargineers.platform.telemetry.RobotTelemetry
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Watches the battery through the hubs' voltage sensors: shows the voltage on the Driver Station, graphs it, and
 * remembers the lowest reading. Every opmode has one.
 *
 * Reading a voltage sensor is a separate request to the hub, not part of the bulk read, so it happens only a few
 * times a second rather than every loop.
 */
class BatteryMonitor(
    private val sensors: () -> List<VoltageSensor>,
    private val telemetry: RobotTelemetry,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ConfigScope {
    override val configScope: String = "battery"

    /** Below this many volts, INIT warns to charge or swap the battery */
    val low by config(12.0)

    /** The last reading, in volts; the lowest hub's if there are several. Null when no hub reads a battery. */
    var voltage: Double? = null
        private set

    /** The lowest reading since the opmode started, in volts */
    var lowest: Double? = null
        private set

    private val start = timeSource.markNow()
    private var lastRead: Duration? = null

    /** Called every loop: reads the sensors when it's time, and shows the voltage */
    fun update() {
        val now = start.elapsedNow()
        val last = lastRead
        if (last == null || now - last >= READ_EVERY) {
            lastRead = now
            // A hub without a battery reads 0
            voltage = sensors().map { it.voltage }.filter { it > 0.0 }.minOrNull()
            voltage?.let { volts ->
                lowest = minOf(lowest ?: volts, volts)
                telemetry.graph("battery", volts)
            }
        }
        telemetry.show("Battery") { voltage?.let { "${volts(it)}, lowest ${volts(lowest!!)}" } ?: "no reading" }
    }

    /** What INIT should warn about the battery, if anything */
    fun warning(): String? {
        val volts = voltage ?: return "Battery: no voltage reading. Is the battery connected and switched on?"
        return if (volts < low) "Battery at ${volts(volts)}, below battery.low = ${volts(low)}: charge or swap it" else null
    }

    /** Logs the lowest reading; the opmode calls it at the end */
    fun logLowest() {
        lowest?.let { telemetry.log("Battery: lowest ${volts(it)} during the opmode") }
    }

    private fun volts(value: Double) = String.format(Locale.US, "%.1f V", value)

    private companion object {
        val READ_EVERY = 250.milliseconds
    }
}
