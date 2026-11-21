package de.mm20.launcher2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickContactTest {
    private val contacts = listOf(
        "Sam Patel",
        "Work Sam  ISE",
        "Work Sam Kumar",
        "Work Sam PG",
    )

    private fun pick(query: String) = pickContact(query, contacts) { it }

    @Test
    fun fullNameActsDirectly() {
        assertEquals("Work Sam  ISE", pick("work sam ise"))
        assertEquals("Sam Patel", pick("Sam Patel"))
    }

    @Test
    fun uniqueFirstTwoNamesActDirectly() {
        assertEquals("Sam Patel", pick("sam patel ji"))
    }

    @Test
    fun ambiguousNamesShowOptions() {
        assertNull(pick("sam"))
        assertNull(pick("work sam"))
        assertNull(pick("someone else"))
    }
}
