package nl.beargineers.platform.config

import nl.beargineers.platform.geometry.Field
import java.io.StringReader
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Robot settings that can change without rebuilding the code: PID gains, servo positions, field locations and so on.
 *
 * Declare a setting with `val kP by config(0.02)`. If the config text has a value for it, that value is used,
 * otherwise the default. Every successful [load] takes effect right away, even while the robot runs.
 *
 * Keys are `<scope>.<property name>`. The scope is the name of the class the property is declared in (`Shooter.kP`),
 * unless the class implements [ConfigScope] to choose it. Top-level properties have no scope: just the property name.
 * `config(0.02, key = "...")` sets the key explicitly.
 *
 * Everything that declares the same key shares one setting, e.g. two instances of the same class. Declaring a key
 * again with a different type or default fails, since that means two different settings collide.
 *
 * The text uses the Java properties format: `key = value` lines and `#` comments. One text can serve several robots,
 * with sections for each: settings before the first section are every robot's, and a line like `[betaDecode]` starts
 * settings only for the robot whose robot configuration (picked on the Driver Station) is named so. A section can name
 * several configurations, `[betaDecode, betaPractice]`, and `[*]` goes back to every robot's settings. A robot's own
 * value for a key overrides the value every robot has.
 */
object Config {
    internal class Snapshot(
        val text: String,
        val values: Map<String, String>,
        /** For values from a robot's section, the section's names as written; values every robot shares have none */
        val sections: Map<String, String>,
        val robotConfiguration: String?,
        val version: Int,
    )

    @Volatile
    internal var snapshot = Snapshot("", emptyMap(), emptyMap(), null, 0)
        private set

    private val declarations = ConcurrentHashMap<String, Declaration<*>>()

    // Keys declared in this round: since the robot of the current opmode started being built
    private val declaredThisRound = ConcurrentHashMap.newKeySet<String>()
    private val types = ConcurrentHashMap<KClass<*>, ConfigType<*>>(standardConfigTypes())

    /** The season's field, needed to read tile codes like `F6BL` in [Point][nl.beargineers.platform.geometry.Point] and [Pose][nl.beargineers.platform.geometry.Pose] values */
    @Volatile
    var field: Field? = null

    /** The config text in effect */
    val text: String get() = snapshot.text

    /** The name of the robot configuration whose sections of the text are in effect, if any */
    val robotConfiguration: String? get() = snapshot.robotConfiguration

    /**
     * Replaces all values with the ones from [text]: those every robot shares, and those of [robotConfiguration]'s
     * sections (see [Config]).
     *
     * If a value in [text] can't be read for a declared setting, nothing changes and [ConfigException] lists every such value.
     */
    @Synchronized
    fun load(text: String, robotConfiguration: String? = null) {
        val values = mutableMapOf<String, String>()
        val sections = mutableMapOf<String, String>()
        for (part in sectionsOf(text)) {
            if (part.names != null && robotConfiguration !in part.names) continue
            val partValues = parseProperties(part.text)
            values += partValues
            for (key in partValues.keys) {
                if (part.label == null) sections -= key else sections[key] = part.label
            }
        }
        val invalid = declarations.values.mapNotNull { declaration -> values[declaration.key]?.let(declaration::check) }
        if (invalid.isNotEmpty()) throw ConfigException(invalid)
        snapshot = Snapshot(text, values, sections, robotConfiguration, snapshot.version + 1)
    }

    /** Settings declared by the code created so far, sorted by key */
    fun declarations(): List<Declaration<*>> = declarations.values.sortedBy { it.key }

    /** The value [key] has in the config text, as written there, or null if the text doesn't set it */
    fun valueText(key: String): String? = snapshot.values[key]

    /** The section [key]'s value comes from, like `[betaDecode]`; null for a value every robot shares, or none */
    fun valueSection(key: String): String? = snapshot.sections[key]

    /**
     * Human-readable problems with the current config: values that can't be read, required settings without a value,
     * and keys that no declared setting uses, which usually are typos. Ask after the robot is fully created,
     * otherwise settings of code that doesn't exist yet show up as unused.
     */
    fun problems(): List<String> {
        val values = snapshot.values
        val invalid = declarations().mapNotNull { declaration ->
            val raw = values[declaration.key]
            when {
                raw != null -> declaration.check(raw)
                declaration.isRequired -> "${declaration.key} is not set and has no default"
                else -> null
            }
        }
        val unused = (values.keys - declarations.keys).sorted().map { key ->
            "$key is set but nothing uses it" + (suggestionFor(key)?.let { ". Did you mean $it?" } ?: "")
        }
        return invalid + unused
    }

    /** Teaches config to read values of type [T] */
    inline fun <reified T : Any> registerType(type: ConfigType<T>) = registerType(T::class, type)

    fun <T : Any> registerType(kClass: KClass<T>, type: ConfigType<T>) {
        types[kClass] = type
    }

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> typeOf(kClass: KClass<T>): ConfigType<T> {
        types[kClass]?.let { return it as ConfigType<T> }
        if (kClass.java.isEnum) return enumConfigType(kClass).also { types[kClass] = it }
        throw IllegalArgumentException(
            "Config can't read ${kClass.java.simpleName} values. Teach it with Config.registerType()"
        )
    }

