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

/**
 * Declares a setting that may be left out: `val cameraOffset by optionalConfig<Distance>()` is null unless the config
 * text sets it, or [default] when that is given. The key works as for [config].
 */
inline fun <reified T : Any> optionalConfig(default: T? = null, key: String? = null): OptionalConfigDelegateProvider<T> =
    OptionalConfigDelegateProvider(Config.typeOf(T::class), default, key)

class ConfigDelegateProvider<T : Any> @PublishedApi internal constructor(
    private val type: ConfigType<T>,
    private val default: T?,
    private val key: String?,
) : PropertyDelegateProvider<Any?, ConfigValue<T>> {
    override operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ConfigValue<T> {
        val declaration = Config.declare(key ?: Config.keyFor(thisRef, property.name), type, default, optional = false)
        // A required setting without a value throws in valueIn rather than returning null
        return ConfigValue(declaration.key) { values -> declaration.valueIn(values)!! }
    }
}

class OptionalConfigDelegateProvider<T : Any> @PublishedApi internal constructor(
    private val type: ConfigType<T>,
    private val default: T?,
    private val key: String?,
) : PropertyDelegateProvider<Any?, ConfigValue<T?>> {
    override operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ConfigValue<T?> {
        val declaration = Config.declare(key ?: Config.keyFor(thisRef, property.name), type, default, optional = true)
        return ConfigValue(declaration.key) { values -> declaration.valueIn(values) }
    }
}

/** The current value of one setting. It reads the config text again only after the text changes. */
class ConfigValue<V> internal constructor(
    val key: String,
    private val read: (values: Map<String, String>) -> V,
) : ReadOnlyProperty<Any?, V> {
    private class Cached<V>(val version: Int, val value: V)

    @Volatile
    private var cached: Cached<V>? = null

    val value: V
        get() {
            val snapshot = Config.snapshot
            cached?.let { if (it.version == snapshot.version) return it.value }
            val value = read(snapshot.values)
            cached = Cached(snapshot.version, value)
            return value
        }

    override fun getValue(thisRef: Any?, property: KProperty<*>): V = value
}
