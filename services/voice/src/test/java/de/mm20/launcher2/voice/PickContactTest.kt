package de.mm20.launcher2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickContactTest {
    private val contacts = listOf(
        "Priyanshu Mummy",
        "JSS Priyanshu  ISE",
        "JSS Priyanshu Kumar",
        "JSS Priyanshu PG",
    )

    private fun pick(query: String) = pickContact(query, contacts) { it }

    @Test
    fun fullNameActsDirectly() {
        assertEquals("JSS Priyanshu  ISE", pick("jss priyanshu ise"))
        assertEquals("Priyanshu Mummy", pick("Priyanshu Mummy"))
    }

    @Test
    fun uniqueFirstTwoNamesActDirectly() {
        assertEquals("Priyanshu Mummy", pick("priyanshu mummy ji"))
    }

    @Test
    fun ambiguousNamesShowOptions() {
        assertNull(pick("priyanshu"))
        assertNull(pick("jss priyanshu"))
        assertNull(pick("someone else"))
    }
}
