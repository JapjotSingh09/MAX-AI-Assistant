package com.max.assistant.ai

import com.max.assistant.assistant.CommandIntent
import com.max.assistant.assistant.IntentValidator
import com.max.assistant.data.remote.ApiException
import com.max.assistant.data.remote.MaxApiClient
import org.json.JSONObject

data class AIRequest(val message: String, val conversationId: String?)

data class AIResponse(
    val conversationId: String?,
    val reply: String,
    // Already validated against the whitelist on the device (the backend validates too).
    val suggestedAction: CommandIntent?,
    val serverActionId: String?,
    val aiUnavailable: Boolean
)

// The app only knows this interface. Today the implementation calls the MAX backend, which
// then picks Gemini/OpenAI/OpenRouter. The Android app never talks to an AI vendor directly.
interface AIProvider {
    /** Throws [ApiException] with a user-friendly message on failure. */
    suspend fun chat(request: AIRequest): AIResponse
}

class BackendAIProvider(private val api: MaxApiClient) : AIProvider {
    override suspend fun chat(request: AIRequest): AIResponse {
        val body = JSONObject().put("message", request.message)
        request.conversationId?.let { body.put("conversationId", it) }
        val res = api.request("POST", "/api/chat", body)

        val reply = res.optJSONObject("assistantMessage")?.optString("content").orEmpty()
        if (reply.isBlank()) throw ApiException("MAX's AI service is temporarily unavailable.")

        var intent: CommandIntent? = null
        var actionId: String? = null
        res.optJSONObject("action")?.let { a ->
            val params = a.optJSONObject("parameters")?.let { p -> p.keys().asSequence().associateWith { k -> p.get(k) } } ?: emptyMap()
            // Never trust the server blindly: validate again before anything can run.
            intent = IntentValidator.validate(a.optString("action"), params, a.optBoolean("requiresConfirmation"))
            actionId = a.optString("id").ifBlank { null }
        }
        return AIResponse(res.optString("conversationId").ifBlank { null }, reply, intent, actionId, res.optBoolean("aiUnavailable"))
    }
}
