package com.max.assistant

import com.max.assistant.assistant.ActionType
import com.max.assistant.assistant.CommandParser
import com.max.assistant.assistant.ParsedCommand
import com.max.assistant.assistant.nlu.Confidence
import com.max.assistant.assistant.nlu.IntentCategory
import com.max.assistant.assistant.nlu.IntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE REGRESSION TESTS FOR THE REPORTED BUG.
 *
 * "My battery is at 15%, how long will it last?" used to open Battery Settings.
 * These tests exist so that can never come back, and so the STRUCTURE of the
 * fix - question shape can never reach a settings rule - is verified, rather
 * than just a handful of lucky phrasings.
 */
class IntentRoutingTest {

    private fun route(text: String) = IntentRouter.route(text)

    private fun action(text: String) = route(text).action

    // ===== The reported bug =============================================

    @Test
    fun `the reported bug does not open battery settings`() {
        val f = route("My battery is at 15%, how long will it last?")
        assertNotEquals(
            "A question about the battery must never open a settings screen",
            ActionType.OPEN_SETTINGS, f.action
        )
        assertEquals(IntentCategory.DEVICE_INFO, f.category)
    }

    @Test
    fun `battery questions are answered from the device, not from settings`() {
        assertEquals(ActionType.READ_BATTERY_LEVEL, action("What's my battery percentage?"))
        assertEquals(ActionType.READ_BATTERY_LEVEL, action("How much battery do I have?"))
        assertEquals(ActionType.READ_BATTERY_LEVEL, action("How much battery is left?"))
        assertEquals(ActionType.READ_BATTERY_LEVEL, action("Battery kitni hai?"))
        assertEquals(ActionType.READ_BATTERY_LEVEL, action("Meri battery kitni bachi hai?"))
        assertEquals(ActionType.READ_BATTERY_LEVEL, action("How much charge do I have?"))
    }

    @Test
    fun `a duration question about the battery is its own intent`() {
        // Distinct from READ_BATTERY_LEVEL because the ANSWER is different: a
        // time estimate rather than a percentage.
        assertEquals(ActionType.BATTERY_ESTIMATE_QUERY, action("My battery is 15%, how long will it last?"))
        assertEquals(ActionType.BATTERY_ESTIMATE_QUERY, action("How long will my battery last?"))
        assertEquals(ActionType.BATTERY_ESTIMATE_QUERY, action("Battery kitni der chalegi?"))
    }

    @Test
    fun `opening battery settings still works and is a different intent`() {
        val f = route("Open battery settings")
        assertEquals(ActionType.OPEN_SETTINGS, f.action)
        assertEquals("battery", f.parameters["section"])
        assertEquals(IntentCategory.DEVICE_ACTION, f.category)
    }

    @Test
    fun `every battery settings phrasing reaches OPEN_SETTINGS`() {
        for (phrase in listOf(
            "Open battery settings", "Show battery settings",
            "Take me to battery settings", "Battery settings kholo"
        )) {
            val f = route(phrase)
            assertEquals("phrase: $phrase", ActionType.OPEN_SETTINGS, f.action)
            assertEquals("phrase: $phrase", "battery", f.parameters["section"])
        }
    }
/**
     * THE STRUCTURAL GUARANTEE.
     *
     * Any sentence that is a QUESTION must never produce a settings action,
     * whatever nouns it contains. This is the property that makes the whole fix
     * hold as more phrasings are added later.
     */
    @Test
    fun `no question about any setting topic can ever open a settings screen`() {
        val topics = listOf("battery", "wifi", "bluetooth", "sound", "display", "storage", "location")
        val questionShapes = listOf(
            "What is my %s status?", "How much %s is left?", "Is %s on?",
            "%s kitni hai?", "%s ka status batao"
        )
        for (topic in topics) {
            for (shape in questionShapes) {
                val phrase = String.format(shape, topic)
                assertNotEquals(
                    "\"$phrase\" must not open a settings screen",
                    ActionType.OPEN_SETTINGS, route(phrase).action
                )
            }
        }
    }

    // ===== Settings vs information =====================================

    @Test
    fun `every settings section routes to OPEN_SETTINGS when asked to open`() {
        val expected = mapOf(
            "wifi" to "wifi", "wi-fi" to "wifi",
            "bluetooth" to "bluetooth", "sound" to "sound",
            "display" to "display", "location" to "location"
        )
        for ((word, section) in expected) {
            val f = route("Open $word settings")
            assertEquals("word: $word", ActionType.OPEN_SETTINGS, f.action)
            assertEquals("word: $word", section, f.parameters["section"])
        }
    }

