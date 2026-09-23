package de.mm20.launcher2.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Central registry for all [AgentPlugin]s in the process.
 *
 * The voice layer, the LLM tool-caller and the UI all resolve capabilities through
 * this single entry point. That guarantees that a capability added by a plugin is
 * immediately available everywhere, and that there is exactly one code path that
 * mutates feature state.
 */
class AgentRegistry(
    plugins: List<AgentPlugin>,
) {
    private val plugins: List<AgentPlugin> = plugins.distinctBy { it.id }

    private val mutex = Mutex()

    /** All registered plugins, in registration order. */
    fun plugins(): List<AgentPlugin> = plugins

    /** All tools paired with the plugin that provides them. */
    fun tools(): List<Pair<AgentPlugin, ToolSpec>> =
        plugins.flatMap { plugin -> plugin.tools().map { plugin to it } }

    /** Flat list of tool specs, suitable for injecting into an LLM prompt. */
    fun toolSpecs(): List<ToolSpec> = tools().map { it.second }

    fun find(toolName: String): Pair<AgentPlugin, ToolSpec>? =
        tools().firstOrNull { it.second.name == toolName }

    /**
     * Invoke a tool by name. Returns [ToolResult.Error] for unknown tools or when the
     * plugin throws, so callers never have to handle exceptions from plugins.
     */
    suspend fun invoke(name: String, args: JsonObject = JsonObject(emptyMap())): ToolResult {
        val (plugin, _) = find(name) ?: return ToolResult.Error("Unknown tool: $name")
        return mutex.withLock {
            try {
                plugin.invoke(name, args)
            } catch (e: Exception) {
                ToolResult.Error(e.message ?: "Tool $name failed")
            }
        }
    }
}
