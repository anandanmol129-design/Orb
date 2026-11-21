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
        assertCall("Call Priyanshu Mummy", "orb.calls.phone", "priyanshu mummy")
        assertCall("make a call to JSS Priyanshu", "orb.calls.phone", "jss priyanshu")
        assertCall("phone mom.", "orb.calls.phone", "mom")
    }

    @Test
    fun whatsappVoiceCalls() {
        assertCall("call Priyanshu Mummy on WhatsApp", "orb.calls.whatsapp_voice", "priyanshu mummy")
        assertCall("WhatsApp call jss priyanshu ise", "orb.calls.whatsapp_voice", "jss priyanshu ise")
        assertCall("whatsapp voice call mom", "orb.calls.whatsapp_voice", "mom")
        assertCall("voice call mom on whats app", "orb.calls.whatsapp_voice", "mom")
    }

    @Test
    fun whatsappVideoCalls() {
        assertCall("video call mom", "orb.calls.whatsapp_video", "mom")
        assertCall("video call mom on WhatsApp", "orb.calls.whatsapp_video", "mom")
        assertCall("WhatsApp video call jss priyanshu", "orb.calls.whatsapp_video", "jss priyanshu")
    }

    @Test
    fun whatsappChats() {
        assertCall("message mom on WhatsApp", "orb.calls.whatsapp_chat", "mom")
        assertCall("open WhatsApp chat with Priyanshu Mummy", "orb.calls.whatsapp_chat", "priyanshu mummy")
        assertCall("whatsapp priyanshu mummy", "orb.calls.whatsapp_chat", "priyanshu mummy")
    }

    @Test
    fun openingWhatsAppStillOpensTheApp() {
        assertEquals("orb.apps.open", parser.parse("open WhatsApp")?.name)
        assertNull(parser.parse("whatsapp")?.takeIf { it.name.startsWith("orb.calls") })
    }
}
