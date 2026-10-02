package com.max.assistant.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Notes and tasks, stored ON THE PHONE so they keep working with no internet.
 *
 * WHY local and not on the MAX server:
 *  - "Create a note" must work on a train with no signal, and so must
 *    "what are my notes". A server round-trip would make both fail offline.
 *  - The user's personal notes never leave the device, which is also the
 *    simplest privacy story we can give.
 *
 * WHY a plain JSON file instead of Room:
 *  - The data is a few dozen short strings per user. Room would add a
 *    dependency, a schema and a migration story for no real benefit.
 *  - Writes are atomic (temp file + rename), so a crash mid-write can never
 *    leave a half-written notes file behind.
 *
 * CONCURRENCY: every public method is `synchronized`. Tools can run from a UI
 * coroutine and from the wake-word service at the same time.
 */
class LocalStore(private val context: Context) {

    data class Note(val id: String, val title: String, val content: String, val createdAt: Long)
    data class Task(val id: String, val title: String, val due: String?, val done: Boolean, val createdAt: Long)

    private val file = File(context.filesDir, "max_local_store.json")
    private val lock = Any()

    // In-memory cache: parsing the file on every note operation would be
    // wasteful, and these lists are small enough to hold in memory.
    private var notes: MutableList<Note> = mutableListOf()
    private var tasks: MutableList<Task> = mutableListOf()
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            notes = root.optJSONArray("notes")?.toNotes()?.toMutableList() ?: mutableListOf()
            tasks = root.optJSONArray("tasks")?.toTasks()?.toMutableList() ?: mutableListOf()
        }.onFailure {
            // A corrupt file must never crash the app. Start clean and move the
            // bad file aside so the user can still recover it manually.
            runCatching { file.renameTo(File(context.filesDir, "max_local_store.corrupt.json")) }
            notes = mutableListOf()
            tasks = mutableListOf()
        }
    }

    private fun persist() {
        runCatching {
            val root = JSONObject()
            root.put("notes", JSONArray().also { a -> notes.forEach { a.put(it.toJson()) } })
            root.put("tasks", JSONArray().also { a -> tasks.forEach { a.put(it.toJson()) } })
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString())
            // Rename is atomic on the same filesystem: a reader sees either the
            // old file or the new one, never a partial write.
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }
        // A failed write (no space left, for example) must not crash a voice
        // command. The in-memory copy still answers LIST_* correctly this
        // session, and the next successful write fixes persistence.
    }
    // --- Notes ----------------------------------------------------------

    /** @return the saved note, or null when the text was blank. */
    fun addNote(title: String?, content: String): Note? {
        val body = content.trim()
        if (body.isEmpty()) return null
        synchronized(lock) {
            ensureLoaded()
            val note = Note(
                id = UUID.randomUUID().toString(),
                // A missing title is normal ("note: milk"), so fall back to the
                // start of the body rather than storing an empty heading.
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: body.take(60),
                content = body.take(2000),
                createdAt = System.currentTimeMillis()
            )
            notes.add(0, note)
            persist()
            return note
        }
    }

    fun notes(): List<Note> = synchronized(lock) { ensureLoaded(); notes.toList() }

    /** Case-insensitive substring match, newest first. */
    fun findNotes(query: String): List<Note> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return notes()
        return notes().filter {
            it.title.lowercase().contains(q) || it.content.lowercase().contains(q)
        }
    }

    /** Deletes the first note whose title or content contains [query]. */
    fun deleteNote(query: String): Note? = synchronized(lock) {
        ensureLoaded()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@synchronized null
        val idx = notes.indexOfFirst {
            it.title.lowercase().contains(q) || it.content.lowercase().contains(q)
        }
        if (idx < 0) return@synchronized null
        notes.removeAt(idx).also { persist() }
    }

    // --- Tasks ----------------------------------------------------------

    fun addTask(title: String, due: String?): Task? {
        val body = title.trim()
        if (body.isEmpty()) return null
        synchronized(lock) {
            ensureLoaded()
            val task = Task(
                id = UUID.randomUUID().toString(),
                title = body.take(120),
                due = due?.trim()?.takeIf { it.isNotEmpty() }?.take(40),
                done = false,
                createdAt = System.currentTimeMillis()
            )
            tasks.add(0, task)
            persist()
            return task
        }
    }

    fun tasks(includeDone: Boolean = false): List<Task> = synchronized(lock) {
        ensureLoaded()
        tasks.filter { includeDone || !it.done }
    }

    /** Marks the first open task matching [query] as done. */
    fun completeTask(query: String): Task? = synchronized(lock) {
        ensureLoaded()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@synchronized null
        val idx = tasks.indexOfFirst { !it.done && it.title.lowercase().contains(q) }
        if (idx < 0) return@synchronized null
        tasks[idx].copy(done = true).also {
            tasks[idx] = it
            persist()
        }
    }

    private fun Note.toJson() = JSONObject()
        .put("id", id).put("title", title).put("content", content).put("createdAt", createdAt)

    private fun Task.toJson() = JSONObject()
        .put("id", id).put("title", title)
        .put("due", due ?: JSONObject.NULL)
        .put("done", done).put("createdAt", createdAt)

    private fun JSONArray.toNotes(): List<Note> = (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.let { o ->
            runCatching { Note(o.getString("id"), o.optString("title"), o.optString("content"), o.optLong("createdAt")) }
                .getOrNull()
        }
    }

    private fun JSONArray.toTasks(): List<Task> = (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.let { o ->
            runCatching {
                Task(
                    o.getString("id"),
                    o.optString("title"),
                    if (o.isNull("due")) null else o.optString("due"),
                    o.optBoolean("done"),
                    o.optLong("createdAt")
                )
            }.getOrNull()
        }
    }
}
