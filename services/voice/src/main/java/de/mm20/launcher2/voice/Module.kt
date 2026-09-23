package de.mm20.launcher2.voice

import de.mm20.launcher2.agent.AgentPlugin
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val voiceModule = module {
    single { VoiceSettings(androidContext()) }
    single { LocalIntentParser() }
    single { CloudNlu(get()) }
    single {
        VoiceController(
            context = androidContext(),
            registry = get(),
            settings = get(),
            localParser = get(),
            cloudNlu = get(),
        )
    }

    // Launcher-level tools participate in the same registry as feature plugins.
    single<AgentPlugin> { LauncherToolsPlugin(androidContext(), get()) }
}
