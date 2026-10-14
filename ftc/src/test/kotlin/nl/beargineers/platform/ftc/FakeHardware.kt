package nl.beargineers.platform.ftc

import com.qualcomm.hardware.broadcom.BroadcomColorSensor
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.hardware.AnalogInput
import com.qualcomm.robotcore.hardware.AnalogInputController
import com.qualcomm.robotcore.hardware.CRServo
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.DigitalChannel
import com.qualcomm.robotcore.hardware.DigitalChannelController
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.LED
import com.qualcomm.robotcore.hardware.NormalizedColorSensor
import com.qualcomm.robotcore.hardware.NormalizedRGBA
import com.qualcomm.robotcore.hardware.PwmControl
import com.qualcomm.robotcore.hardware.Servo
import nl.beargineers.platform.component.RobotRunner
import java.lang.reflect.Proxy
import kotlin.time.TestTimeSource

/**
 * A robot configuration with fake devices. The SDK's own lookup loads a native library that only exists on the
 * Control Hub, so this one answers from a plain map.
 */
class FakeHardwareMap : HardwareMap(null, null) {
    private val devices = mutableMapOf<String, Any>()

    fun add(name: String, device: Any) {
        devices[name] = device
    }

    override fun <T> tryGet(classOrInterface: Class<out T>, deviceName: String): T? {
        val device = devices[deviceName]
        return if (classOrInterface.isInstance(device)) classOrInterface.cast(device) else null
    }
}

/** An [FtcHost] with an empty robot configuration; add fake devices to [hardware] */
class TestFtcHost(hardwareAvailable: Boolean = true) : FtcHost {
    val hardware = FakeHardwareMap()
    val time = TestTimeSource()
    override val runner = RobotRunner(time)
    var voltage: Double? = null
    override val batteryVoltage: Double? get() = voltage
    var reader: DeviceReader? = null
    override val deviceReader: DeviceReader? get() = reader
    var configuration: String? = null
    override val robotConfiguration: String? get() = configuration
    override val opMode: OpMode = object : OpMode() {
        override fun init() {}
        override fun loop() {}
    }.also { it.hardwareMap = if (hardwareAvailable) hardware else null }
}

class FakeMotor {
    var power = 0.0
    var powerWrites = 0
    var direction = DcMotorSimple.Direction.FORWARD
    var zeroPowerBehavior = DcMotor.ZeroPowerBehavior.UNKNOWN
    var mode = DcMotor.RunMode.RUN_USING_ENCODER
    var position = 0
    var ticksPerSecond = 0.0

    val device: DcMotorEx = fake { method, args ->
        when (method) {
            "setPower" -> {
                power = args[0] as Double
                powerWrites++
            }
            "getPower" -> power
            "setDirection" -> direction = args[0] as DcMotorSimple.Direction
            "getDirection" -> direction
            "setZeroPowerBehavior" -> zeroPowerBehavior = args[0] as DcMotor.ZeroPowerBehavior
            "setMode" -> mode = args[0] as DcMotor.RunMode
            "getCurrentPosition" -> position
            "getVelocity" -> if (args.isEmpty()) ticksPerSecond else Unanswered
            else -> Unanswered
        }
    }
}

class FakeServo {
    var position = Double.NaN
    var positionWrites = 0
    var direction = Servo.Direction.FORWARD
    var pwmRange = PwmControl.PwmRange.defaultRange

    /** Like the SDK's servos, it is a [PwmControl] too */
    val device: Servo = fakeOf(Servo::class.java, PwmControl::class.java) { method, args ->
        when (method) {
            "setPwmRange" -> pwmRange = args[0] as PwmControl.PwmRange
            "getPwmRange" -> pwmRange
            "setPosition" -> {
                position = args[0] as Double
                positionWrites++
            }
            "getPosition" -> position
            "setDirection" -> direction = args[0] as Servo.Direction
            "getDirection" -> direction
            else -> Unanswered
        }
    }
}

class FakeDigitalChannel {
    /** The line: true is high */
    var state = true
    var mode = DigitalChannel.Mode.OUTPUT

    val device: DigitalChannel = fake { method, args ->
        when (method) {
            "getState" -> state
            "setMode" -> mode = args[0] as DigitalChannel.Mode
            "getMode" -> mode
            else -> Unanswered
        }
    }
}

