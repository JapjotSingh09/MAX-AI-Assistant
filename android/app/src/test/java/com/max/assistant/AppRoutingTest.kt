package com.max.assistant

import com.max.assistant.assistant.ActionType
import com.max.assistant.assistant.CommandParser
import com.max.assistant.assistant.ConversationContext
import com.max.assistant.assistant.IntentValidator
import com.max.assistant.assistant.ParsedCommand
import com.max.assistant.assistant.nlu.IntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generic app control: MAX must handle ANY installed app, not a chosen few.
 *
 * These tests deliberately use apps the project has no special knowledge of
 * (Discord, Telegram) to prove the routing is generic rather than a lookup
 * table. Actually LAUNCHING needs a real device and PackageManager, so what is
 * verified here is that the routing produces the right, validated, generic
 * intent - and that it refuses to invent apps.
 */
class AppRoutingTest {

    private fun frame(text: String, fallback: String? = null) =
        IntentRouter.routeApp(text, fallback)

    @Test
    fun `opens arbitrary apps by name with no hardcoded list`() {
        // Discord and Telegram appear nowhere in the app logic; they work
        // because the name is handed to PackageManager at execution time.
        for (name in listOf("Discord", "Telegram", "Instagram", "Chrome", "VS Code", "Calculator")) {
            val f = frame("Open $name")
            assertTrue("no frame for \"Open $name\" (${f?.source})", f != null)
            assertEquals(name, f!!.parameters["appName"])
            assertEquals(ActionType.OPEN_APP, f.action)
        }
    }

    @Test
    fun `hinglish and politeness are handled when opening an app`() {
        assertEquals("Chrome", frame("Chrome khol")?.parameters?.get("appName"))
        assertEquals("Discord", frame("please open Discord")?.parameters?.get("appName"))
        assertEquals("Telegram", frame("launch the Telegram app")?.parameters?.get("appName"))
        assertEquals("Gmail", frame("Hey MAX, open Gmail")?.parameters?.get("appName"))
    }

    @Test
    fun `search inside an app extracts app and query separately`() {
        val f = frame("Search YouTube for Android tutorials")
        assertEquals(ActionType.APP_ACTION, f?.action)
        assertEquals("SEARCH", f?.parameters?.get("operation"))
        assertEquals("Android tutorials", f?.parameters?.get("entity"))
    }

    @Test
    fun `a trailing on-app clause is an explicit app choice`() {
        val f = frame("Search for Arijit Singh on Spotify")
        assertEquals("Spotify", f?.parameters?.get("appName"))
        assertEquals("SEARCH", f?.parameters?.get("operation"))
        assertEquals("Arijit Singh", f?.parameters?.get("entity"))
    }
@Test
    fun `app actions validate against the closed whitelist`() {
        // Whatever the router produces must still pass validation, or the
        // executor would never run it.
        assertTrue(
            "app action failed validation",
            IntentValidator.validate(
                "APP_ACTION",
                mapOf("appName" to "Discord", "operation" to "SEARCH", "entity" to "kotlin")
            ) != null
        )
        // An invented operation must be rejected.
        assertNull(
            IntentValidator.validate(
                "APP_ACTION",
                mapOf("appName" to "Discord", "operation" to "RUN_SHELL")
            )
        )
        // A missing app must be rejected.
        assertNull(IntentValidator.validate("APP_ACTION", mapOf("operation" to "SEARCH")))
    }

    @Test
    fun `a bare search with no app is a web search`() {
        val f = frame("Search for train times to Delhi")
        assertEquals(ActionType.WEB_SEARCH, f?.action)
        assertEquals("train times to Delhi", f?.parameters?.get("query"))
    }

    @Test
    fun `an app named in context is used for a bare follow-up`() {
        // "Open YouTube" -> "Search for Android tutorials"
        val f = frame("Search for Android tutorials", "YouTube")
        assertEquals(ActionType.APP_ACTION, f?.action)
        assertEquals("YouTube", f?.parameters?.get("appName"))
        assertEquals("SEARCH", f?.parameters?.get("operation"))
    }

    @Test
    fun `an explicitly named app always beats remembered context`() {
        // The user's explicit choice must win over what was open before.
        assertEquals("Spotify", frame("Search for lo-fi on Spotify", "YouTube")?.parameters?.get("appName"))
    }

    @Test
    fun `device questions are never mistaken for app requests`() {
        // "battery" must never be read as an app name.
        assertNull(frame("What's my battery percentage?", "YouTube"))
        assertNull(frame("How much storage do I have?", "YouTube"))
        assertNull(frame("Open battery settings", "YouTube"))
    }

    @Test
    fun `long sentences are not treated as app names`() {
        // The router must not claim a whole sentence as an "app".
        assertNull(frame("Open the pod bay doors and tell me a story"))
    }

    // --- Context -------------------------------------------------------

    @Test
    fun `context remembers the last app`() {
        val ctx = ConversationContext()
        assertNull("nothing remembered yet", ctx.currentApp())
        ctx.setApp("YouTube")
        assertEquals("YouTube", ctx.currentApp())
    }

    @Test
    fun `context expires so it cannot act on stale intent`() {
        val ctx = ConversationContext()
        ctx.setApp("YouTube", nowMs = 0L)
        // Two minutes later the context is gone; MAX must not assume YouTube.
        assertNull(ctx.currentApp(nowMs = 200_000L))
    }

    @Test
    fun `a cleared conversation forgets the app`() {
        val ctx = ConversationContext()
        ctx.setApp("Spotify")
        ctx.clear()
        assertNull(ctx.currentApp())
    }

    // --- The whole pipeline --------------------------------------------

    @Test
    fun `the full pipeline routes app commands through the parser`() {
        val parsed = CommandParser.parseWithContext("Open Discord")
        assertTrue("expected an intent, got $parsed", parsed is ParsedCommand.Intent)
        val intent = (parsed as ParsedCommand.Intent).intent
        assertEquals(ActionType.OPEN_APP, intent.action)
        assertEquals("Discord", intent.str("appName"))
    }

    @Test
    fun `the full pipeline still routes battery questions to the device`() {
        val parsed = CommandParser.parseWithContext("What's my battery percentage?")
        assertTrue(parsed is ParsedCommand.Intent)
        val intent = (parsed as ParsedCommand.Intent).intent
        assertEquals(ActionType.READ_BATTERY_LEVEL, intent.action)
        assertNotEquals(ActionType.OPEN_SETTINGS, intent.action)
    }

    @Test
    fun `the full pipeline asks when the request is ambiguous`() {
        val parsed = CommandParser.parseWithContext("...")
        assertTrue("expected a clarification, got $parsed", parsed is ParsedCommand.Clarify)
    }
}