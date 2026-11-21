package de.mm20.launcher2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeGrammarTest {
    @Test
    fun defaultPhrase() {
        val grammar = wakeGrammar(VoiceSettings.DEFAULT_WAKE_PHRASE)
        assertEquals(listOf("hey orb", "hey you orb"), grammar.targets)
        assertEquals(
            """["hey orb","hey you orb","hey bob","hey rob","hey or","hey all","hey ruby","hey bobby",""" +
                """"hey robbie","hey there","hey you","hey","orb","[unk]"]""",
            grammar.grammarJson,
        )
    }

    @Test
    fun matchesOnlyTheWholePhrase() {
        val grammar = wakeGrammar("Hey Orb")
        assertTrue(grammar.matches("hey orb"))
        assertTrue(grammar.matches("hey you orb"))
        assertTrue(grammar.matches("[unk] hey orb [unk]"))
        assertFalse(grammar.matches("hey bob"))
        assertFalse(grammar.matches("hey you"))
        assertFalse(grammar.matches("orb"))
    }

    @Test
    fun orbyUsesHowTheModelHearsIt() {
        val grammar = wakeGrammar("Hey Orby")
        assertEquals(listOf("hey or be", "hey you or be"), grammar.targets)
        assertTrue(grammar.matches("hey or be"))
    }

    @Test
    fun customPhrasesAreKeptAsSaid() {
        val grammar = wakeGrammar("OK  Computer!")
        assertEquals(listOf("ok computer"), grammar.targets)
        assertTrue(grammar.matches("ok computer"))
    }
}
