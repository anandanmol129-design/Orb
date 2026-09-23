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

    val isCloudReady: Boolean
        get() = cloudEnabled && apiKey.isNotBlank()

    private companion object {
        const val PREFS = "orb_voice"
        const val KEY_CLOUD_ENABLED = "cloud_enabled"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_PROVIDER = "provider"

        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
