package org.beargineers.platform.ftc

import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.util.RobotLog
import kotlinx.coroutines.CoroutineScope
import org.beargineers.platform.component.Robot
import org.beargineers.platform.component.RobotHost
import org.beargineers.platform.component.RobotRunner
import org.beargineers.platform.runtime.LoopRuntime

/**
 * Base class for opmodes written with coroutines. Create the robot as a property, `val robot = GammaRobot(this)`, then:
 * - [onInit] runs from INIT until START, e.g. for menus or picking the starting pose. START cancels it.
 * - [onStart] runs from START until the opmode ends.
 * - [onStop] runs at the very end, after all coroutines and components have stopped.
 *
 * Every loop reads all hub sensors in one bulk read, updates the robot's components, runs coroutines,
 * and sends telemetry.
 */
abstract class CoroutineOpMode : LinearOpMode(), RobotHost {
    private val runner = RobotRunner(log = { RobotLog.ii(LOG_TAG, "%s", it) })

    override val runtime: LoopRuntime get() = runner.runtime

    override fun attach(robot: Robot) = runner.attach(robot)

    open suspend fun CoroutineScope.onInit() {}

    abstract suspend fun CoroutineScope.onStart()

    open fun onStop() {}

    final override fun runOpMode() {
        val hubs = hardwareMap.getAll(LynxModule::class.java)
        for (hub in hubs) hub.bulkCachingMode = LynxModule.BulkCachingMode.MANUAL

        try {
            runner.init { onInit() }
            while (opModeInInit()) loopOnce(hubs)

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
