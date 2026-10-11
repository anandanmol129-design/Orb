package de.mm20.launcher2.orb

import de.mm20.launcher2.orb.pie.PieRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Binds the orb UI configuration: radial menu layout and sphere appearance.
 */
val orbModule = module {
    single { PieRepository(androidContext()) }
}