/** A REV LED on a digital port: lit while its line is low, as the SDK's [LED] drives it */
class FakeLed {
    var line = true
    var writes = 0
    val isOn: Boolean get() = !line

    val device = LED(fake<DigitalChannelController> { method, args ->
        when (method) {
            "setDigitalChannelState" -> {
                line = args[1] as Boolean
                writes++
            }
            else -> Unanswered
        }
    }, 0)
}

class FakeDistanceSensor {
    var cm = 50.0
    var reads = 0

    val device: com.qualcomm.robotcore.hardware.DistanceSensor = fake { method, args ->
        when (method) {
            "getDistance" -> {
                check(args[0] == org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.CM)
                reads++
                cm
            }
            else -> Unanswered
        }
    }
}

/** REV's Color Sensor V3, as the SDK offers it: a Broadcom color sensor that measures distance too */
class FakeColorSensor {
    var red = 0.0f
    var green = 0.0f
    var blue = 0.0f
    var cm = 15.24
    var colorReads = 0
    var distanceReads = 0
    var parameters: BroadcomColorSensor.Parameters = BroadcomColorSensor.Parameters.createForAPDS9151()

    val device: NormalizedColorSensor = fakeOf(
        BroadcomColorSensor::class.java, com.qualcomm.robotcore.hardware.DistanceSensor::class.java,
    ) { method, args ->
        when (method) {
            "getNormalizedColors" -> {
                colorReads++
                NormalizedRGBA().also {
                    it.red = red
                    it.green = green
                    it.blue = blue
                }
            }
            "getDistance" -> {
                distanceReads++
                cm
            }
            "getParameters" -> parameters
            "initialize" -> {
                parameters = args[0] as BroadcomColorSensor.Parameters
                true
            }
            else -> Unanswered
        }
    }
}

class FakeAnalogInput {
    var voltage = 0.0
    var reads = 0

    val device = AnalogInput(fake<AnalogInputController> { method, _ ->
        when (method) {
            "getAnalogInputVoltage" -> {
                reads++
                voltage
            }
            "getMaxAnalogInputVoltage" -> 3.3
            else -> Unanswered
        }
    }, 0)
}

class FakeCRServo {
    var power = 0.0
    var powerWrites = 0
    var direction = DcMotorSimple.Direction.FORWARD
    var pwmRange = PwmControl.PwmRange.defaultRange

    val device: CRServo = fakeOf(CRServo::class.java, PwmControl::class.java) { method, args ->
        when (method) {
            "setPower" -> {
                power = args[0] as Double
                powerWrites++
            }
            "getPower" -> power
            "setDirection" -> direction = args[0] as DcMotorSimple.Direction
            "getDirection" -> direction
            "setPwmRange" -> pwmRange = args[0] as PwmControl.PwmRange
            "getPwmRange" -> pwmRange
            else -> Unanswered
        }
    }
}

object Unanswered

/**
 * An SDK device interface implemented by [answer]. Methods it leaves [Unanswered] return zero, false or null,
 * so fakes only have to handle what the code under test uses.
 */
inline fun <reified T> fake(crossinline answer: (method: String, args: List<Any?>) -> Any?): T =
    fakeOf(T::class.java) { method, args -> answer(method, args) }

/** Like [fake], implementing all of [interfaces]; it is cast to the first */
@Suppress("UNCHECKED_CAST")
fun <T> fakeOf(vararg interfaces: Class<*>, answer: (method: String, args: List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(interfaces[0].classLoader, arrayOf(*interfaces)) { proxy, method, args ->
        val arguments = args?.asList().orEmpty()
        when (method.name) {
            "equals" -> return@newProxyInstance proxy === arguments[0]
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "fake ${interfaces[0].simpleName}"
        }
        val result = answer(method.name, arguments)
        when {
            method.returnType == Void.TYPE -> null
            result !== Unanswered -> result
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == Integer.TYPE -> 0
            method.returnType == java.lang.Long.TYPE -> 0L
            method.returnType == java.lang.Double.TYPE -> 0.0
            method.returnType == java.lang.Float.TYPE -> 0f
            else -> null
        }
    } as T