    /**
     * Starts a new round of declarations, as each opmode builds its own robot. A key declared again in a new round
     * takes the new declaration, so the robots of different opmodes may give a setting different defaults. Within a
     * round, declarations of a key must agree.
     */
    internal fun newRound() {
        declaredThisRound.clear()
    }

    internal fun <T : Any> declare(key: String, type: ConfigType<T>, default: T?, optional: Boolean): Declaration<T> {
        val declaration = Declaration(key, type, default, optional)
        if (declaredThisRound.add(key)) {
            declarations[key] = declaration
            return declaration
        }
        val previous = declarations.getValue(key)

        // Declaring a key again in a round shares the setting, like another instance of the same class. Disagreeing
        // about what the setting is means two different things collide.
        val difference = when {
            previous.type != type -> "types ${previous.type.name} and ${type.name}"
            previous.default != default -> "defaults ${previous.defaultText} and ${declaration.defaultText}"
            previous.optional != optional -> "declarations: one optional, one not"
            else -> return declaration
        }
        error("Config key $key is declared twice with different $difference. Give one of them another key or ConfigScope.")
    }

    internal fun keyFor(owner: Any?, name: String): String {
        val scope = when (owner) {
            null -> return name
            is ConfigScope -> {
                @Suppress("SENSELESS_COMPARISON") // it is null while the owner's constructor hasn't set it yet
                check(owner.configScope != null) {
                    "${owner.javaClass.simpleName}.configScope must be set before config property '$name' is declared. " +
                        "Pass it through the constructor or make it a getter."
                }
                owner.configScope
            }
            else -> scopeOf(owner.javaClass)
        }
        return "$scope.$name"
    }

    private fun scopeOf(javaClass: Class<*>): String {
        val declaringClass = javaClass.declaringClass
        return when {
            javaClass.simpleName == "Companion" && declaringClass != null -> declaringClass.simpleName
            javaClass.simpleName.isEmpty() -> javaClass.name.substringAfterLast('.')
            else -> javaClass.simpleName
        }
    }

    private fun suggestionFor(key: String): String? {
        val keys = declarations.keys
        val name = key.substringAfterLast('.')
        return keys.firstOrNull { it.equals(key, ignoreCase = true) }
            ?: keys.firstOrNull { it.substringAfterLast('.').equals(name, ignoreCase = true) }
            ?: keys.map { it to editDistance(it.lowercase(), key.lowercase()) }
                .filter { (_, distance) -> distance <= 2 }
                .minByOrNull { (_, distance) -> distance }
                ?.first
    }

    /** How many single letters have to be added, removed or replaced to turn [a] into [b] */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val replace = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, replace)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** A part of the text: every robot's settings ([names] null), or a section's */
    private class Part(val names: Set<String>?, val label: String?, val text: String)

    private val sectionHeader = Regex("""^\s*\[(.*)]\s*$""")

    private fun sectionsOf(text: String): List<Part> {
        val parts = mutableListOf<Part>()
        var names: Set<String>? = null
        var label: String? = null
        val lines = StringBuilder()
        fun finish() {
            if (lines.isNotBlank()) parts += Part(names, label, lines.toString())
            lines.clear()
        }
        for (line in text.lineSequence()) {
            val header = sectionHeader.matchEntire(line)
            if (header == null) {
                lines.append(line).append('\n')
                continue
            }
            finish()
            val listed = header.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (listed.isEmpty()) throw ConfigException(listOf("Section ${line.trim()} names no robot configuration; [*] is every robot's"))
            names = if (listed == listOf("*")) null else listed.toSet()
            label = if (names == null) null else "[${listed.joinToString(", ")}]"
        }
        finish()
        return parts
    }

    private fun parseProperties(text: String): Map<String, String> {
        val properties = Properties()
        try {
            properties.load(StringReader(text))
        } catch (e: IllegalArgumentException) {
            throw ConfigException(listOf("Config text can't be read: ${e.message}"))
        }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }
    }
}

/**
 * Implement to choose the scope of config keys declared in a class, instead of the class name. Instances with
 * different scopes get separate settings: `LimelightCam("left")` and `LimelightCam("right")`.
 */
interface ConfigScope {
    val configScope: String
}

/** A setting declared by the code */
class Declaration<T : Any> internal constructor(
    val key: String,
    val type: ConfigType<T>,
    val default: T?,
    /** Whether the setting may be left out, see [optionalConfig] */
    val optional: Boolean,
) {
    /** The default written the way the config text would have it, or null for settings without a default */
    val defaultText: String? get() = default?.let(type.format)

    /** Whether the config text has to set it: there is no default, and it isn't optional */
    val isRequired: Boolean get() = default == null && !optional

    /** The value in [values], else the default; null only for optional settings */
    internal fun valueIn(values: Map<String, String>): T? {
        val raw = values[key] ?: return when {
            isRequired -> throw ConfigException(listOf("$key is not set and has no default"))
            else -> default
        }
        return try {
            type.parse(raw)
        } catch (e: Exception) {
            throw ConfigException(listOf(problem(raw, e)))
        }
    }

    internal fun check(raw: String): String? = try {
        type.parse(raw)
        null
    } catch (e: Exception) {
        problem(raw, e)
    }

    private fun problem(raw: String, e: Exception) =
        "$key = $raw: ${e.message}" + if ('#' in raw) " (comments must be on their own line)" else ""
}

/** Config text that can't be used, with one [problem][problems] per line in the message */
class ConfigException(val problems: List<String>) : IllegalArgumentException(problems.joinToString("\n"))
