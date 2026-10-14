package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.LED
import nl.beargineers.platform.component.Component
import nl.beargineers.platform.indicator.Indicator

/** Colors of REV's red/green LED: amber is both on */
enum class LedColor(val letter: Char, internal val red: Boolean, internal val green: Boolean) {
    OFF('0', false, false),
    RED('R', true, false),
    GREEN('G', false, true),
    AMBER('A', true, true);

    companion object {
        /** The color of [letter] in a [RevLeds] state, or null if it is none */
        fun of(letter: Char): LedColor? = entries.firstOrNull { it.letter == letter }
    }
}

/**
 * One of REV's red/green LED indicators (REV-31-2010), as a part of [parent]. It takes a digital port pair, configured
 * as two LED devices: `<device>red` on the port that lights red, `<device>green` on the other. Each LED is one
 * command to the hub, so only LEDs that change are sent.
 */
class RevLed(parent: Component, name: String, device: String = name) : Component(parent, name) {
    /** The SDK devices, for anything this class doesn't cover */
    val red: LED by hardware("${device}red")
    val green: LED by hardware("${device}green")

    private var redSent: Boolean? = null
    private var greenSent: Boolean? = null

    var color: LedColor = LedColor.OFF
        set(value) {
            field = value
            if (redSent != value.red) {
                timed("led writes", counted = true) { red.enable(value.red) }
                redSent = value.red
            }
            if (greenSent != value.green) {
                timed("led writes", counted = true) { green.enable(value.green) }
                greenSent = value.green
            }
        }
}

/**
 * A row of REV's red/green LED indicators, as a part of [parent]: `val leds = RevLeds(this)` for three named `led1`,
 * `led2` and `led3` in the robot configuration, each with its red and green device (see [RevLed]).
 *
 * It shows strings of one letter per LED, R for red, G for green, A for amber and 0 for off: `"GG0"`. Parts of the
 * robot show them through signals, see [Indicator]: `leds.signal(priority = 10).show("GG0")`, or patterns like
 * `Pattern("G00" to 150.milliseconds, "0G0" to 150.milliseconds, "00G" to 150.milliseconds)`.
 */
class RevLeds(
    parent: Component,
    name: String = "leds",
    /** The LEDs' names in the robot configuration, before `red` and `green` */
    devices: List<String> = listOf("led1", "led2", "led3"),
) : Indicator<String>(parent, name, "0".repeat(devices.size)) {
    val leds: List<RevLed> = devices.map { RevLed(this, it) }

    override fun checkState(state: String) {
        require(state.length == leds.size && state.all { LedColor.of(it) != null }) {
            "${describe()} shows one letter per LED, R, G, A or 0, like \"${"G".padEnd(leds.size, '0')}\": not \"$state\""
        }
    }

    override fun display(state: String) {
        for (i in leds.indices) leds[i].color = LedColor.of(state[i])!!
    }
}
