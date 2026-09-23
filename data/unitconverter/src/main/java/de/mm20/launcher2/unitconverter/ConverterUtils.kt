package de.mm20.launcher2.unitconverter

import android.content.Context
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal object ConverterUtils {

    private val formats = ThreadLocal.withInitial { HashMap<String, DecimalFormat>() }

    private fun decimalFormat(pattern: String): DecimalFormat =
        formats.get().getOrPut("$pattern#${Locale.getDefault().toLanguageTag()}") {
            DecimalFormat(pattern)
        }

    fun formatName(context: Context, unit: MeasureUnit, value: Double): String {
        return unit.formatName(context, value)
    }

    fun formatValue(context: Context, unit: MeasureUnit, value: Double): String {
        if (abs(value) > 1e5 || abs(value) < 1e-3) {
            return decimalFormat("#.###E0").format(value)
        }

        return decimalFormat("#.###").format(value)

    }

}