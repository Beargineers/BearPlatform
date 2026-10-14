package nl.beargineers.platform.ftc

import com.qualcomm.robotcore.hardware.PwmControl

/** Sends a servo's pulse widths when they change: the SDK's 600 and 2400 µs where they aren't set */
internal class PwmRangeSetting {
    private var appliedMin: Double? = null
    private var appliedMax: Double? = null

    fun apply(device: Any, min: Double?, max: Double?) {
        if (min == appliedMin && max == appliedMax) return
        (device as? PwmControl)?.let { pwm ->
            val default = PwmControl.PwmRange.defaultRange
            pwm.pwmRange = PwmControl.PwmRange(min ?: default.usPulseLower, max ?: default.usPulseUpper)
        }
        appliedMin = min
        appliedMax = max
    }
}
