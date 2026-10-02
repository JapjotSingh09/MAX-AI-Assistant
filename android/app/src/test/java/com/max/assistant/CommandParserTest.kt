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
}
