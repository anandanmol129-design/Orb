package de.mm20.launcher2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeGrammarTest {
    @Test
    fun defaultPhraseUsesHowTheModelHearsOrby() {
        val grammar = wakeGrammar("Hey Orby")
        assertEquals("hey or be", grammar.target)
        assertEquals(
            """["hey or be","hey ruby","hey bobby","hey robbie","hey there","hey you","hey","or be","[unk]"]""",
            grammar.grammarJson,
        )
    }

    @Test
    fun matchesOnlyTheWholePhrase() {
        val grammar = wakeGrammar("Hey Orby")
        assertTrue(grammar.matches("hey or be"))
        assertTrue(grammar.matches("[unk] hey or be [unk]"))
        assertFalse(grammar.matches("hey ruby"))
        assertFalse(grammar.matches("hey"))
        assertFalse(grammar.matches("or be"))
    }

    @Test
    fun customPhrasesAreKeptAsSaid() {
        val grammar = wakeGrammar("OK  Computer!")
        assertEquals("ok computer", grammar.target)
        assertTrue(grammar.matches("ok computer"))
    }
}
