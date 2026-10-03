package com.max.assistant

import com.max.assistant.services.VoiceSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The voice state machine is the guard against duplicate commands and runaway
 * recognition, so its rules are tested directly rather than only through the UI.
 */
class VoiceSessionTest {

    @Before fun setUp() = VoiceSession.reset()

    @After fun tearDown() = VoiceSession.reset()

    @Test
    fun `starts idle with the microphone closed`() {
        assertEquals(VoiceSession.State.IDLE, VoiceSession.state)
        assertFalse(VoiceSession.isListening)
        assertFalse(VoiceSession.isBusy)
    }

    @Test
    fun `follows the intended lifecycle`() {
        assertTrue(VoiceSession.moveTo(VoiceSession.State.WAKE_DETECTED))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.LISTENING))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.PROCESSING))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.EXECUTING))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.RESPONDING))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.IDLE))
    }

    /** The core duplicate-command defence. */
    @Test
    fun `a second wake word while already handling one is rejected`() {
        assertTrue(VoiceSession.moveTo(VoiceSession.State.WAKE_DETECTED))
        // A repeated "hey max" must not re-enter the same state...
        assertFalse(VoiceSession.moveTo(VoiceSession.State.WAKE_DETECTED))
        assertFalse(VoiceSession.moveTo(VoiceSession.State.WAKE_DETECTED))
    }

    @Test
    fun `cannot skip straight from idle to executing`() {
        assertFalse(VoiceSession.moveTo(VoiceSession.State.EXECUTING))
        assertEquals(VoiceSession.State.IDLE, VoiceSession.state)
    }

    @Test
    fun `cannot start a second listening session while one is running`() {
        assertTrue(VoiceSession.moveTo(VoiceSession.State.LISTENING))
        assertFalse("A second recogniser must not be opened", VoiceSession.moveTo(VoiceSession.State.LISTENING))
        assertTrue(VoiceSession.isListening)
    }

    @Test
    fun `busy states block a new command`() {
        assertTrue(VoiceSession.moveTo(VoiceSession.State.LISTENING))
        assertFalse("Listening itself is not busy", VoiceSession.isBusy)
        assertTrue(VoiceSession.moveTo(VoiceSession.State.PROCESSING))
        assertTrue(VoiceSession.isBusy)
        assertTrue(VoiceSession.moveTo(VoiceSession.State.EXECUTING))
        assertTrue(VoiceSession.isBusy)
    }

    @Test
    fun `returning to idle is always allowed, from any state`() {
        assertTrue(VoiceSession.moveTo(VoiceSession.State.LISTENING))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.IDLE))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.LISTENING))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.IDLE))
    }

    @Test
    fun `an error can always be reported`() {
        assertTrue(VoiceSession.moveTo(VoiceSession.State.ERROR))
        VoiceSession.reset()
        assertTrue(VoiceSession.moveTo(VoiceSession.State.LISTENING))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.ERROR))
    }

    @Test
    fun `reset returns the machine to a usable idle state`() {
        assertTrue(VoiceSession.moveTo(VoiceSession.State.LISTENING))
        assertTrue(VoiceSession.moveTo(VoiceSession.State.PROCESSING))
        VoiceSession.reset()
        assertEquals(VoiceSession.State.IDLE, VoiceSession.state)
        // ...and must still be able to accept a new command afterwards.
        assertTrue(VoiceSession.moveTo(VoiceSession.State.LISTENING))
    }
}