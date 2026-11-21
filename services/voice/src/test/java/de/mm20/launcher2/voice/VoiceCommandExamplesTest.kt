package de.mm20.launcher2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandExamplesTest {
    private val parser = LocalIntentParser()

    @Test
    fun everyTutorialExampleParsesToItsTool() {
        voiceCommandGroups.flatMap { it.examples }.forEach { example ->
            assertEquals(example.phrase, example.tool, parser.parse(example.phrase)?.name)
        }
    }

    @Test
    fun everyGroupHasExamples() {
        assertTrue(voiceCommandGroups.all { it.examples.isNotEmpty() })
    }
}
