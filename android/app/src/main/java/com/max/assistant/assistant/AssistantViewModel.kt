package com.max.assistant.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.max.assistant.AppContainer
import com.max.assistant.actions.ActionResult
import com.max.assistant.ai.AIRequest
import com.max.assistant.data.remote.ApiException
import com.max.assistant.data.remote.FriendlyErrors
import com.max.assistant.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String)

/** An action waiting for the user to tap Confirm (or for a permission to be granted). */
data class PendingAction(val intent: CommandIntent, val serverActionId: String?)

/** One conversation in the history list. */
data class ConversationSummary(val id: String, val title: String, val updatedAt: String)

data class AssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val pending: PendingAction? = null,
    val busy: Boolean = false,
    val permissionRequest: List<String> = emptyList(),
    val lastReply: String? = null,
    /** True while MAX waits for the AI, so the UI can show a thinking state. */
    val thinking: Boolean = false,
    /** True when the device genuinely has no connection. */
    val offline: Boolean = false,
    /** One-line status: which tool is running, or the last outcome. */
    val status: String? = null,
    /** True when the last turn came from the microphone, so MAX speaks back. */
    val lastTurnWasVoice: Boolean = false,
    // --- Conversation management ---
    val conversations: List<ConversationSummary> = emptyList(),
    val conversationsLoading: Boolean = false,
    val conversationError: String? = null
)

