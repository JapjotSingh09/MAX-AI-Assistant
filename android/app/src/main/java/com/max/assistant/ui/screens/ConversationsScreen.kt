package com.max.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.max.assistant.assistant.AssistantViewModel
import com.max.assistant.assistant.ConversationSummary

/**
 * Conversation management: new, list, search, rename, delete, open.
 *
 * Everything here goes through the existing MAX endpoints
 * (`/api/conversations`). The server scopes every query to the signed-in user,
 * so the phone never handles or guesses a user id.
 *
 * Delete sits behind an explicit dialog on purpose: it cascades to the
 * conversation's messages and cannot be undone.
 */
@Composable
fun ConversationsScreen(vm: AssistantViewModel, onOpen: (String) -> Unit) {
    val state by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleting by remember { mutableStateOf<ConversationSummary?>(null) }

    // Debounce the search so each keystroke does not hit the network.
    LaunchedEffect(query) {
        if (query.isBlank()) vm.loadConversations() else {
            kotlinx.coroutines.delay(350)
            vm.loadConversations(query)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.newConversation() }) { Text("+ New") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search conversations") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        state.conversationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }

        when {
            state.conversationsLoading && state.conversations.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            state.conversations.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 40.dp)) {
                Text(
                    if (query.isBlank()) "No conversations yet." else "Nothing matches \"$query\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.conversations, key = { it.id }) { c ->
                    ConversationRow(
                        c,
                        onOpen = { onOpen(c.id) },
                        onRename = { renaming = c },
                        onDelete = { deleting = c }
                    )
                }
            }
        }
    }
    // --- Rename -------------------------------------------------------------
    renaming?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(100) },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (draft.isNotBlank()) vm.renameConversation(target.id, draft.trim())
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }

    // --- Delete: destructive, so it always asks first --------------------
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this conversation?") },
            text = {
                Text("\"${target.title}\" and all of its messages will be permanently deleted. This cannot be undone.")
            },
            confirmButton = {
                Button(onClick = {
                    vm.deleteConversation(target.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }
        )
    }
}

/** One conversation in the list, with its rename and delete affordances. */
@Composable
private fun ConversationRow(
    c: ConversationSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (c.updatedAt.isNotBlank()) {
                    Text(
                        c.updatedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onRename) { Text("✎", style = MaterialTheme.typography.titleMedium) }
            IconButton(onClick = onDelete) {
                Text("🗑", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
