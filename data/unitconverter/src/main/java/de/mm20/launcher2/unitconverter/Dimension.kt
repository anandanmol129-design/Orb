package de.mm20.launcher2.unitconverter

import androidx.annotation.StringRes

enum class Dimension(@StringRes val resource: Int) {
    Length(R.string.dimension_length),
    Mass(R.string.dimension_mass),
    Velocity(R.string.dimension_velocity),
    Volume(R.string.dimension_volume),
    Area(R.string.dimension_area),
    Currency(R.string.dimension_currency),
    Data(R.string.dimension_data),
    Temperature(R.string.dimension_temperature),
    Time(R.string.dimension_time),
}