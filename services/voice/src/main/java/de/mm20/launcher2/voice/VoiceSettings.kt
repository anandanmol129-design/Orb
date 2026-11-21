package de.mm20.launcher2.voice

import android.content.Context

/**
 * Voice configuration. Local (on-device) parsing is always available; the cloud NLU path is
 * opt-in and requires an OpenAI-compatible endpoint and key.
 */
class VoiceSettings(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var cloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_ENABLED, value).apply()

    /** Base URL, e.g. `https://api.openai.com/v1`. */
    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT)?.ifBlank { DEFAULT_ENDPOINT }
            ?: DEFAULT_ENDPOINT
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL)?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var provider: String
        get() = prefs.getString(KEY_PROVIDER, AiProvider.OpenAi.name) ?: AiProvider.OpenAi.name
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    /** Listen for [wakePhrase] while the home screen is showing. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_ENABLED, value).apply()

    var wakePhrase: String
        get() = prefs.getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE)?.ifBlank { DEFAULT_WAKE_PHRASE }
            ?: DEFAULT_WAKE_PHRASE
        set(value) = prefs.edit().putString(KEY_WAKE_PHRASE, value).apply()

    /** Allow "call X" phone calls by voice. */
    var allowPhoneCalls: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_CALLS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_CALLS, value).apply()

    /** Allow WhatsApp calls, video calls and chats by voice. */
    var allowWhatsApp: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_WHATSAPP, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_WHATSAPP, value).apply()

    /** Place phone calls straight away (needs the Phone permission) instead of opening the dialer. */
    var callDirectly: Boolean
        get() = prefs.getBoolean(KEY_CALL_DIRECTLY, true)
        set(value) = prefs.edit().putBoolean(KEY_CALL_DIRECTLY, value).apply()

    /** How long the mic waits for the first words before closing, in milliseconds. */
    var silenceTimeoutMs: Long
        get() = prefs.getLong(KEY_SILENCE_TIMEOUT, DEFAULT_SILENCE_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_SILENCE_TIMEOUT, value).apply()

    val isCloudReady: Boolean
        get() = cloudEnabled && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_WAKE_PHRASE = "Hey Orb"
        const val DEFAULT_SILENCE_TIMEOUT_MS = 3_000L
        val SILENCE_TIMEOUT_CHOICES_MS = listOf(2_000L, 3_000L, 5_000L)

        private const val PREFS = "orb_voice"
        private const val KEY_CLOUD_ENABLED = "cloud_enabled"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_WAKE_ENABLED = "wake_enabled"
        private const val KEY_WAKE_PHRASE = "wake_phrase"
        private const val KEY_ALLOW_CALLS = "allow_phone_calls"
        private const val KEY_ALLOW_WHATSAPP = "allow_whatsapp"
        private const val KEY_CALL_DIRECTLY = "call_directly"
        private const val KEY_SILENCE_TIMEOUT = "silence_timeout_ms"

        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
