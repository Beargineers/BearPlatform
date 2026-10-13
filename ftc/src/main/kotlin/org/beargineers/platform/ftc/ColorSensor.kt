package org.beargineers.platform.ftc

import com.qualcomm.hardware.broadcom.BroadcomColorSensor
import com.qualcomm.robotcore.hardware.NormalizedColorSensor
import org.beargineers.platform.component.Component
import org.beargineers.platform.config.config
import org.beargineers.platform.units.Distance
import org.beargineers.platform.units.cm
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import com.qualcomm.robotcore.hardware.DistanceSensor as SdkDistanceSensor

/**
 * A color, as a sensor measured it: [red], [green] and [blue] from 0 to 1 of what the sensor can measure. Colors of
 * things in front of a sensor are dim, so compare [hue] and [saturation], which don't depend on how bright the light
 * is, rather than the channels themselves. On Beta's REV sensor, DECODE's green artifact was hue 162° with saturation
 * 0.73, and the purple one hue 230° with saturation 0.48. With nothing in front of the sensor, hue and saturation are
 * noise, so check that something is there first, by [value] or the sensor's distance.
 */
class Rgb(val red: Double, val green: Double, val blue: Double) {
    /** The brightest channel, from 0 to 1 */
    val value: Double = maxOf(red, green, blue)

    /** How far the color is from gray, from 0 (gray, white or black) to 1 (one pure color) */
    val saturation: Double

    /** Where the color is on the color wheel, in degrees from 0 to 360: red 0, yellow 60, green 120, blue 240 */
    val hue: Double

    init {
        val min = minOf(red, green, blue)
        val range = value - min
        saturation = if (value > 0) range / value else 0.0
        val sixths = when {
            range == 0.0 -> 0.0
            value == red -> ((green - blue) / range).mod(6.0)
            value == green -> (blue - red) / range + 2
            else -> (red - green) / range + 4
        }
        hue = sixths * 60
    }

    override fun toString() = String.format(Locale.US, "hue %.0f°, saturation %.2f, value %.4f", hue, saturation, value)
}

/**
 * A color sensor, like REV's Color Sensor V3, as a part of [parent]: `val sample = ColorSensor(this, "sample")`.
 *
 * It is an I2C device, read on the robot's device reader thread every [readInterval] (see [BackgroundSensor]), and
 * every loop sees the newest [color]. Each read takes two I2C transfers, and with [readDistance] a third: on Beta's
 * Expansion Hub 11 ms, or 16 ms with the distance, so reading every 50 ms takes a fifth to a third of the time. Turn
 * [reading] off while nothing needs the sensor.
 *
 * REV's sensor measures every 100 ms unless told otherwise, so it is told to measure every 25 ms: a reading is then
 * at most 25 ms older than its read. It lights what it sees with its own LED, so it sees colors well only within a
 * centimeter or two.
 *
 * Its settings are config settings (`intake.sample.readInterval = 30ms`); the constructor arguments are the defaults.
 */
class ColorSensor(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
    readInterval: Duration = 50.milliseconds,
    readDistance: Boolean = false,
    maxRange: Distance = 15.cm,
) : BackgroundSensor<ColorSensor.Reading>(parent, name, readInterval) {
    /**
     * Whether to read the sensor's distance too, measured with infrared light it sends out. REV's sensor sees things a
     * few centimeters away, and how far depends on their color and surface: good for telling whether something is in
     * front of it, not for measuring. On Beta, artifacts on the sensor read 0.6 to 1 cm, and the robot's own parts
     * behind it 5 to 7 cm.
     */
    val readDistance by config(readDistance)

    /** Distances this far or farther count as nothing in front of the sensor; REV's says 15.2 cm (6 in) for nothing */
    val maxRange by config(maxRange)

    /** The SDK sensor, for anything this class doesn't cover */
    val device: NormalizedColorSensor by hardware(device)

    class Reading(val color: Rgb, val cm: Double?)

    /** The color the sensor sees, as of this loop; null while not [reading] and before its first reading */
    val color: Rgb? get() = value?.color

    /**
     * How far the sensor sees something, as of this loop; null when nothing is within [maxRange], without
     * [readDistance], while not [reading], and before the first reading
     */
    val distance: Distance?
        get() = value?.cm?.takeIf { it < maxRange.inCm }?.cm

    override fun init() {
        (device as? BroadcomColorSensor)?.let { sensor ->
            val parameters = sensor.parameters.clone()
            parameters.lightSensorMeasRate = BroadcomColorSensor.LSMeasurementRate.R25ms
            parameters.proximityMeasRate = BroadcomColorSensor.PSMeasurementRate.R25ms
            if (!sensor.initialize(parameters)) warn("couldn't set the sensor to measure every 25 ms")
        }
        super.init()
    }

    override fun read(): Reading {
        val colors = device.normalizedColors
        val color = Rgb(colors.red.toDouble(), colors.green.toDouble(), colors.blue.toDouble())
        val cm = if (readDistance) (device as? SdkDistanceSensor)?.getDistance(DistanceUnit.CM) else null
        return Reading(color, cm)
    }
}
