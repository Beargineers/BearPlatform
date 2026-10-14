package nl.beargineers.platform.ftc

import nl.beargineers.platform.component.Component
import nl.beargineers.platform.config.config
import nl.beargineers.platform.units.Distance
import nl.beargineers.platform.units.cm
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import com.qualcomm.robotcore.hardware.DistanceSensor as SdkDistanceSensor

/**
 * A distance sensor, like REV's 2m distance sensor, as a part of [parent]: `val ball = DistanceSensor(this, "ball")`.
 *
 * It is an I2C device, slow to read, so it is read on the robot's device reader thread every [readInterval], and the
 * loop never waits for it (see [BackgroundSensor]). REV's 2m sensor measures about every 33 ms, and a read waits for a
 * new measurement, asking the sensor over and over: reading more often than that only keeps the hubs' connection busy
 * waiting. Each read holds the connection for about 9 ms: turn [reading] off while nothing needs the sensor.
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
) : BackgroundSensor<Double>(parent, name, readInterval) {
    /** Readings farther than this count as nothing in range: the sensor's own range, 200 cm for REV's 2m sensor */
    val maxRange by config(maxRange)

    /** The SDK sensor, for anything this class doesn't cover */
    val device: SdkDistanceSensor by hardware(device)

    /**
     * How far the sensor sees something, as of this loop; null when nothing is within [maxRange], when the sensor
     * doesn't answer, while not [reading], and before its first reading
     */
    val distance: Distance?
        get() = value?.takeIf { it <= maxRange.inCm }?.cm

    override fun read(): Double = device.getDistance(DistanceUnit.CM)
}
