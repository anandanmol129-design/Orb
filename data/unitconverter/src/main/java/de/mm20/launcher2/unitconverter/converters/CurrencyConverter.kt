package de.mm20.launcher2.unitconverter.converters

import android.content.Context
import android.icu.text.PluralRules
import android.icu.util.Currency
import de.mm20.launcher2.currencies.CurrencyRepository
import de.mm20.launcher2.search.data.CurrencyUnitConverter
import de.mm20.launcher2.search.data.UnitConverter
import de.mm20.launcher2.unitconverter.Dimension
import de.mm20.launcher2.unitconverter.MeasureUnit
import de.mm20.launcher2.unitconverter.UnitValue
import java.text.DecimalFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import java.util.Currency as JCurrency

class CurrencyConverter(
    private val repository: CurrencyRepository,
    private val preferredCurrencies: List<String>,
) : Converter {

    override val dimension: Dimension = Dimension.Currency

    private val topCurrencies = arrayOf("USD", "EUR", "JPY", "GBP", "AUD")

    private val decimalFormats = ThreadLocal.withInitial { HashMap<String, DecimalFormat>() }

    private val nameCache = ConcurrentHashMap<String, String>()

    private fun decimalFormat(pattern: String): DecimalFormat =
        decimalFormats.get().getOrPut("$pattern#${Locale.getDefault().toLanguageTag()}") {
            DecimalFormat(pattern)
        }

    override suspend fun isValidUnit(symbol: String): Boolean {
        return repository.isValidCurrency(symbol)
    }


    private fun formatName(symbol: String, value: Double): String {
        val currency = Currency.getInstance(symbol) ?: return formatNameFallback(symbol)
        val locale = Locale.getDefault()
        val pluralCount = PluralRules.forLocale(locale).select(value)
        return nameCache.getOrPut("$symbol#$pluralCount#${locale.toLanguageTag()}") {
            val text = StringBuilder()
            text.append(
                currency.getName(
                    locale,
                    Currency.PLURAL_LONG_NAME,
                    pluralCount,
                    booleanArrayOf(false)
                )
            )
            text.toString()
        }
    }

    private fun formatNameFallback(symbol: String): String {
        return symbol
    }

    private fun formatValue(symbol: String, value: Double): String {
        if (abs(value) > 1e10) {
            return decimalFormat("#.###E0").format(value)
        }
        val currency = JCurrency.getInstance(symbol)
        return if (currency != null) {
            val format = StringBuilder("#,##0")
            val digits = currency.defaultFractionDigits
            if (digits > 0) format.append(".")
            for (i in 0 until digits) format.append("0")
            decimalFormat(format.toString()).format(value)
        } else {
            decimalFormat("#,##0.00").format(value)
        }
    }


    override suspend fun convert(
        context: Context,
        fromUnit: String,
        value: Double,
        toUnit: String?
    ): UnitConverter {
        val fromIsoCode = repository.resolveAlias(fromUnit)
        val toIsoCode = toUnit?.let { repository.resolveAlias(it) }

        val values = repository.convertCurrency(fromIsoCode, value, toIsoCode).map {
            UnitValue(
                it.second,
                it.first,
                formatName(it.first, it.second),
                formatValue(it.first, it.second)
            )
        }.toMutableList()

        val ownCurrencySymbols = preferredCurrencies.ifEmpty {
            val defaultCurrencySymbols = mutableListOf<String>()
            val localeList = context.resources.configuration.locales
            for (i in 0 until localeList.size()) {
                val locale = localeList[i]
                val currency = try {
                    JCurrency.getInstance(locale)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (currency != null) {
                    defaultCurrencySymbols.add(currency.currencyCode)
                }
            }
            defaultCurrencySymbols.distinct()
        }
        val topCurrencies = ownCurrencySymbols + topCurrencies

        values.sortBy {
            val i = topCurrencies.indexOf(it.symbol)
            if (i != -1) return@sortBy ("000$i").takeLast(4)
            it.formattedName
        }

        val inputValue = UnitValue(
            value,
            fromIsoCode,
            formatName(fromIsoCode, value),
            formatValue(fromIsoCode, value)
        )
        val lastUpdate = repository.getLastUpdate(fromIsoCode)
        return CurrencyUnitConverter(dimension, inputValue, values, lastUpdate)
    }

    override suspend fun getSupportedUnits(): List<MeasureUnit> {
        val currencies = repository.getKnownUnits()
        return currencies.map {
            CurrencyUnit(
                symbol = it,
            )
        }
    }
}

internal data class CurrencyUnit(
    override val symbol: String,
) : MeasureUnit {
    override fun formatName(context: Context, value: Double): String {
        val text = StringBuilder()
        val currency = try {
            Currency.getInstance(symbol)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return symbol
        val pluralCount = PluralRules.forLocale(Locale.getDefault()).select(value)
        text.append(
            currency.getName(
                Locale.getDefault(),
                Currency.LONG_NAME,
                pluralCount,
                booleanArrayOf(false)
            )
        )

        return text.toString()
    }
}
