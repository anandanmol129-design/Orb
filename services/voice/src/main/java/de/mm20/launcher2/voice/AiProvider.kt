package de.mm20.launcher2.voice

/**
 * AI providers for the cloud voice NLU. All of these speak the OpenAI chat-completions wire
 * format (the Anthropic and Gemini options use each vendor's OpenAI-compatibility endpoint), so
 * selecting one simply fills in the endpoint and a sensible default model.
 */
enum class AiProvider(
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
) {
    OpenAi("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    Anthropic("Anthropic (Claude)", "https://api.anthropic.com/v1", "claude-haiku-4-5"),
    Gemini("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.0-flash"),
    Groq("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    OpenRouter("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    Mistral("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"),
    DeepSeek("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    Ollama("Ollama (local)", "http://localhost:11434/v1", "llama3.2"),
    Custom("Custom", "", ""),
    ;

    companion object {
        fun fromName(name: String?): AiProvider =
            entries.firstOrNull { it.name == name } ?: Custom
    }
}
