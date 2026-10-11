package de.mm20.launcher2.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A single callable capability exposed by an [AgentPlugin].
 *
 * Tools are the only contract between agents (voice, LLM, UI, other plugins) and
 * feature code. A feature must never be reachable in a way that bypasses its tools,
 * so that every action is discoverable and auditable.
 *
 * @param name globally unique, namespaced name, e.g. `orb.apps.open`.
 * @param description human/LLM readable description of what the tool does.
 * @param parameters JSON Schema object describing the accepted arguments.
 * @param requiresConfirmation when true, the host must confirm with the user before invoking.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject = JsonObject(emptyMap()),
    val requiresConfirmation: Boolean = false,
)

/**
 * Result of invoking a tool.
 */
sealed class ToolResult {
    /**
     * @param data structured payload for follow-up reasoning or rendering.
     * @param message optional human-readable summary suitable for TTS.
     */
    data class Success(
        val data: JsonObject = JsonObject(emptyMap()),
        val message: String? = null,
    ) : ToolResult()

    data class Error(val message: String) : ToolResult()

    /**
     * The tool ran but needs an explicit confirmation before it is committed.
     * The host should surface [message] and, if accepted, re-invoke the tool with
     * `confirmed = true` in the arguments.
     */
    data class ConfirmationRequired(val message: String) : ToolResult()
}

/**
 * A pluggable feature exposed to agents.
 *
 * Plugins (such as the built-in launcher tools) and future plugins implement this
 * interface and register themselves in Koin. The agent runtime only knows about
 * [tools] and [invoke], so plugins can be added or upgraded without touching the
 * voice/LLM layer.
 */
interface AgentPlugin {
    /** Stable, globally unique id, e.g. `orb.launcher`. Must not change once shipped. */
    val id: String

    /** Monotonically increasing schema/behaviour version for the plugin. */
    val version: Int
        get() = 1

    /** Optional human-readable name for settings/UI. */
    val label: String
        get() = id

    fun tools(): List<ToolSpec>

    /**
     * Execute [name] with [args]. Implementations must validate their own arguments
     * and must be safe to call from a background dispatcher.
     *
     * [args] may include a boolean `confirmed` key when the user has confirmed a
     * previously returned [ToolResult.ConfirmationRequired].
     */
    suspend fun invoke(name: String, args: JsonObject): ToolResult
}

/**
 * Small helpers to make building and reading JSON tool arguments ergonomic.
 */
object ToolArgs {
    fun of(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> put(key, kotlinx.serialization.json.JsonNull)
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is Float -> put(key, value)
                is kotlinx.serialization.json.JsonElement -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }
}

fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.stringOrNull(key: String): String? = string(key)

fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.childObject(key: String): JsonObject? =
    (this[key] as? JsonObject)?.jsonObject

/**
 * Minimal JSON Schema builders so plugins can describe their parameters without
 * hand-rolling raw maps.
 */
object Schema {
    fun string(description: String, required: Boolean = false): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (required) put("required", true)
    }

    fun integer(description: String, required: Boolean = false): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (required) put("required", true)
    }

    fun number(description: String, required: Boolean = false): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", description)
        if (required) put("required", true)
    }

    fun boolean(description: String, required: Boolean = false): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        if (required) put("required", true)
    }

    /**
     * Build a JSON Schema `object` from named properties. Properties marked with
     * `required = true` are collected into the schema's `required` array.
     */
    fun obj(vararg properties: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        val props = buildJsonObject {
            val required = mutableListOf<String>()
            properties.forEach { (name, schema) ->
                val cleaned = buildJsonObject {
                    schema.forEach { (k, v) ->
                        if (k != "required") put(k, v)
                    }
                }
                put(name, cleaned)
                if (schema["required"].let { it != null && it.toString() == "true" }) {
                    required.add(name)
                }
            }
        }
        put("type", "object")
        put("properties", props)
        val required = properties.filter { it.second["required"]?.toString() == "true" }.map { it.first }
        if (required.isNotEmpty()) {
            put(
                "required",
                kotlinx.serialization.json.buildJsonArray {
                    required.forEach { add(JsonPrimitive(it)) }
                }
            )
        }
    }
}
