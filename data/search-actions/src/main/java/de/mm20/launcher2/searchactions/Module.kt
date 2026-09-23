package de.mm20.launcher2.searchactions

import de.mm20.launcher2.backup.Backupable
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

val searchActionsModule = module {
    single<SearchActionRepository> { SearchActionRepositoryImpl(androidContext(), get()) }
    single<Backupable>(named<SearchActionRepository>()) { get<SearchActionRepository>() }
    single<SearchActionService> { SearchActionServiceImpl(androidContext(), get(), TextClassifierImpl()) }
}
