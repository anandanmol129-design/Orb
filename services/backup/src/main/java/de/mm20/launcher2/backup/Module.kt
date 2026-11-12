package de.mm20.launcher2.backup

import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * SharedPreferences files that hold user state outside the settings data store and the database.
 * Keep in sync with the `PREFS` constants of their owners:
 * - orb_pie: PieRepository (pie menu items, orb position, size, shape and fill)
 * - orb_voice: VoiceSettings (assistant provider, endpoint, model and key)
 * - orb_rss_reader: OrbFeedComponent (RSS subscriptions)
 */
private val backedUpPreferences = listOf("orb_pie", "orb_voice", "orb_rss_reader")

val backupModule = module {
    for (prefs in backedUpPreferences) {
        factory<Backupable>(named("prefs.$prefs")) { SharedPreferencesBackup(androidContext(), prefs) }
    }
    single { BackupManager(androidContext(), getAll()) }
}
