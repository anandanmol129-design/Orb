package de.mm20.launcher2.voice

import de.mm20.launcher2.agent.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIntentParserTest {
    private val parser = LocalIntentParser()

    private fun assertCall(text: String, tool: String, contact: String) {
        val result = parser.parse(text)
        assertEquals(text, tool, result?.name)
        assertEquals(text, contact, result?.args?.string("contact"))
    }

    @Test
    fun phoneCalls() {
        assertCall("Call Sam Patel", "orb.calls.phone", "sam patel")
        assertCall("make a call to Work Sam", "orb.calls.phone", "work sam")
        assertCall("phone mom.", "orb.calls.phone", "mom")
    }

    @Test
    fun whatsappVoiceCalls() {
        assertCall("call Sam Patel on WhatsApp", "orb.calls.whatsapp_voice", "sam patel")
        assertCall("WhatsApp call work sam ise", "orb.calls.whatsapp_voice", "work sam ise")
        assertCall("whatsapp voice call mom", "orb.calls.whatsapp_voice", "mom")
        assertCall("voice call mom on whats app", "orb.calls.whatsapp_voice", "mom")
    }

    @Test
    fun whatsappVideoCalls() {
        assertCall("video call mom", "orb.calls.whatsapp_video", "mom")
        assertCall("video call mom on WhatsApp", "orb.calls.whatsapp_video", "mom")
        assertCall("WhatsApp video call work sam", "orb.calls.whatsapp_video", "work sam")
    }

    @Test
    fun whatsappChats() {
        assertCall("message mom on WhatsApp", "orb.calls.whatsapp_chat", "mom")
        assertCall("open WhatsApp chat with Sam Patel", "orb.calls.whatsapp_chat", "sam patel")
        assertCall("whatsapp sam patel", "orb.calls.whatsapp_chat", "sam patel")
    }

    @Test
    fun openingWhatsAppStillOpensTheApp() {
        assertEquals("orb.apps.open", parser.parse("open WhatsApp")?.name)
        assertNull(parser.parse("whatsapp")?.takeIf { it.name.startsWith("orb.calls") })
    }
}
