package nl.beargineers.platform.ftc

import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.util.RobotLog
import kotlinx.coroutines.CoroutineScope
import nl.beargineers.platform.component.Robot
import nl.beargineers.platform.component.RobotRunner
import nl.beargineers.platform.config.Config
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.locks.LockSupport
import nl.beargineers.platform.config.config
import nl.beargineers.platform.config.ConfigScope
import nl.beargineers.platform.runtime.LoopRuntime
import nl.beargineers.platform.telemetry.LoopTimer
import kotlin.time.DurationUnit
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration

/**
 * Base class for opmodes written with coroutines. Declare the robot as a property, `val robot by robot(::GammaRobot)`,
 * or `by robot(DecodeRobots)` for an opmode several robots share (see [robot] and [RobotChoice]), then:
 * - [onInit] runs from INIT until START, e.g. for menus or picking the starting pose. START cancels it.
 * - [onStart] runs from START until the opmode ends.
 * - [onStop] runs at the very end, after all coroutines and components have stopped.
 *
 * INIT loads the robot's config (see [RobotConfig]) and fails if a value can't be read. Other config problems,
 * like keys nothing uses, warnings of the robot's components, and a low battery are shown on the Driver Station
 * until START. Device reads taking more of the time than `reader.budget` are shown while they do.
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

    final override val batteryVoltage: Double? get() = battery.voltage

    final override val deviceReader = DeviceReader()

    final override val robotConfiguration: String? get() = activeRobotConfiguration()

    private val robotsToCreate = mutableListOf<RobotAtInit<*>>()

    /**
     * The opmode's robot, created at INIT: `val robot by robot(::GammaRobot)`, or `val robot by robot(DecodeRobots)` for
     * whichever robot the robot configuration picked on the Driver Station is (see [RobotChoice]). Created then rather
     * than with the opmode, a robot that can't be created fails INIT with the reason on the Driver Station. Use it from
     * [onInit] and [onStart] on.
     */
    protected fun <R : Robot> robot(create: (FtcHost) -> R): ReadOnlyProperty<CoroutineOpMode, R> =
        RobotAtInit(create).also { robotsToCreate += it }

    private inner class RobotAtInit<R : Robot>(private val create: (FtcHost) -> R) : ReadOnlyProperty<CoroutineOpMode, R> {
        private var robot: R? = null

        fun create() {
            robot = create(this@CoroutineOpMode)
        }

        override fun getValue(thisRef: CoroutineOpMode, property: KProperty<*>): R = robot
            ?: error("${property.name} is created at INIT: use it from onInit or onStart on, not while the opmode is created")
    }

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

    /**
     * Offers [action] as a command, started by a button on the robot's graphs page or by a script, for testing and
     * tuning: `command("forward 60 cm") { robot.follower.driveTo(FORWARD) }`. Offered until the calling coroutine ends:
     * from `onInit` until START, from `onStart` until the end. See [RemoteCommands][nl.beargineers.platform.input.RemoteCommands].
     */
    suspend fun command(name: String, action: suspend CoroutineScope.() -> Unit) = runner.commands.offer(name, action)

    open suspend fun CoroutineScope.onInit() {}

    abstract suspend fun CoroutineScope.onStart()

    open fun onStop() {}

    final override fun runOpMode() {
        val hubs = hardwareMap.getAll(LynxModule::class.java)
        for (hub in hubs) hub.bulkCachingMode = LynxModule.BulkCachingMode.MANUAL
        LiveTelemetry.publish(runner.telemetry, runner.commands)
        LiveTelemetry.running = true

        try {
            for (robot in robotsToCreate) robot.create()
            // Fails INIT, listing every value that can't be read, rather than failing in the middle of a match
            RobotConfig.onRobot(hardwareMap.appContext).loadForOpMode()
            val configProblems = Config.problems().map { "Config: $it" }
            configProblems.forEach { RobotLog.ww(LOG_TAG, "%s", it) }

            battery.update()
            val batteryWarning = listOfNotNull(battery.warning())
            batteryWarning.forEach { RobotLog.ww(LOG_TAG, "%s", it) }

            runner.init { onInit() }
            // Components added their reads in init()
            deviceReader.start()
            // Components report their warnings while they initialize, and they are logged then
            val problems = configProblems + runner.robot?.warnings.orEmpty() + batteryWarning
            while (opModeInInit()) pace { loopOnce(hubs, problems) }

            if (opModeIsActive()) {
                runner.start { onStart() }
                while (opModeIsActive()) pace { loopOnce(hubs, emptyList()) }
            }
        } finally {
            LiveTelemetry.running = false
            deviceReader.stop()
            memory.stop()
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
        report.startLoop()
        for (i in hubs.indices) hubs[i].clearBulkCache()
        val readsOverBudget = deviceReader.checkBudget(readerSettings.budget) { RobotLog.ww(LOG_TAG, "%s", it) }
        if (report.isCollecting) {
            header.forEach { report.show(it) }
            if (readsOverBudget != null) report.show(readsOverBudget)
            loopTimer.report()
            if (report.isProfiling) {
                memory.report()
                deviceReader.report(report::graph)
            }
        }
        report.timed("battery") { battery.update() }
        deviceReader.rethrowFailure()
        runner.tick()
        report.timed("telemetry") {
            val lines = report.finishLoop() ?: return@timed
            for (line in lines) telemetry.addLine(line)
            telemetry.update()
        }
    }

    private val memory = MemoryProfile()

    private val loopSettings = object : ConfigScope {
        override val configScope = "loop"

        /**
         * A loop that takes less than this sleeps for the rest. Looping faster than sensors change only burns the CPU
         * that the hubs' and the device reader's threads need, and makes garbage.
         */
        val minTime by config(5.milliseconds)
    }

    private val readerSettings = object : ConfigScope {
        override val configScope = "reader"

        /**
         * Percent of the time the device reader may spend reading before the Driver Station and the log warn. Its
         * reads hold the hubs' connection that the loop's motor powers and bulk reads need too.
         */
        val budget by config(50.0)
    }

    private var lastLoopEnd = 0L

    /**
     * Runs one loop, then sleeps if it took less than `loop.minTime`. When profiling, logs where the time went in a
     * loop that took unusually long, and long pauses between loops.
     */
    private inline fun pace(loop: () -> Unit) {
        val start = System.nanoTime()
        val report = runner.telemetry
        if (report.isProfiling && lastLoopEnd != 0L && start - lastLoopEnd > LONG_LOOP) {
            log("Long pause between loops: ${ms(start - lastLoopEnd)}")
        }
        loop()
        val work = System.nanoTime() - start
        if (report.isProfiling && work > LONG_LOOP) log("Long loop: ${ms(work)}: ${report.loopBreakdown()}")
        val rest = loopSettings.minTime.inWholeNanoseconds - work
        if (rest > 0) {
            LockSupport.parkNanos(rest)
            val slept = System.nanoTime() - start - work
            if (report.isProfiling && slept > rest + LONG_LOOP) log("Long sleep: ${ms(slept)} instead of ${ms(rest)}")
        }
        lastLoopEnd = System.nanoTime()
    }

    private fun ms(nanos: Long) = String.format(java.util.Locale.US, "%.1f ms", nanos / 1e6)

    /**
     * Garbage collection and allocation, from Android's runtime counters, graphed when profiling: the whole app's, and
     * the loop thread's, by part (see [RobotTelemetry.allocationCounter][nl.beargineers.platform.telemetry.RobotTelemetry.allocationCounter])
     */
    private inner class MemoryProfile {
        private var lastTime = Duration.ZERO
        private var lastGcMs = -1L
        private var lastAllocated = -1L
        private var lastLoopAllocated = -1L

        fun report() {
            if (runner.telemetry.allocationCounter == null) {
                // Counting allocations per thread costs a little on every allocation, so only while profiling
                @Suppress("DEPRECATION")
                android.os.Debug.startAllocCounting()
                runner.telemetry.allocationCounter = { threadAllocated() }
            }
            val now = runtime.elapsed
            val gcMs = stat("art.gc.gc-time")
            val allocated = stat("art.gc.bytes-allocated")
            val loopAllocated = threadAllocated()
            val seconds = (now - lastTime).toDouble(DurationUnit.SECONDS)
            if (lastGcMs >= 0 && gcMs != null && seconds > 0) runner.telemetry.graph("gc ms per s", (gcMs - lastGcMs) / seconds)
            if (lastAllocated >= 0 && allocated != null && seconds > 0) {
                runner.telemetry.graph("allocated KB per s", (allocated - lastAllocated) / 1024.0 / seconds)
            }
            if (lastLoopAllocated >= 0 && seconds > 0) {
                var bytes = loopAllocated - lastLoopAllocated
                if (bytes < 0) bytes += 1L shl 32
                runner.telemetry.graph("loop thread allocated KB per s", bytes / 1024.0 / seconds)
            }
            lastTime = now
            lastGcMs = gcMs ?: -1
            lastAllocated = allocated ?: -1
            lastLoopAllocated = loopAllocated
        }

        @Suppress("DEPRECATION")
        private fun threadAllocated(): Long = android.os.Debug.getThreadAllocSize().toLong()

        fun stop() {
            if (runner.telemetry.allocationCounter != null) {
                runner.telemetry.allocationCounter = null
                @Suppress("DEPRECATION")
                android.os.Debug.stopAllocCounting()
            }
        }

        private fun stat(name: String): Long? = android.os.Debug.getRuntimeStat(name)?.toLongOrNull()
    }
}

/** With profiling on, loops taking longer than this are logged with where their time went */
private const val LONG_LOOP = 40_000_000L

/** Tag of the platform's lines in logcat and the robot's logs */
internal const val LOG_TAG = "BearPlatform"
