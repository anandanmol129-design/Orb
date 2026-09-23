package de.mm20.launcher2.widgets

import de.mm20.launcher2.backup.Backupable
import org.koin.core.qualifier.named
import org.koin.dsl.module

val widgetsModule = module {
    single<WidgetRepository> { WidgetRepositoryImpl(get()) }
    single<Backupable>(named<WidgetRepository>()) { get<WidgetRepository>() }
}
