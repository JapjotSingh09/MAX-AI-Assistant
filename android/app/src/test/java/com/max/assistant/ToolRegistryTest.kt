package com.max.assistant

import com.max.assistant.assistant.ActionType
import com.max.assistant.speech.SpeechOutput
import com.max.assistant.services.ReminderScheduler
import com.max.assistant.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Tests for the parts of MAX that are pure logic and can run on the JVM:
 * the tool registry, the reminder time parser, and TTS truncation.
 *
 * Anything that needs a real Android runtime (SpeechRecognizer, TextToSpeech,
 * AlarmManager) is exercised on a device, not here - see ARCHITECTURE.md.
 */
class ToolRegistryTest {

    @Test
    fun `every action type has a spec`() {
        // If a tool is added to the enum but not to the registry, MAX would
        // silently do nothing for it. This test is the guard against that.
        assertTrue(
            "ToolRegistry is missing an entry for: " +
                ActionType.values().filterNot { ToolRegistry.byType.containsKey(it) }.joinToString(),
            ToolRegistry.isComplete()
        )
    }

    @Test
    fun `only people tools need contacts`() {
        for (type in listOf(ActionType.CALL_CONTACT, ActionType.SEND_SMS, ActionType.OPEN_WHATSAPP)) {
            assertTrue(type.name, ToolRegistry.permissionsFor(type).contains("android.permission.READ_CONTACTS"))
        }
        // Most tools need nothing at all, which is why MAX asks for so little.
        for (type in listOf(ActionType.OPEN_APP, ActionType.SET_ALARM, ActionType.CREATE_NOTE, ActionType.WEB_SEARCH)) {
            assertTrue(type.name, ToolRegistry.permissionsFor(type).isEmpty())
        }
    }

    @Test
    fun `android limitations are documented for the tools that have them`() {
        // These three are the ones where a naive implementation would lie about
        // what it can do, so each must carry an explanation for the user.
        assertNotNull(ToolRegistry.limitFor(ActionType.SET_BLUETOOTH))
        assertNotNull(ToolRegistry.limitFor(ActionType.READ_CALENDAR))
        assertNotNull(ToolRegistry.limitFor(ActionType.SHOW_NOTIFICATIONS))
    }
}

class ReminderSchedulerTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `resolves an explicit time today`() {
        val now = at(2026, Calendar.MARCH, 10, 8, 0)
        val whenMillis = ReminderScheduler.resolveWhen("today at 3pm", now)
        assertNotNull(whenMillis)
        val result = Calendar.getInstance().apply { timeInMillis = whenMillis!! }
        assertEquals(15, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result.get(Calendar.MINUTE))
    }

    @Test
    fun `resolves tomorrow`() {
        val now = at(2026, Calendar.MARCH, 10, 8, 0)
        val whenMillis = ReminderScheduler.resolveWhen("tomorrow at 9 am", now)
        assertNotNull(whenMillis)
        val result = Calendar.getInstance().apply { timeInMillis = whenMillis!! }
        assertEquals(11, result.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, result.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `a time that already passed means tomorrow`() {
        val now = at(2026, Calendar.MARCH, 10, 18, 0)
        val whenMillis = ReminderScheduler.resolveWhen("at 9 am", now)
        assertNotNull(whenMillis)
        val result = Calendar.getInstance().apply { timeInMillis = whenMillis!! }
        assertEquals(9, result.get(Calendar.HOUR_OF_DAY))
        // Strictly in the future: that is the whole point of the rule.
        assertTrue(whenMillis!! > now.timeInMillis)
    }

    @Test
    fun `refuses to guess when the phrase is too vague`() {
        // MAX must not invent a time. A null here is the SAFE answer and makes
        // the executor fall back to the calendar.
        val now = at(2026, Calendar.MARCH, 10, 8, 0)
        assertNull(ReminderScheduler.resolveWhen("about the thing", now))
        assertNull(ReminderScheduler.resolveWhen("sometime soon", now))
        assertNull(ReminderScheduler.resolveWhen(null, now))
        // "at 99 o'clock" is nonsense, not a time.
        assertNull(ReminderScheduler.resolveWhen("at 99", now))
    }
}

class SpeechTruncationTest {

    @Test
    fun `short replies are untouched`() {
        val text = "Your alarm is set for 7am."
        assertEquals(text, SpeechOutput.truncate(text))
    }

    @Test
    fun `long replies stop at a sentence boundary`() {
        // A real reply: a short complete sentence, then a long tail.
        val long = "Your alarm is set for 7am. " + "word ".repeat(200)
        val spoken = SpeechOutput.truncate(long)
        assertTrue(spoken.length <= SpeechOutput.MAX_SPOKEN_CHARS + 1)
        // Ending on a full stop means MAX never reads out half a sentence.
        assertTrue(spoken.endsWith("."))
        assertEquals("Your alarm is set for 7am.", spoken)
    }

    @Test
    fun `truncation falls back to a word boundary with no sentence end`() {
        val long = "alpha bravo charlie delta echo foxtrot golf hotel india juliet " +
            "kilo lima mike november oscar papa quebec romeo sierra tango"
        val spoken = SpeechOutput.truncate(long)
        assertTrue(spoken.length <= SpeechOutput.MAX_SPOKEN_CHARS)
        // No half word, and no trailing space left hanging.
        assertTrue(spoken.split(" ").all { it.isNotBlank() })
        assertTrue(!spoken.endsWith(" "))
    }
}
