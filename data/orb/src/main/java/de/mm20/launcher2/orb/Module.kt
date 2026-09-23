package de.mm20.launcher2.orb

import de.mm20.launcher2.agent.AgentPlugin
import de.mm20.launcher2.orb.board.BoardRepository
import de.mm20.launcher2.orb.board.BoardRepositoryImpl
import de.mm20.launcher2.orb.board.BoardPlugin
import de.mm20.launcher2.orb.calendar.CalendarRepository
import de.mm20.launcher2.orb.calendar.CalendarRepositoryImpl
import de.mm20.launcher2.orb.calendar.CalendarPlugin
import de.mm20.launcher2.orb.pie.PieRepository
import de.mm20.launcher2.orb.tracker.TrackerRepository
import de.mm20.launcher2.orb.tracker.TrackerRepositoryImpl
import de.mm20.launcher2.orb.tracker.TrackerPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Binds the launcher's own feature database, repositories and agent plugins.
 *
 * Adding a new first-party feature means adding a repository plus an [AgentPlugin]
 * binding here; nothing else in the app needs to change.
 */
val orbModule = module {
    single { OrbDatabase.getInstance(androidContext()) }

    single<CalendarRepository> { CalendarRepositoryImpl(get<OrbDatabase>().calendarDao()) }
    single<BoardRepository> {
        BoardRepositoryImpl(
            get<OrbDatabase>().boardColumnDao(),
            get<OrbDatabase>().boardTaskDao(),
        )
    }
    single<TrackerRepository> { TrackerRepositoryImpl(get<OrbDatabase>().timeSessionDao()) }

    single { PieRepository(androidContext()) }

    // Seed defaults once, off the main thread, as soon as Koin starts.
    single(createdAtStart = true) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            get<CalendarRepository>().ensureDefaultCalendar()
            get<BoardRepository>().ensureDefaultColumns()
        }
        Unit
    }

    single<AgentPlugin> { CalendarPlugin(get()) }
    single<AgentPlugin> { BoardPlugin(get()) }
    single<AgentPlugin> { TrackerPlugin(get()) }
}
