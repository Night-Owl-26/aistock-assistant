package com.aistock.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test
    fun theCommandAfterTheNameIsKept() {
        assertEquals("add 10 metres fabric", WakePhrase.find("Hey Stocky add 10 metres fabric"))
        assertEquals("how much blue cotton", WakePhrase.find("ok stockie, how much blue cotton?"))
        assertEquals("", WakePhrase.find("hey stocky"))
        assertEquals("fabric evlo irukku", WakePhrase.find("Stocky fabric evlo irukku"))
        assertEquals("துணி எவ்வளவு இருக்கு", WakePhrase.find("ஹே ஸ்டாக்கி துணி எவ்வளவு இருக்கு"))
    }

    @Test
    fun ordinaryTalkDoesNotWakeIt() {
        assertNull(WakePhrase.find("we need more stock today"))
        assertNull(WakePhrase.find("the stocks are low"))
        assertNull(WakePhrase.find("hey stockholder meeting"))
        assertNull(WakePhrase.find(""))
    }

    @Test
    fun stopWords() {
        assertTrue(WakePhrase.isStop("Bye Stocky."))
        assertTrue(WakePhrase.isStop("போதும்"))
    }
}
