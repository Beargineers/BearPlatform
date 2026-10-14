package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.Gamepad
import nl.beargineers.platform.config.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControllerTest {
    private val host = TestFtcHost()
    private val gamepad = Gamepad()
    private val controller = Controller(host, "controller1") { gamepad }

    @BeforeTest
    fun noDeadband() {
        Config.load("controller1.deadband = 0")
    }

    @Test
    fun buttonsFollowTheGamepad() {
        gamepad.a = true
        gamepad.dpad_left = true
        host.runner.tick()
        assertTrue(controller.a.wasPressed)
        assertTrue(controller.dpadLeft.isDown)
    }

    @Test
    fun stickForwardIsPositive() {
        gamepad.left_stick_y = -0.5f
        gamepad.right_stick_x = 0.25f
        host.runner.tick()
        assertEquals(0.5, controller.leftStick.y.value, 1e-6)
        assertEquals(0.25, controller.rightStick.x.value, 1e-6)
    }

    @Test
    fun triggers() {
        gamepad.right_trigger = 0.75f
        host.runner.tick()
        assertEquals(0.75, controller.rightTrigger.value, 1e-6)
    }
}
