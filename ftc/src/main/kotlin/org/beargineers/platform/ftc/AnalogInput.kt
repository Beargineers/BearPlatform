package org.beargineers.platform.ftc

import org.beargineers.platform.component.Component
import com.qualcomm.robotcore.hardware.AnalogInput as SdkAnalogInput

/**
 * A sensor that answers with a voltage, like a potentiometer, an absolute encoder or an ultrasonic range sensor, as a
 * part of [parent]: `val arm = AnalogInput(this, "armAngle")`.
 *
 * Analog inputs come with the hub's bulk read, which is taken again every loop: everything in a loop sees the same
 * [voltage]. The first input read in a loop costs one bulk read of its hub, about 2 ms, and the others on that hub,
 * motor encoders and digital inputs too, come with it. An input nothing reads in a loop costs nothing.
 */
class AnalogInput(
    parent: Component,
    name: String,
    /** Name in the robot configuration, if it differs from [name] */
    device: String = name,
) : Component(parent, name) {
    /** The SDK input, for anything this class doesn't cover */
    val device: SdkAnalogInput by hardware(device)

    /** Volts, from 0 to [maxVoltage] */
    val voltage: Double get() = device.voltage

    /** The highest voltage the hub measures: 3.3 V on REV hubs */
    val maxVoltage: Double get() = device.maxVoltage

    /** [voltage] as a share of [maxVoltage], from 0 to 1: where a potentiometer or an absolute encoder is */
    val fraction: Double get() = voltage / maxVoltage
}
