package com.max.assistant.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.max.assistant.AppContainer
import com.max.assistant.actions.ActionResult
import com.max.assistant.ai.AIRequest
import com.max.assistant.data.remote.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String)

/** An action waiting for the user to tap Confirm (or for a permission to be granted). */
data class PendingAction(val intent: CommandIntent, val serverActionId: String?)

data class AssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val pending: PendingAction? = null,
    val busy: Boolean = false,
    val permissionRequest: List<String> = emptyList(),
    val lastReply: String? = null
)

// The pipeline lives here:
// text -> LOCAL parser -> (cloud AI if needed) -> validate -> permission check
//      -> confirmation -> AndroidActionExecutor -> result message -> activity log.
class AssistantViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var conversationId: String? = null
    private var nextId = 0L

    private fun addMessage(fromUser: Boolean, text: String) =
        _state.update { it.copy(messages = it.messages + ChatMessage(nextId++, fromUser, text), lastReply = if (fromUser) it.lastReply else text) }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _state.value.busy) return
        addMessage(true, text)

        // LOCAL FIRST: deterministic commands never need the internet or an AI call.
        val local = CommandParser.parse(text)
        if (local is ParsedCommand.Intent) {
            handleIntent(local.intent, null)
            return
        }

        // CLOUD WHEN NEEDED: questions, ambiguity, conversation.
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val res = c.ai.chat(AIRequest(text, conversationId))
                conversationId = res.conversationId ?: conversationId
                addMessage(false, res.reply)
                res.suggestedAction?.let { handleIntent(it, res.serverActionId) }
            } catch (e: ApiException) {
                addMessage(false, e.message)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun handleIntent(intent: CommandIntent, serverActionId: String?) {
        if (intent.requiresConfirmation) {
            addMessage(false, "I can do this once you confirm: ${intent.describe()}.")
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
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { c.executor.execute(p.intent) }
            when (result) {
                is ActionResult.NeedsPermission -> _state.update { it.copy(pending = p, permissionRequest = result.permissions) }
                else -> report(p, result.status, "${result.message.take(60)}: ${p.intent.describe()}".take(190))
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

    class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(c) as T
    }
}
