package de.mm20.launcher2.agent

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Provides the [AgentRegistry] pre-populated with every [AgentPlugin] bound in Koin.
 *
 * Feature modules only need to add `single<AgentPlugin> { MyPlugin(...) }` to their own
 * Koin module; no changes to the application or the voice layer are required.
 */
val agentModule: Module = module {
    single { AgentRegistry(getAll()) }
}