// The pipeline lives here:
// text -> LOCAL parser -> (cloud AI if needed) -> validate -> permission check
//      -> confirmation -> AndroidActionExecutor -> result message -> activity log.
class AssistantViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var conversationId: String? = null
    private var nextId = 0L

    /** The in-flight AI request. Cancelled when the user sends something new. */
    private var inFlight: Job? = null

    private fun addMessage(fromUser: Boolean, text: String) =
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(nextId++, fromUser, text),
                lastReply = if (fromUser) it.lastReply else text
            )
        }

    /**
     * Sends a message, or runs a local command.
     *
     * @param fromVoice true when the words came from the microphone, which is
     *        what decides whether MAX speaks the reply back.
     */
    fun send(raw: String, fromVoice: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty() || _state.value.busy) return
        addMessage(true, text)
        _state.update { it.copy(lastTurnWasVoice = fromVoice, offline = false, status = null) }

        // LOCAL FIRST: deterministic commands never need the internet or an AI call.
        when (val local = CommandParser.parse(text)) {
            is ParsedCommand.Intent -> {
                handleIntent(local.intent, null)
                return
            }
            // Memory commands and anything unrecognised go to the cloud: memories
            // live on the MAX server and need the AI's memory context.
            else -> Unit
        }

        // CLOUD WHEN NEEDED: questions, ambiguity, conversation.
        _state.update { it.copy(busy = true, thinking = true) }
        inFlight?.cancel()
        inFlight = viewModelScope.launch {
            try {
                val res = c.ai.chat(AIRequest(text, conversationId))
                conversationId = res.conversationId ?: conversationId
                _state.update { it.copy(thinking = false, status = null) }
                addMessage(false, res.reply)
                res.suggestedAction?.let { handleIntent(it, res.serverActionId) }
                if (res.conversationId != null) loadConversations()
            } catch (e: ApiException) {
                // FriendlyErrors already separates "you're offline" from
                // "the server is unreachable", so it is shown verbatim.
                _state.update { it.copy(thinking = false, offline = e.message == FriendlyErrors.OFFLINE) }
                addMessage(false, e.message)
            } catch (e: CancellationException) {
                // A newer request replaced this one. Not an error, but the
                // thinking indicator must still be cleared.
                _state.update { it.copy(thinking = false) }
                throw e
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun handleIntent(intent: CommandIntent, serverActionId: String?) {
        // The confirmation card always shows the Android limitation, so the user
        // knows exactly what they are agreeing to before they tap.
        val limit = ToolRegistry.limitFor(intent.action)
        val message = buildString {
            append(if (intent.requiresConfirmation) "Confirm to " else "")
            append(intent.describe())
            if (limit != null) append("\n\n").append(limit)
        }
        if (intent.requiresConfirmation) {
            addMessage(false, message)
            _state.update { it.copy(pending = PendingAction(intent, serverActionId)) }
        } else {
            execute(PendingAction(intent, serverActionId))
        }
    }

    fun confirm() {
        val p = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        execute(p)
    }

    fun cancel() {
        val p = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        addMessage(false, "Okay, cancelled.")
        report(p, "cancelled", "Cancelled: ${p.intent.describe()}")
    }

    /** Called after the system permission dialog closes: try the action again. */
    fun onPermissionResult() {
        val p = _state.value.pending
        _state.update { it.copy(permissionRequest = emptyList(), pending = null) }
        if (p != null) execute(p)
    }

    private fun execute(p: PendingAction) {
        val missing = c.permissions.missingFor(p.intent.action)
        if (missing.isNotEmpty()) {
            addMessage(false, c.permissions.explain(missing.first()))
            _state.update { it.copy(pending = p, permissionRequest = missing) }
            return
        }
        _state.update { it.copy(status = "Running ${p.intent.action.name.lowercase().replace('_', ' ')}…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { c.executor.execute(p.intent) }
            when (result) {
                is ActionResult.NeedsPermission ->
                    _state.update { it.copy(pending = p, permissionRequest = result.permissions, status = null) }
                else -> {
                    _state.update { it.copy(status = null) }
                    report(p, result.status, "${result.message.take(60)}: ${p.intent.describe()}".take(190))
                }
            }
            // MAX only says what really happened.
            addMessage(false, result.message)
        }
    }

    // Records the true outcome. Failures here (e.g. offline) are ignored on purpose.
    private fun report(p: PendingAction, status: String, summary: String) {
        viewModelScope.launch {
            try {
                if (p.serverActionId != null) {
                    c.api.request("PATCH", "/api/actions/${p.serverActionId}", JSONObject().put("status", status).put("message", summary.take(280)))
                } else {
                    c.api.request(
                        "POST", "/api/activity",
                        JSONObject().put("actionType", p.intent.action.name).put("status", status).put("summary", summary)
                    )
                }
            } catch (_: ApiException) { /* offline: the action itself already ran */ }
        }
    }

    // --- Conversation management ------------------------------------------
    //
    // All of these call the existing MAX endpoints. Every request is scoped by
    // the session token on the server, so the phone never handles user ids.

    /**
     * Loads the user's conversations. `query` is sent to the server, which
     * filters by title AND scopes the query to the signed-in user, so a search
     * can never surface another account's history.
     */
    fun loadConversations(query: String? = null) {
        _state.update { it.copy(conversationsLoading = true, conversationError = null) }
        viewModelScope.launch {
            try {
                val path = buildString {
                    append("/api/conversations?limit=50")
                    if (!query.isNullOrBlank()) append("&q=").append(URLEncoder.encode(query, "UTF-8"))
                }
                _state.update { it.copy(conversations = parseSummaries(c.api.request("GET", path)), conversationsLoading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(conversationsLoading = false, conversationError = e.message) }
            }
        }
    }

    private fun parseSummaries(res: JSONObject): List<ConversationSummary> {
        val items = res.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                add(
                    ConversationSummary(
                        id = o.optString("id"),
                        title = o.optString("title").ifBlank { "New conversation" },
                        updatedAt = o.optString("updatedAt")
                    )
                )
            }
        }
    }

    /** Starts a brand-new conversation and clears the on-screen transcript. */
    fun newConversation() {
        conversationId = null
        nextId = 0
        _state.update {
            it.copy(messages = emptyList(), pending = null, lastReply = null, conversationError = null)
        }
        loadConversations()
    }

    /** Opens a past conversation and shows its most recent messages. */
    fun openConversation(id: String) {
        _state.update { it.copy(conversationsLoading = true, conversationError = null, messages = emptyList()) }
        viewModelScope.launch {
            try {
                val res = c.api.request("GET", "/api/conversations/$id")
                conversationId = id
                val items = res.optJSONArray("items")
                val restored = buildList {
                    // The API returns newest-first; the chat list reads oldest-first.
                    for (i in (items?.length() ?: 0) - 1 downTo 0) {
                        val o = items!!.optJSONObject(i) ?: continue
                        add(ChatMessage(nextId++, o.optString("role") != "assistant", o.optString("content")))
                    }
                }
                _state.update { it.copy(messages = restored, conversationsLoading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(conversationsLoading = false, conversationError = e.message) }
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            try {
                c.api.request("PATCH", "/api/conversations/$id", JSONObject().put("title", title))
                loadConversations()
            } catch (e: ApiException) {
                _state.update { it.copy(conversationError = e.message) }
            }
        }
    }

    /**
     * Deletes one conversation. The caller must confirm with the user first -
     * this endpoint is irreversible and cascades to its messages.
     */
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            try {
                c.api.request("DELETE", "/api/conversations/$id")
                if (conversationId == id) {
                    conversationId = null
                    _state.update { it.copy(messages = emptyList()) }
                }
                loadConversations()
            } catch (e: ApiException) {
                _state.update { it.copy(conversationError = e.message) }
            }
        }
    }

    /** Clears the on-screen transcript without deleting server-side history. */
    fun clearOnScreen() {
        _state.update { it.copy(messages = emptyList(), pending = null, lastReply = null) }
    }

    /** Cancels an in-flight request, so a long answer can be abandoned. */
    fun cancelRequest() {
        inFlight?.cancel()
        inFlight = null
        _state.update { it.copy(busy = false, thinking = false, status = "Stopped") }
    }

    /** Re-checks connectivity so the offline banner stays honest. */
    fun refreshConnectivity(context: android.content.Context) {
        _state.update { it.copy(offline = !AppContainer.isOnline(context)) }
    }

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(c) as T
    }
}
