package de.mm20.launcher2.voice

import de.mm20.launcher2.agent.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

sealed class NluResult {
    data class Call(val invocation: ToolInvocation) : NluResult()
    data object NoMatch : NluResult()
    data class Failure(val message: String) : NluResult()
}

/**
 * Optional cloud NLU using any OpenAI-compatible chat-completions API with function
 * (tool) calling. The model chooses among the tools registered in the [AgentRegistry];
 * it never mutates state itself.
 */
class CloudNlu(
    private val settings: VoiceSettings,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun interpret(
        transcript: String,
        tools: List<ToolSpec>,
    ): NluResult = withContext(Dispatchers.IO) {
        if (!settings.isCloudReady) return@withContext NluResult.NoMatch

        val nameMap = tools.associate { llmFunctionName(it.name) to it.name }

        val body = buildJsonObject {
            put("model", settings.model)
            put("temperature", 0)
            put("tool_choice", "auto")
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", SYSTEM_PROMPT)
                        }
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", transcript)
                        }
                    )
                }
            )
            put(
                "tools",
                buildJsonArray {
                    tools.forEach { spec ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", llmFunctionName(spec.name))
                                        put("description", spec.description)
                                        put("parameters", spec.parameters)
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }

        try {
            val response = post(body.toString())
            parseResponse(response, nameMap)
        } catch (e: Exception) {
            NluResult.Failure(e.message ?: "Cloud request failed")
        }
    }

    private fun parseResponse(response: String, nameMap: Map<String, String>): NluResult {
        val root = json.parseToJsonElement(response).jsonObject
        val message = root["choices"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?: return NluResult.NoMatch

        val toolCall = message["tool_calls"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return NluResult.NoMatch

        val function = toolCall["function"]?.jsonObject ?: return NluResult.NoMatch
        val llmName = function["name"]?.jsonPrimitive?.content ?: return NluResult.NoMatch
        val originalName = nameMap[llmName] ?: return NluResult.NoMatch
        val argsText = function["arguments"]?.jsonPrimitive?.content ?: "{}"
        val args: JsonObject = try {
            json.parseToJsonElement(argsText).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
        return NluResult.Call(ToolInvocation(originalName, args))
    }

    private fun post(body: String): String {
        val base = settings.endpoint.trimEnd('/')
        val url = URL("$base/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Cloud request failed ($code)")
        }
        return text
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are Orb, the voice assistant built into an Android launcher. " +
                "Pick the single best tool for the user's request and call it. " +
                "Resolve relative dates like 'tomorrow' using the current time. " +
                "Never reply with prose; always call a tool when one applies."

        /**
         * OpenAI function names only allow letters, digits, `_` and `-`, so the dotted
         * tool ids are mapped when talking to the model and mapped back on the response.
         */
        fun llmFunctionName(name: String): String = name.replace('.', '_')
    }
}
