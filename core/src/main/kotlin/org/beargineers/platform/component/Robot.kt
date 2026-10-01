package org.beargineers.platform.component

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.beargineers.platform.config.Config
import org.beargineers.platform.input.RemoteCommands
import org.beargineers.platform.runtime.LoopRuntime
import org.beargineers.platform.telemetry.RobotTelemetry
import kotlin.time.TimeSource

/**
 * The top of a robot's component tree. Create it in the opmode: `val robot = GammaRobot(this)`.
 *
 * The robot's name is not part of its parts' paths, so config keys stay short: `shooter.kV`, not `robot.shooter.kV`.
 * Settings declared in the robot class itself use the `robot` scope.
 */
abstract class Robot(val host: RobotHost) : Component() {
    private val reported = mutableListOf<String>()

    // All components in update order; built again only after a part is added, not every loop
    private var order: List<Component>? = null

    /** Problems components reported with [warn], like doubtful hardware settings. The opmode shows them at INIT. */
    val warnings: List<String> get() = reported.toList()

    init {
        host.runner.attach(this)
    }

    internal fun addWarning(warning: String) {
        reported += warning
    }

    internal fun components(): List<Component> = order ?: inOrder().also { order = it }

    internal fun partAdded() {
        order = null
    }
}

/**
 * What a robot is created with: the opmode on the Control Hub, or a [RobotRunner] in tests.
 * Platform modules extend it with what their components need, like the FTC hardware.
 */
interface RobotHost {
    /** Runs the robot created with this host */
    val runner: RobotRunner
}

/**
 * Runs a robot and coroutines through the phases of an opmode: [init], a [tick] per loop, [start], more ticks, [stop].
 * The FTC opmode drives it on the robot; tests drive it directly.
 */
class RobotRunner(timeSource: TimeSource = TimeSource.Monotonic, log: (String) -> Unit = {}) : RobotHost {
    init {
        // Each opmode builds its own robot, which may give settings other defaults than the last opmode's robot
        Config.newRound()
    }

    val runtime = LoopRuntime(timeSource, log)

    /** Driver Station lines, graphs and logs of this robot and its coroutines */
    val telemetry = RobotTelemetry({ runtime.elapsed }, log)

    /** Actions that can be started from the robot's graphs page or by scripts, for testing and tuning */
    val commands = RemoteCommands()

    override val runner: RobotRunner get() = this

    var robot: Robot? = null
        private set

    private var initJob: Job? = null
    private val inputs = mutableListOf<() -> Unit>()

    /** Reads inputs, like gamepads, at the start of every [tick], before anything else runs */
    internal fun readEveryTick(input: () -> Unit) {
        inputs += input
    }

    internal fun attach(robot: Robot) {
        check(this.robot == null) { "An opmode can only have one robot" }
        this.robot = robot
    }

    /** Initializes all components and launches [onInit], which runs until [start] */
    fun init(onInit: suspend CoroutineScope.() -> Unit = {}) {
        robot?.components()?.forEach { it.initIfNew() }
        initJob = runtime.launch("init", onInit)
    }

    /**
     * One loop: reads inputs, updates all components (initializing those created since the last loop first),
     * then runs coroutines
     */
    fun tick() {
        // Indexed loops: iterators would be garbage, every loop
        telemetry.timed("inputs") { for (i in inputs.indices) inputs[i]() }
        robot?.let { robot ->
            val components = robot.components()
            for (i in components.indices) components[i].initIfNew()
            for (i in components.indices) {
                val component = components[i]
                telemetry.timed(component.profileName) { component.runUpdate() }
            }
        }
        telemetry.timed("coroutines") { runtime.tick() }
    }

    /** Cancels the init coroutine and launches [onStart] */
    fun start(onStart: suspend CoroutineScope.() -> Unit) {
        initJob?.cancel()
        runtime.launch("main", onStart)
    }

    /** Cancels all coroutines, letting them clean up, then stops all components. Rethrows the first failure. */
    fun stop() {
        var failure: Throwable? = null
        try {
            runtime.stop()
        } catch (e: Throwable) {
            failure = e
        }
        for (component in robot?.components()?.asReversed().orEmpty()) {
            try {
                component.runStopIfInitialized()
            } catch (e: Throwable) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }
}
