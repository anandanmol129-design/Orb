package de.mm20.launcher2.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import de.mm20.launcher2.agent.AgentRegistry
import de.mm20.launcher2.agent.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Orchestrates the hybrid voice pipeline:
 *
 *   mic -> on-device speech-to-text -> local parser -> cloud tool-calling -> agent tools
 *
 * The controller owns no feature logic: it only turns utterances into [AgentRegistry]
 * tool calls and exposes its progress as [VoiceUiState] for the sphere to render.
 */
class VoiceController(
    private val context: Context,
    private val registry: AgentRegistry,
    private val settings: VoiceSettings,
    private val localParser: LocalIntentParser,
    private val cloudNlu: CloudNlu,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Normalised mic level (0..1) sampled while listening; drives the waveform pill. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** Live partial transcript of what the user is saying. */
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** Incremented per listening session so stale callbacks from an old session are ignored. */
    private var activeSession = 0

    /** Set while we are waiting for a spoken yes/no. */
    private var confirmation: ToolInvocation? = null

    /** Pending finalize that fires after [SILENCE_TIMEOUT_MS] of a flat transcript. */
    private var silenceJob: Job? = null

    private val speaker = Speaker(context)

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = VoiceUiState.Error("Microphone permission is required.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceUiState.Error("Speech recognition isn't available on this device.")
            return
        }
        scope.launch {
            speaker.stop()
            _transcript.value = ""
            _amplitude.value = 0f
            _state.value = VoiceUiState.Listening
            startRecognizer()
        }
    }

    /** Clears a lingering success/error state once the UI has shown it. */
    fun acknowledge() {
        val current = _state.value
        if (current is VoiceUiState.Success || current is VoiceUiState.Error) {
            _state.value = VoiceUiState.Idle
        }
    }

    /** Aborts any in-flight recognition and returns to idle. */
    fun cancel() {
        confirmation = null
        silenceJob?.cancel()
        silenceJob = null
        activeSession++
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        _state.value = VoiceUiState.Idle
    }

    /**
     * Runs a single tool call directly. Used by the UI and other agents that already know
     * the intent and don't need speech recognition.
     */
    suspend fun invoke(name: String, args: JsonObject): ToolResult = registry.invoke(name, args)

    private suspend fun startRecognizer() {
        silenceJob?.cancel()
        silenceJob = null
        val existing = recognizer
        if (existing == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            // Reuse the recognizer, but give the previous session a moment to fully
            // release the audio stack before starting a new one.
            existing.cancel()
            delay(300)
        }
        val session = ++activeSession
        recognizer?.setRecognitionListener(recognitionListener(session))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Finalize right after a second of quiet instead of waiting out the
            // recognizer's much longer default end-of-speech timeout.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MS,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MS,
            )
        }
        recognizer?.startListening(intent)
    }

    /**
     * (Re)arms the end-of-utterance timer. Called whenever the transcript grows; when it
     * stops growing the user is done talking and we finish the utterance after
     * [SILENCE_TIMEOUT_MS].
     */
    private fun armSilenceWatchdog() {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(SILENCE_TIMEOUT_MS)
            finalizeUtterance()
        }
    }

    /**
     * Wraps up the current utterance once the user has gone quiet. Asks the recognizer to
     * finalize with the full audio it has captured so far (rather than the latest partial),
     * so nothing the user said is lost.
     */
    private fun finalizeUtterance() {
        silenceJob = null
        if (_transcript.value.isBlank()) return
        _amplitude.value = 0f
        recognizer?.stopListening()
    }

    private fun recognitionListener(session: Int) = object : RecognitionListener {
        private fun current(): Boolean = session == activeSession

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (current()) {
                // The recognizer's own RMS (roughly -2..10 dB) drives the waveform, so no
                // second mic capture is needed (a concurrent AudioRecord preempts the
                // recognizer and silences it).
                _amplitude.value = ((rmsdB + 2f) / 14f).coerceIn(0f, 1f)
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (current()) {
                silenceJob?.cancel()
                silenceJob = null
                _amplitude.value = 0f
            }
        }

        override fun onError(error: Int) {
            if (!current()) return
            silenceJob?.cancel()
            silenceJob = null
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Microphone permission is required."

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service is offline."

                else -> "Sorry, I couldn't listen just now."
            }
            _state.value = VoiceUiState.Error(message)
        }

        override fun onResults(results: Bundle?) {
            if (!current()) return
            silenceJob?.cancel()
            silenceJob = null
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            android.util.Log.d("OrbTranscript", "onResults text=$text")
            if (text.isNullOrEmpty()) {
                _state.value = VoiceUiState.Error("I didn't catch that.")
                return
            }
            _transcript.value = text
            handleTranscript(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!current()) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.lastOrNull()
                ?.trim()
                ?: partialResults
                    ?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)
                    ?.lastOrNull()
                    ?.trim()
            android.util.Log.d("OrbTranscript", "onPartialResults text=$text")
            if (!text.isNullOrEmpty() && text != _transcript.value) {
                _transcript.value = text
                // The transcript is still growing: the user is mid-command. Hold off the
                // end-of-utterance timer until they pause.
                armSilenceWatchdog()
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleTranscript(text: String) {
        val pending = confirmation
        if (pending != null) {
            when {
                isAffirmative(text) -> {
                    confirmation = null
                    val confirmedArgs = JsonObject(
                        pending.args + ("confirmed" to JsonPrimitive(true))
                    )
                    scope.launch { execute(pending.copy(args = confirmedArgs)) }
                }

                isNegative(text) -> {
                    confirmation = null
                    _state.value = VoiceUiState.Idle
                    speaker.speak("Okay, cancelled.")
                }

                else -> {
                    speaker.speak("Please say yes or no.")
                    startListening()
                }
            }
            return
        }
        scope.launch { interpretAndExecute(text) }
    }

    private suspend fun interpretAndExecute(text: String) {
        _state.value = VoiceUiState.Thinking
        val local = localParser.parse(text)
        android.util.Log.d("OrbVoice", "interpret text='$text' local=$local")
        val invocation = local ?: when (val nlu = cloudNlu.interpret(text, registry.toolSpecs())) {
            is NluResult.Call -> nlu.invocation
            is NluResult.Failure -> {
                _state.value = VoiceUiState.Error(nlu.message)
                return
            }

            NluResult.NoMatch -> null
        }

        if (invocation == null) {
            val message = if (settings.cloudEnabled && settings.isCloudReady) {
                "I didn't understand that."
            } else {
                "I can open apps, set timers and alarms, toggle the flashlight, and search. " +
                    "Enable cloud commands for more."
            }
            _state.value = VoiceUiState.Error(message)
            speaker.speak(message)
            return
        }
        execute(invocation)
    }

    private suspend fun execute(invocation: ToolInvocation) {
        _state.value = VoiceUiState.Thinking
        android.util.Log.d("OrbVoice", "invoke name=${invocation.name} args=${invocation.args}")
        when (val result = registry.invoke(invocation.name, invocation.args)) {
            is ToolResult.Success -> {
                android.util.Log.d("OrbVoice", "result=Success ${result.message}")
                _state.value = VoiceUiState.Success(result.message)
                result.message?.let { speaker.speak(it) }
            }

            is ToolResult.Error -> {
                android.util.Log.d("OrbVoice", "result=Error ${result.message}")
                _state.value = VoiceUiState.Error(result.message)
                speaker.speak(result.message)
            }

            is ToolResult.ConfirmationRequired -> {
                android.util.Log.d("OrbVoice", "result=Confirm ${result.message}")
                confirmation = invocation
                speaker.speak(result.message)
                startListening()
            }
        }
    }

    private companion object {
        /** How long to wait after the transcript stops growing before finishing the utterance. */
        private const val SILENCE_TIMEOUT_MS = 1_000L
    }
}

/** Small wrapper so we can speak confirmations without leaking TextToSpeech everywhere. */
private class Speaker(context: Context) {
    private var tts: TextToSpeech? = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            // Locale is best-effort; the default engine voice is used otherwise.
        }
    }.apply {
        language = Locale.getDefault()
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orb-${System.nanoTime()}")
    }

    fun stop() {
        tts?.stop()
    }
}
