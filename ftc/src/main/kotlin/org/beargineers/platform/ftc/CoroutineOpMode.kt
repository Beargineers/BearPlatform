package org.beargineers.platform.ftc

import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.util.RobotLog
import kotlinx.coroutines.CoroutineScope
import org.beargineers.platform.component.RobotRunner
import org.beargineers.platform.config.Config
import org.beargineers.platform.runtime.LoopRuntime

/**
 * Base class for opmodes written with coroutines. Create the robot as a property, `val robot = GammaRobot(this)`, then:
 * - [onInit] runs from INIT until START, e.g. for menus or picking the starting pose. START cancels it.
 * - [onStart] runs from START until the opmode ends.
 * - [onStop] runs at the very end, after all coroutines and components have stopped.
 *
 * INIT loads the robot's config (see [RobotConfig]) and fails if a value can't be read. Other config problems,
 * like keys nothing uses, are shown on the Driver Station until START.
 *
 * Every loop reads the gamepads and all hub sensors (in one bulk read), updates the robot's components,
 * runs coroutines, and sends telemetry.
 */
abstract class CoroutineOpMode : LinearOpMode(), FtcHost {
    final override val runner = RobotRunner(log = { RobotLog.ii(LOG_TAG, "%s", it) })

    final override val opMode: OpMode get() = this

    val runtime: LoopRuntime get() = runner.runtime

    /** Gamepad 1, read once per loop. Its config settings, like the deadband, start with `driver1.` */
    val driver1 = Controller(this, "driver1") { gamepad1 }

    /** Gamepad 2, read once per loop. Its config settings, like the deadband, start with `driver2.` */
    val driver2 = Controller(this, "driver2") { gamepad2 }

    open suspend fun CoroutineScope.onInit() {}

    abstract suspend fun CoroutineScope.onStart()

    open fun onStop() {}

    final override fun runOpMode() {
        val hubs = hardwareMap.getAll(LynxModule::class.java)
        for (hub in hubs) hub.bulkCachingMode = LynxModule.BulkCachingMode.MANUAL

        try {
            // Fails INIT, listing every value that can't be read, rather than failing in the middle of a match
            RobotConfig.onRobot(hardwareMap.appContext).loadForOpMode()
            val configProblems = Config.problems()
            configProblems.forEach { RobotLog.ww(LOG_TAG, "Config: %s", it) }

            runner.init { onInit() }
            while (opModeInInit()) {
                configProblems.forEach { telemetry.addLine("Config: $it") }
                loopOnce(hubs)
            }

            if (opModeIsActive()) {
                runner.start { onStart() }
                while (opModeIsActive()) loopOnce(hubs)
            }
        } finally {
            try {
                runner.stop()
            } finally {
                onStop()
            }
        }
    }

    private fun loopOnce(hubs: List<LynxModule>) {
        for (hub in hubs) hub.clearBulkCache()
        runner.tick()
        telemetry.update()
    }

    private companion object {
        const val LOG_TAG = "BearPlatform"
    }
}
