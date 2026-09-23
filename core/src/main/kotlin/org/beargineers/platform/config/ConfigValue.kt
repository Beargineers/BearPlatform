package org.beargineers.platform.config

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Declares a setting read from [Config]: `val kP by config(0.02)`.
 *
 * Without a [default] the config text must provide the value: `val goal by config<Point>()`.
 * The key is `<scope>.<property name>` (see [Config]); pass [key] to choose it yourself.
 */
inline fun <reified T : Any> config(default: T? = null, key: String? = null): ConfigDelegateProvider<T> =
    ConfigDelegateProvider(Config.typeOf(T::class), default, key)

class ConfigDelegateProvider<T : Any> @PublishedApi internal constructor(
    private val type: ConfigType<T>,
    private val default: T?,
    private val key: String?,
) : PropertyDelegateProvider<Any?, ConfigValue<T>> {
    override operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ConfigValue<T> =
        ConfigValue(Config.declare(key ?: Config.keyFor(thisRef, property.name), type, default))
}

/** The current value of one setting. It reads the config text again only after the text changes. */
class ConfigValue<T : Any> internal constructor(private val declaration: Declaration<T>) : ReadOnlyProperty<Any?, T> {
    private class Cached<T>(val version: Int, val value: T)

    @Volatile
    private var cached: Cached<T>? = null

    val key: String get() = declaration.key

    val value: T
        get() {
            val snapshot = Config.snapshot
            cached?.let { if (it.version == snapshot.version) return it.value }
            val value = declaration.valueIn(snapshot.values)
            cached = Cached(snapshot.version, value)
            return value
        }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
}