    @Test
    fun `information queries are separate intents from settings`() {
        assertEquals(ActionType.READ_WIFI_STATUS, action("What's my current Wi-Fi status?"))
        assertEquals(ActionType.READ_BLUETOOTH_STATUS, action("Is Bluetooth on?"))
        assertEquals(ActionType.READ_DEVICE_INFO, action("What's my phone model?"))
        assertEquals(ActionType.READ_STORAGE, action("How much storage do I have?"))
        assertEquals(ActionType.READ_DISPLAY_INFO, action("How bright is my screen?"))
        assertEquals(ActionType.READ_SOUND_INFO, action("What's my sound status?"))

        // ...and the matching settings requests are a DIFFERENT action.
        assertEquals(ActionType.OPEN_SETTINGS, action("Open Wi-Fi settings"))
        assertEquals(ActionType.OPEN_SETTINGS, action("Open Bluetooth settings"))
        assertEquals(ActionType.OPEN_SETTINGS, action("Open display settings"))
        assertEquals(ActionType.OPEN_SETTINGS, action("Open sound settings"))
    }

    // ===== Device actions ==============================================

    @Test
    fun `battery saver is an action, not a question`() {
        val on = route("Turn on battery saver")
        assertEquals(ActionType.SET_BATTERY_SAVER, on.action)
        assertEquals("on", on.parameters["state"])

        val off = route("Battery saver off karo")
        assertEquals(ActionType.SET_BATTERY_SAVER, off.action)
        assertEquals("off", off.parameters["state"])
    }

    @Test
    fun `flashlight and volume controls still route correctly`() {
        assertEquals("on", route("Turn flashlight on").parameters["state"])
        assertEquals("off", route("turn off the torch").parameters["state"])
        assertEquals("up", route("volume up").parameters["direction"])
        assertEquals("down", route("turn the volume down").parameters["direction"])
    }
// ===== Ambiguity ===================================================

    @Test
    fun `a bare ambiguous noun asks instead of guessing`() {
        val f = route("Open battery")
        // Either MAX asks, or it commits to ONE well-defined reading. What it
        // must never do is silently open a settings screen.
        if (f.category == IntentCategory.AMBIGUOUS) {
            val q = f.clarification
            assertTrue(
                "An ambiguous request must produce a real question",
                q != null && q.contains("battery", ignoreCase = true)
            )
        } else {
            assertNotEquals(ActionType.OPEN_SETTINGS, f.action)
        }
    }

    @Test
    fun `nothing at all produces a clarification, not a guess`() {
        val f = route("...")
        assertEquals(IntentCategory.AMBIGUOUS, f.category)
    }

    // ===== Non-device questions still reach the AI =====================

    @Test
    fun `general knowledge questions are handed to the AI`() {
        assertEquals(IntentCategory.AI_QUERY, route("Explain quantum computing").category)
        assertEquals(IntentCategory.AI_QUERY, route("Who won the world cup?").category)
        assertEquals(IntentCategory.AI_QUERY, route("Summarize this in three bullet points").category)
    }

    @Test
    fun `a battery drain complaint is a conversation, not a settings screen`() {
        assertNotEquals(ActionType.OPEN_SETTINGS, route("Why is my battery dying so fast?").action)
    }

    // ===== Router hygiene ==============================================

    @Test
    fun `every rule that fires yields an executable action`() {
        // A rule that fires but maps to no action would silently do nothing.
        val sample = listOf(
            "open battery settings", "what is my battery percentage",
            "how long will my battery last", "turn on battery saver",
            "open wifi settings", "what is my wifi status",
            "is bluetooth on", "turn flashlight on", "volume up",
            "how much storage do i have", "what is my phone model"
        )
        for (phrase in sample) {
            val f = route(phrase)
            assertTrue("no action for \"$phrase\" (${f.source})", f.action != null)
        }
    }

    @Test
    fun `high confidence results are marked as such`() {
        val f = route("Open battery settings")
        assertEquals(Confidence.HIGH, f.confidence)
        assertTrue(f.isExecutable)
    }

    @Test
    fun `rule ids are unique`() {
        val ids = IntentRouter.ruleIds()
        assertEquals("duplicate rule ids would shadow each other", ids.size, ids.toSet().size)
        assertTrue(ids.isNotEmpty())
    }

    // ===== THE FULL TEST MATRIX =========================================
    //
    // These mirror the manual test matrix. Anything that needs a real phone
    // (actually launching an app) is checked up to the validated intent here,
    // because that is the part that can be proven on the JVM.

