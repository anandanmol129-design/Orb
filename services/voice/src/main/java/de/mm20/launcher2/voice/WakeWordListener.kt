package de.mm20.launcher2.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Listens for the wake phrase ("Hey Orby" by default) fully on the device, using Vosk.
 *
 * The speech model (about 40 MB) is downloaded once, on request. Listening is restricted by
 * a grammar to the wake phrase and a few look-alikes, which keeps it cheap and keeps other
 * speech from being mistaken for the phrase. The home screen starts it while it is showing
 * and stops it when it is left or while the assistant itself is using the microphone.
 */
class WakeWordListener(
    context: Context,
    private val settings: VoiceSettings,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    sealed interface ModelState {
        data object Missing : ModelState
        data class Downloading(val progress: Float) : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    private val modelDir = File(this.context.filesDir, "vosk-model")
    private val readyMarker = File(modelDir, ".ready")

    private val _modelState = MutableStateFlow<ModelState>(
        if (readyMarker.exists()) ModelState.Ready else ModelState.Missing
    )
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private var model: Model? = null
    private var service: SpeechService? = null
    private var startJob: Job? = null
    private var downloadJob: Job? = null

    /** True while the microphone is open for the wake phrase. */
    val isListening: Boolean get() = service != null

    fun downloadModel() {
        if (_modelState.value is ModelState.Downloading || _modelState.value == ModelState.Ready) return
        downloadJob = scope.launch {
            _modelState.value = ModelState.Downloading(0f)
            _modelState.value = try {
                withContext(Dispatchers.IO) { download() }
                ModelState.Ready
            } catch (e: Exception) {
                Log.w(TAG, "Model download failed", e)
                ModelState.Failed(e.message ?: "Download failed")
            }
        }
    }

    private fun download() {
        val tmpZip = File(context.cacheDir, "vosk-model.zip")
        val staging = File(context.filesDir, "vosk-model.tmp")
        staging.deleteRecursively()
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("Server replied ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: MODEL_SIZE_BYTES
            connection.inputStream.use { input ->
                tmpZip.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        _modelState.value = ModelState.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        // The archive holds one top-level folder; its contents become the model directory.
        val stagingRoot = staging.canonicalFile
        ZipInputStream(tmpZip.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val target = File(staging, relative).canonicalFile
                // Refuse entries that would land outside the model directory.
                if (!target.path.startsWith(stagingRoot.path + File.separator)) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        tmpZip.delete()
        if (!File(staging, "am").exists()) error("The downloaded model is incomplete")
        modelDir.deleteRecursively()
        if (!staging.renameTo(modelDir)) error("Couldn't install the model")
        readyMarker.createNewFile()
    }

    /** Starts listening if the wake word is on, the model is installed and the mic is allowed. */
    fun start(onWake: () -> Unit) {
        if (service != null || startJob?.isActive == true) return
        if (!settings.wakeWordEnabled || _modelState.value != ModelState.Ready) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val phrase = wakeGrammar(settings.wakePhrase)
        startJob = scope.launch {
            try {
                val loaded = model ?: withContext(Dispatchers.IO) { Model(modelDir.path) }.also { model = it }
                val recognizer = Recognizer(loaded, SAMPLE_RATE, phrase.grammarJson)
                val speech = SpeechService(recognizer, SAMPLE_RATE)
                service = speech
                speech.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        check(hypothesis, "partial")
                    }

                    override fun onResult(hypothesis: String?) {
                        check(hypothesis, "text")
                    }

                    override fun onFinalResult(hypothesis: String?) {}
                    override fun onError(exception: Exception?) {
                        Log.w(TAG, "Wake word listener failed", exception)
                        stop()
                    }

                    override fun onTimeout() {}

                    private fun check(hypothesis: String?, key: String) {
                        val heard = hypothesis?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }
                        if (heard.isNullOrEmpty() || !phrase.matches(heard)) return
                        Log.d(TAG, "Heard the wake phrase")
                        stop()
                        onWake()
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the wake word listener", e)
                stop()
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
    }

    private companion object {
        const val TAG = "OrbWakeWord"
        const val SAMPLE_RATE = 16000f
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        const val MODEL_SIZE_BYTES = 41_205_931L
    }
}

/** What the recognizer may hear, and which of it counts as the wake phrase. */
internal class WakeGrammar(val target: String, val grammarJson: String) {
    fun matches(heard: String): Boolean = " ${heard.trim()} ".contains(" $target ")
}

/**
 * Builds the grammar for [phrase]. Words the offline model doesn't know are swapped for how
 * it hears them ("Orby" comes out as "or be"); without that it would drop them. Look-alike
 * phrases are listed too, so that speech that only sounds similar is matched to them instead
 * of to the wake phrase.
 */
internal fun wakeGrammar(phrase: String): WakeGrammar {
    val words = phrase.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
    val target = words.joinToString(" ") { SOUNDS_LIKE[it] ?: it }.ifEmpty { "hey or be" }
    val options = linkedSetOf(target)
    if (target.startsWith("hey ")) {
        options += listOf("hey ruby", "hey bobby", "hey robbie", "hey there", "hey you", "hey")
    }
    options += target.substringAfter(' ', "")
    options.remove("")
    options += "[unk]"
    return WakeGrammar(target, JsonArray(options.map { JsonPrimitive(it) }).toString())
}

private val SOUNDS_LIKE = mapOf(
    "orby" to "or be",
    "orbie" to "or be",
    "orbi" to "or be",
    "orbee" to "or be",
)
