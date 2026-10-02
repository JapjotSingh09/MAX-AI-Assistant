package com.max.assistant

import com.max.assistant.assistant.ActionType
import com.max.assistant.assistant.CommandParser
import com.max.assistant.assistant.IntentValidator
import com.max.assistant.assistant.ParsedCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private fun intent(text: String) = (CommandParser.parse(text) as? ParsedCommand.Intent)?.intent

    @Test fun opensApps() {
        assertEquals(ActionType.OPEN_APP, intent("Open YouTube")?.action)
        assertEquals("YouTube", intent("Open YouTube")?.str("appName"))
    }

    @Test fun flashlight() = assertEquals("on", intent("Turn flashlight on")?.str("state"))

    @Test fun timerAndAlarm() {
        assertEquals(600, intent("Set timer for 10 minutes")?.int("seconds"))
        assertEquals(19, intent("set an alarm for 7 PM")?.int("hour"))
        assertEquals(7, intent("Set an alarm for 7 AM")?.int("hour"))
    }

    @Test fun callsAndMessagesAlwaysNeedConfirmation() {
        assertTrue(intent("Call Dad")!!.requiresConfirmation)
        val sms = intent("Send Rahul a message saying I'll reach in 10 minutes")!!
        assertEquals(ActionType.SEND_SMS, sms.action)
        assertEquals("I'll reach in 10 minutes", sms.str("message"))
        assertTrue(sms.requiresConfirmation)
    }

    @Test fun questionsGoToCloudAi() {
        assertNull(CommandParser.parse("Explain this topic to me"))
    }

    @Test fun unknownActionsAreRejected() {
        assertNull(IntentValidator.validate("RUN_SHELL", mapOf("cmd" to "rm -rf /")))
        assertNull(IntentValidator.validate("SET_ALARM", mapOf("hour" to 99, "minute" to 0)))
        assertNull(IntentValidator.validate("OPEN_BROWSER", mapOf("url" to "javascript:alert(1)")))
        assertNotNull(IntentValidator.validate("SET_ALARM", mapOf("hour" to 7, "minute" to 0)))
    }

    @Test fun aiCannotDisableConfirmationForCalls() {
        val v = IntentValidator.validate("CALL_CONTACT", mapOf("contactName" to "Dad"), aiWantsConfirm = false)
        assertTrue(v!!.requiresConfirmation)
    }

    // --- Spoken phrasing ---------------------------------------------------
    // The "Hey MAX, remind me tomorrow at 9 AM to call dad" case from the spec.

    @Test fun spokenReminderSplitsIntoTextAndWhen() {
        val r = intent("Hey MAX, remind me tomorrow at 9 AM to call dad")
        assertEquals(ActionType.CREATE_REMINDER, r?.action)
        assertEquals("to call dad", r?.str("text"))
        assertEquals("tomorrow at 9 am", r?.str("when"))
    }

    @Test fun spokenDurationsUseNumberWords() {
        assertEquals(5400, intent("set a timer for ninety minutes")?.int("seconds"))
        assertEquals(5400, intent("set a timer for one and a half hours")?.int("seconds"))
        assertEquals(3600, intent("start a timer for one hour")?.int("seconds"))
    }

    @Test fun spokenAlarmAcceptsADayPrefix() {
        assertEquals(9, intent("set an alarm for tomorrow at 9")?.int("hour"))
        assertEquals(19, intent("wake me up at 7 pm")?.int("hour"))
    }

    // --- Notes and tasks ---------------------------------------------------

    @Test fun createsNotes() {
        assertEquals("buy milk", intent("create a note saying buy milk")?.str("content"))
        assertEquals("parking spot is B4", intent("note: parking spot is B4")?.str("content"))
        assertEquals("wifi password", intent("write down the wifi password")?.str("content"))
        assertEquals(ActionType.LIST_NOTES, intent("show my notes")?.action)
    }

    @Test fun deletingANoteAlwaysConfirms() {
        val del = intent("delete the note about milk")!!
        assertEquals(ActionType.DELETE_NOTE, del.action)
        assertEquals("milk", del.str("query"))
        assertTrue(del.requiresConfirmation)
    }

    @Test fun createsTasksWithADueDate() {
        val task = intent("add a task to submit the assignment tomorrow")!!
        assertEquals(ActionType.CREATE_TASK, task.action)
        assertEquals("submit the assignment", task.str("title"))
        assertEquals("tomorrow", task.str("due"))
        assertEquals(ActionType.LIST_TASKS, intent("show my tasks")?.action)
        assertEquals(ActionType.COMPLETE_TASK, intent("mark the task about milk as done")?.action)
    }

    // --- Search, weather, calendar, bluetooth, share -----------------------

    @Test fun plainSearchGoesToTheWebNotToMaps() {
        assertEquals(ActionType.WEB_SEARCH, intent("search for train times to Delhi")?.action)
        // The maps rules still win for local searches.
        assertEquals(ActionType.OPEN_MAPS, intent("search for restaurants near me")?.action)
        assertEquals(ActionType.OPEN_MAPS, intent("search for pizza on maps")?.action)
    }

    @Test fun weatherCalendarBluetoothAndShare() {
        assertEquals("Tokyo", intent("what's the weather in Tokyo")?.str("location"))
        assertEquals(ActionType.OPEN_WEATHER, intent("show weather")?.action)
        assertEquals(ActionType.READ_CALENDAR, intent("what's on my calendar")?.action)
        assertEquals("on", intent("turn on bluetooth")?.str("state"))
        assertEquals(ActionType.OPEN_SETTINGS, intent("open bluetooth settings")?.action)
        assertEquals(ActionType.SHARE_TEXT, intent("share my location with the team")?.action)
    }

    // --- Memory and destructive clears -------------------------------------

    @Test fun memoryIsRecognisedAsAServerCommand() {
        assertEquals(
            ParsedCommand.Memory("my exam is on Monday"),
            CommandParser.parse("Remember that my exam is on Monday")
        )
        assertEquals(ParsedCommand.MemoryList, CommandParser.parse("what did I ask you to remember?"))
        assertEquals(
            ParsedCommand.MemoryForget("my exam is on Monday"),
            CommandParser.parse("forget that my exam is on Monday")
        )
    }

    @Test fun forgettingInTheMiddleOfASentenceIsNotADelete() {
        assertNull(CommandParser.parse("write an email to my boss and forget nothing"))
    }

    @Test fun clearingConversationsAlwaysConfirms() {
        val cleared = intent("clear all my conversations")!!
        assertEquals(ActionType.CLEAR_CONVERSATIONS, cleared.action)
        assertTrue(cleared.requiresConfirmation)
    }
}

