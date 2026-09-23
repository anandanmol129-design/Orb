package de.mm20.launcher2.search.data

import de.mm20.launcher2.search.Searchable
import java.text.DecimalFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

data class Calculator(
    val term: String,
    val solution: Double
): Searchable {

    val formattedString: String
    val formattedBinaryString: String
    val formattedHexString: String
    val formattedOctString: String

    private val beautifiedTerm: String

    init {
        if (solution.isNaN()) {
            formattedString = "NaN"
            formattedOctString = "NaN"
            formattedBinaryString = "NaN"
            formattedHexString = "NaN"
        } else {
            val nf =
                if ((abs(solution) > 1e12 || abs(solution) < 1e-5) && solution != 0.0) decimalFormat(
                    "#.######E0"
                )
                else decimalFormat("#,###.######")
            formattedString = nf.format(solution)
            var s = StringBuffer(solution.roundToInt().toString(2))
            while (s.length % 4 != 0) {
                s = s.insert(0, '0')
            }

            for (i in s.length - 4 downTo 4 step 4) {
                s.insert(i, ' ')
            }
            formattedBinaryString = s.toString()

            s = StringBuffer(solution.roundToInt().toString(8))
            while (s.length % 3 != 0) {
                s = s.insert(0, '0')
            }

            for (i in s.length - 3 downTo 3 step 3) {
                s.insert(i, ' ')
            }
            formattedOctString = s.toString()

            s = StringBuffer(solution.roundToInt().toString(16).uppercase(Locale.getDefault()))
            while (s.length % 2 != 0) {
                s = s.insert(0, '0')
            }

            for (i in s.length - 2 downTo 2 step 2) {
                s.insert(i, ' ')
            }
            formattedHexString = s.toString()
        }
        beautifiedTerm = when {
            term.matches(HexRegex) -> term.substring(2).uppercase(Locale.ROOT) + "₁₆"
            term.matches(BinRegex) -> term.substring(2) + "₂"
            term.matches(OctRegex) -> term.substring(1) + "₈"
            else -> term.replace(WhitespaceRegex, "")
                .replace("pi", " π ", ignoreCase = true)
                .replace("*", " × ")
                .replace("-", " − ")
                .replace("/", " ∕ ")
                .replace("+", " + ")
                .replace(AmpersandsRegex, " ∧ ")
                .replace(PipesRegex, " ∨ ")
                .replace("!=", " ≠ ")
                .replace("<>", " ≠ ")
                .replace(">=", " ≥ ")
                .replace("<=", " ≤ ")
                .replace("=", " = ")
                .replace("<", " < ")
                .replace(">", " > ")
        }
    }

    fun getBeatifiedTerm(): String = beautifiedTerm

    companion object {
        private val HexRegex = Regex("0x[0-9a-fA-F]+")
        private val BinRegex = Regex("0b[01]+")
        private val OctRegex = Regex("0[0-7]+")
        private val WhitespaceRegex = Regex("\\s+")
        private val AmpersandsRegex = Regex("&{1,2}")
        private val PipesRegex = Regex("\\|{1,2}")

        private val decimalFormats = ThreadLocal.withInitial { HashMap<String, DecimalFormat>() }

        private fun decimalFormat(pattern: String): DecimalFormat =
            decimalFormats.get().getOrPut("$pattern#${Locale.getDefault().toLanguageTag()}") {
                DecimalFormat(pattern)
            }
    }
}
