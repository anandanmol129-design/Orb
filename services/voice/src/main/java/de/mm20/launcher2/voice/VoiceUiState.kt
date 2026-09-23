package de.mm20.launcher2.voice

import kotlinx.serialization.json.JsonObject

/** A resolved intent: which tool to call and with which arguments. */
data class ToolInvocation(
    val name: String,
    val args: JsonObject,
)

/** Observable state of the voice pipeline, mapped onto the sphere's expressions. */
sealed class VoiceUiState {
    /** Nothing happening. */
    data object Idle : VoiceUiState()

    /** Microphone open, capturing speech. */
    data object Listening : VoiceUiState()

    /** Interpreting a command. */
    data object Thinking : VoiceUiState()

    /** Waiting for a spoken yes/no before committing an action. */
    data class Confirm(val message: String) : VoiceUiState()

    data class Success(val message: String?) : VoiceUiState()

    data class Error(val message: String) : VoiceUiState()
}
