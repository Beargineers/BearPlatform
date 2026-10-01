package org.beargineers.platform.component

import kotlinx.coroutines.CoroutineScope
import org.beargineers.platform.config.ConfigScope
import org.beargineers.platform.runtime.ExclusiveResource
import org.beargineers.platform.runtime.LoopRuntime
import org.beargineers.platform.telemetry.RobotTelemetry

/**
 * A part of the robot: a subsystem like the intake, or a piece of one like the shooter's latch.
 *
 * Components form a tree with the [Robot] at the top. Create a component by passing its parent, e.g.
 * `val latch = Latch(this)` inside `Shooter`. Its [name] defaults to the class name (`latch`) and has to be unique
 * among the parent's parts, so two of the same kind need names: `Flywheel(this, "left")`, `Flywheel(this, "right")`.
 *
 * The [path] of names (`shooter.latch`) is the scope of the component's config keys: `val open by config(0.3)`
 * inside the latch is `shooter.latch.open`.
 *
 * Override [init], [update] and [stop] to take part in the robot's life. All components [init] before the first loop,
 * [update] once per loop before coroutines run, and [stop] at the end. Parents go before their parts, siblings go in
 * the order they were created, so create sensors and localization first. [stop] goes in reverse order.
 */
abstract class Component : ConfigScope {
    /** The component this one is a part of; null for the [Robot] */
    val parent: Component?
    val robot: Robot
    val name: String

    /** Names from the robot down to this component, like `shooter.latch`; empty for the [Robot] */
    val path: String

    final override val configScope: String

    private val parts = mutableListOf<Component>()
    private var initialized = false
    private val resource by lazy { ExclusiveResource(configScope) }

    /** A part of [parent], called [name] or by its class name when [name] is null */
    constructor(parent: Component, name: String? = null) {
        this.parent = parent
        this.robot = parent.robot
        this.name = name ?: javaClass.simpleName.replaceFirstChar { it.lowercaseChar() }.ifEmpty { "part" }
        require(validName.matches(this.name)) {
            "Component name '${this.name}' can only have letters, digits, '_' and '-'"
        }
        path = if (parent.path.isEmpty()) this.name else "${parent.path}.${this.name}"
        configScope = path
        parent.adopt(this)
    }

    internal constructor() {
        parent = null
        robot = this as Robot
        name = "robot"
        path = ""
        configScope = name
    }

    /** Parts of this component, in the order they were created */
    val children: List<Component> get() = parts.toList()

    val runtime: LoopRuntime get() = robot.host.runner.runtime

    /** Called once before the first [update]: set up hardware here */
    protected open fun init() {}

    /** Called every loop, before coroutines run: read sensors, apply what was requested */
    protected open fun update() {}

    /** Called once at the end: leave hardware in a safe state */
    protected open fun stop() {}

    /**
     * Reports a problem that doesn't stop the robot but should be fixed, like a doubtful hardware setting.
     * The opmode shows it on the Driver Station during INIT, and it goes to the log.
     */
    protected fun warn(message: String) {
        val warning = "${path.ifEmpty { name }}: $message"
        robot.addWarning(warning)
        telemetry.log("Warning: $warning")
    }

    /**
     * Shows "label: value" on the Driver Station and the robot's graphs page, for this loop. Lines are only collected
     * every few loops; in the others this does nothing, and [value] is never turned into text.
     */
    fun show(label: String, value: Any?) = telemetry.show(label, value)

    /** Shows "label: value", computing the value only in loops that collect lines: for values expensive to make */
    inline fun show(label: String, value: () -> Any?) = robot.host.runner.telemetry.show(label, value)

    /** Records [value] under [name] for the live graphs on the robot's web page */
    fun graph(name: String, value: Double) = telemetry.graph(name, value)

    /**
     * Runs [block], adding how long it took to `time <name> ms` on the graphs when `telemetry.profile` is on. [counted]
     * also graphs how many times per loop. Motors measure their writes to the hub this way.
     */
    inline fun <T> timed(name: String, counted: Boolean = false, block: () -> T): T =
        robot.host.runner.telemetry.timed(name, counted, block)

    /** Writes [message] to the robot's log, after this component's path */
    fun log(message: String) = telemetry.log("${path.ifEmpty { name }}: $message")

    private val telemetry: RobotTelemetry get() = robot.host.runner.telemetry

    /**
     * Runs [block] in control of this component: stops whatever action controlled it before, and ends with
     * [Preempted][org.beargineers.platform.runtime.Preempted] if another action takes over. See [ExclusiveResource].
     */
    suspend fun <T> takeOver(action: String, block: suspend CoroutineScope.() -> T): T = resource.takeOver(action, block)

    /** Name of the action in control of this component, or null */
    val currentAction: String? get() = resource.currentAction

    val isBusy: Boolean get() = resource.isBusy

    /** Stops the action in control of this component, if any */
    fun cancelAction() = resource.cancel()

    override fun toString(): String = "${javaClass.simpleName} ${path.ifEmpty { name }}"

    private fun adopt(part: Component) {
        check(parts.none { it.name == part.name }) {
            val kind = part.javaClass.simpleName
            "${path.ifEmpty { name }} already has a part named '${part.name}'. " +
                "Give them different names, like $kind(this, \"left\") and $kind(this, \"right\")"
        }
        parts += part
        robot.partAdded()
    }

    internal fun inOrder(): List<Component> = buildList { collectInOrder(this) }

    private fun collectInOrder(into: MutableList<Component>) {
        into += this
        for (part in parts) part.collectInOrder(into)
    }

    internal fun initIfNew() {
        if (!initialized) {
            initialized = true
            init()
        }
    }

    internal fun runUpdate() = update()

    // Profiling adds up updates by the robot's top-level parts: the drive's motors count as the drive
    internal val profileName: String by lazy { "update " + (path.substringBefore('.').ifEmpty { name }) }

    internal fun runStopIfInitialized() {
        if (initialized) stop()
    }

    private companion object {
        val validName = Regex("[A-Za-z0-9_-]+")
    }
}
