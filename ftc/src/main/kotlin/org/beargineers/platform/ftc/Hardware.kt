package org.beargineers.platform.ftc

import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.hardware.HardwareMap
import org.beargineers.platform.component.Component
import org.beargineers.platform.component.RobotHost
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/** A [RobotHost] on the Control Hub: gives components the opmode's hardware, gamepads and telemetry */
interface FtcHost : RobotHost {
    val opMode: OpMode

    /** The battery's voltage, read a few times a second; null when there is no reading */
    val batteryVoltage: Double? get() = null

    /** Reads slow devices, like I2C sensors, on a thread of its own; null where devices are read in the loop */
    val deviceReader: DeviceReader? get() = null
}

/** The devices from the robot configuration. Available from [init][Component] on, not in constructors. */
val Component.hardwareMap: HardwareMap
    get() {
        val host = robot.host as? FtcHost
            ?: error("${describe()} uses FTC hardware, so its robot has to be created in an FTC opmode")
        return host.opMode.hardwareMap
            ?: error("${describe()}: hardware is available from init() on, not in constructors")
    }

/**
 * A device from the robot configuration, for hardware the platform has no wrapper for:
 * `val pinpoint by hardware<GoBildaPinpointDriver>()`. The device is named like the property unless [name] is given.
 * It is looked up on first use, which has to be in [init][Component] or later.
 */
inline fun <reified T : Any> hardware(name: String? = null): ReadOnlyProperty<Component, T> =
    HardwareDevice(T::class.java, name)

class HardwareDevice<T : Any> @PublishedApi internal constructor(
    private val type: Class<T>,
    private val name: String?,
) : ReadOnlyProperty<Component, T> {
    private var device: T? = null

    override fun getValue(thisRef: Component, property: KProperty<*>): T =
        device ?: find(thisRef, name ?: property.name).also { device = it }

    private fun find(component: Component, name: String): T =
        component.hardwareMap.tryGet(type, name)
            ?: error("${component.describe()}: the robot configuration has no ${type.simpleName} named '$name'")
}

internal fun Component.describe(): String = path.ifEmpty { name }