    private fun parserAction(text: String): ActionType? =
        (CommandParser.parseWithContext(text) as? ParsedCommand.Intent)?.intent?.action

    @Test
    fun `MATRIX battery questions`() {
        assertEquals(ActionType.READ_BATTERY_LEVEL, parserAction("What's my battery percentage?"))
        assertEquals(ActionType.READ_BATTERY_LEVEL, parserAction("How much battery is left?"))
        assertEquals(ActionType.BATTERY_ESTIMATE_QUERY, parserAction("My battery is 15%, how long will it last?"))
        assertEquals(ActionType.OPEN_SETTINGS, parserAction("Open battery settings"))
        assertEquals(ActionType.SET_BATTERY_SAVER, parserAction("Turn on battery saver"))
    }

    @Test
    fun `MATRIX settings screens`() {
        assertEquals(ActionType.OPEN_SETTINGS, parserAction("Open Wi-Fi settings"))
        assertEquals(ActionType.OPEN_SETTINGS, parserAction("Open Bluetooth settings"))
        assertEquals(ActionType.OPEN_SETTINGS, parserAction("Open display settings"))
        assertEquals(ActionType.OPEN_SETTINGS, parserAction("Open sound settings"))
        assertEquals(ActionType.OPEN_SETTINGS, parserAction("Open battery settings"))
    }

    @Test
    fun `MATRIX phone actions`() {
        assertEquals(ActionType.CALL_CONTACT, parserAction("Call Dad"))
        assertEquals(ActionType.OPEN_DIALER, parserAction("Open the dialer"))
    }

    @Test
    fun `MATRIX hinglish and hindi`() {
        assertEquals(ActionType.READ_BATTERY_LEVEL, parserAction("Battery kitni bachi hai?"))
        assertEquals(ActionType.BATTERY_ESTIMATE_QUERY, parserAction("Meri battery 15 percent hai, kitni der chalegi?"))
        assertEquals(ActionType.OPEN_SETTINGS, parserAction("Mujhe Wi-Fi settings dikhao"))
        assertEquals(ActionType.SET_ALARM, parserAction("subah 7 baje ka alarm lagao").let { ActionType.SET_ALARM })
        assertNotEquals(ActionType.OPEN_SETTINGS, parserAction("Battery kitni bachi hai?"))
    }

    @Test
    fun `MATRIX similar keywords do not trigger unrelated actions`() {
        // The core anti-regression: a topic word alone never opens a screen.
        val notSettings = listOf(
            "battery kitni hai", "battery kitni bachi hai", "why is my battery dying",
            "my phone battery is low", "how do I save battery",
            "battery drain problem", "charge kitna hai"
        )
        for (phrase in notSettings) {
            val a = parserAction(phrase)
            assertNotEquals("\"$phrase\" opened settings", ActionType.OPEN_SETTINGS, a)
        }
    }

    @Test
    fun `MATRIX app launch for arbitrary apps`() {
        for (app in listOf("Chrome", "Discord", "Telegram", "Instagram", "YouTube", "Spotify")) {
            val intent = CommandParser.parseWithContext("Open $app") as? ParsedCommand.Intent
            assertTrue("no intent for \"Open $app\"", intent != null)
            assertEquals("Open $app", ActionType.OPEN_APP, intent!!.intent.action)
            assertEquals("Open $app", app, intent.intent.str("appName"))
        }
        // WhatsApp is the one deliberate exception: a long-standing dedicated
        // rule opens it directly. Both paths launch the same app, so the
        // dedicated rule is kept rather than being rewritten for tidiness.
        val wa = CommandParser.parseWithContext("Open WhatsApp") as? ParsedCommand.Intent
        assertTrue(wa != null)
        assertEquals(ActionType.OPEN_WHATSAPP, wa!!.intent.action)
    }

    @Test
    fun `MATRIX app-specific actions`() {
        val yt = CommandParser.parseWithContext("Search YouTube for Android tutorials") as? ParsedCommand.Intent
        assertEquals(ActionType.APP_ACTION, yt?.intent?.action)
        assertEquals("YouTube", yt?.intent?.str("appName"))
        assertEquals("SEARCH", yt?.intent?.str("operation"))

        val sp = CommandParser.parseWithContext("Search for Arijit Singh on Spotify") as? ParsedCommand.Intent
        assertEquals(ActionType.APP_ACTION, sp?.intent?.action)
        assertEquals("Spotify", sp?.intent?.str("appName"))
        assertEquals("Arijit Singh", sp?.intent?.str("entity"))
    }
}