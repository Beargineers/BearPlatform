package org.beargineers.platform.ftc

import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.util.RobotLog
import kotlinx.coroutines.CoroutineScope
import org.beargineers.platform.component.RobotRunner
import org.beargineers.platform.config.Config
import org.beargineers.platform.runtime.LoopRuntime
import org.beargineers.platform.telemetry.LoopTimer

/**
 * Base class for opmodes written with coroutines. Create the robot as a property, `val robot = GammaRobot(this)`, then:
 * - [onInit] runs from INIT until START, e.g. for menus or picking the starting pose. START cancels it.
 * - [onStart] runs from START until the opmode ends.
 * - [onStop] runs at the very end, after all coroutines and components have stopped.
 *
 * INIT loads the robot's config (see [RobotConfig]) and fails if a value can't be read. Other config problems,
 * like keys nothing uses, warnings of the robot's components, and a low battery are shown on the Driver Station
 * until START.
 *
 * Every loop reads the gamepads and all hub sensors (in one bulk read), updates the robot's components,
 * runs coroutines, and sends what they [show] to the Driver Station and the robot's graphs page.
 */
abstract class CoroutineOpMode : LinearOpMode(), FtcHost {
    final override val runner = RobotRunner(log = { RobotLog.ii(LOG_TAG, "%s", it) })

    final override val opMode: OpMode get() = this

    val runtime: LoopRuntime get() = runner.runtime

    /** Gamepad 1, read once per loop. Its config settings, like the deadband, start with `driver1.` */
    val driver1 = Controller(this, "driver1") { gamepad1 }

    /** Gamepad 2, read once per loop. Its config settings, like the deadband, start with `driver2.` */
    val driver2 = Controller(this, "driver2") { gamepad2 }

    /** The battery, read through the hubs a few times a second */
    val battery = BatteryMonitor({ hardwareMap.voltageSensor.toList() }, runner.telemetry)

    private val loopTimer = LoopTimer(runner.telemetry)

    /**
     * Shows "label: value" on the Driver Station and the robot's graphs page, for this loop. Lines are only collected
     * every few loops (`telemetry.updateInterval`); in the others this does nothing, and [value] is never turned into text.
     */
    fun show(label: String, value: Any?) = runner.telemetry.show(label, value)

    /** Shows "label: value", computing the value only in loops that collect lines: for values expensive to make */
    inline fun show(label: String, value: () -> Any?) = runner.telemetry.show(label, value)

    /** Shows a line on the Driver Station and the robot's graphs page, for this loop */
    fun show(line: String) = runner.telemetry.show(line)

    /** Records [value] under [name] for the live graphs on the robot's web page */
    fun graph(name: String, value: Double) = runner.telemetry.graph(name, value)

    /** Writes [message] to the robot's log */
    fun log(message: String) = runner.telemetry.log(message)

    open suspend fun CoroutineScope.onInit() {}

    abstract suspend fun CoroutineScope.onStart()

    open fun onStop() {}

    final override fun runOpMode() {
        val hubs = hardwareMap.getAll(LynxModule::class.java)
        for (hub in hubs) hub.bulkCachingMode = LynxModule.BulkCachingMode.MANUAL
        LiveTelemetry.publish(runner.telemetry)
        LiveTelemetry.running = true

        try {
            // Fails INIT, listing every value that can't be read, rather than failing in the middle of a match
            RobotConfig.onRobot(hardwareMap.appContext).loadForOpMode()
            val configProblems = Config.problems().map { "Config: $it" }
            configProblems.forEach { RobotLog.ww(LOG_TAG, "%s", it) }

            battery.update()
            val batteryWarning = listOfNotNull(battery.warning())
            batteryWarning.forEach { RobotLog.ww(LOG_TAG, "%s", it) }

            runner.init { onInit() }
            // Components report their warnings while they initialize, and they are logged then
            val problems = configProblems + runner.robot?.warnings.orEmpty() + batteryWarning
            while (opModeInInit()) loopOnce(hubs, problems)

            if (opModeIsActive()) {
                runner.start { onStart() }
                while (opModeIsActive()) loopOnce(hubs, emptyList())
            }
        } finally {
            LiveTelemetry.running = false
            battery.logLowest()
            try {
                runner.stop()
            } finally {
                onStop()
            }
        }
    }

    /**
     * One loop; [header] lines go first on the Driver Station. Only loops that collect lines touch the SDK's
     * telemetry, which itself sends to the Driver Station at most every 250 ms.
     */
    private fun loopOnce(hubs: List<LynxModule>, header: List<String>) {
        val report = runner.telemetry
        loopTimer.loopStarted(runtime.elapsed)
        for (hub in hubs) hub.clearBulkCache()
        report.startLoop()
        if (report.isCollecting) {
            header.forEach { report.show(it) }
            loopTimer.report()
        }
        battery.update()
        runner.tick()
        val lines = report.finishLoop() ?: return
        for (line in lines) telemetry.addLine(line)
        telemetry.update()
    }
}

/** Tag of the platform's lines in logcat and the robot's logs */
internal const val LOG_TAG = "BearPlatform"
