package org.beargineers.platform.ftc

import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.Servo
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType
import org.beargineers.platform.component.RobotRunner
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
    override val runner = RobotRunner(TestTimeSource())
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
    val motorType = MotorConfigurationType().apply {
        ticksPerRev = 384.5
        maxRPM = 435.0
        achieveableMaxRPMFraction = 1.0
    }

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
            "getMotorType" -> motorType
            else -> Unanswered
        }
    }
}

class FakeServo {
    var position = Double.NaN
    var positionWrites = 0
    var direction = Servo.Direction.FORWARD

    val device: Servo = fake { method, args ->
        when (method) {
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

object Unanswered

/**
 * An SDK device interface implemented by [answer]. Methods it leaves [Unanswered] return zero, false or null,
 * so fakes only have to handle what the code under test uses.
 */
inline fun <reified T> fake(crossinline answer: (method: String, args: List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        val arguments = args?.asList().orEmpty()
        when (method.name) {
            "equals" -> return@newProxyInstance proxy === arguments[0]
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "fake ${T::class.java.simpleName}"
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
