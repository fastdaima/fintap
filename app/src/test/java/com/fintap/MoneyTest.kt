package com.fintap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun parse() {
        assertEquals(12000L, parsePaise("120"))
        assertEquals(1250L, parsePaise("12.5"))
        assertEquals(1205L, parsePaise("12.05"))
        assertEquals(50L, parsePaise(".5"))
        assertEquals(1200L, parsePaise("12."))
        assertEquals(120005L, parsePaise(" 1,200.05 "))
        assertEquals(0L, parsePaise("0"))
    }

    @Test fun parseRejects() {
        assertNull(parsePaise(""))
        assertNull(parsePaise("."))
        assertNull(parsePaise("-5"))
        assertNull(parsePaise("1.234"))
        assertNull(parsePaise("1.2.3"))
        assertNull(parsePaise("abc"))
        assertNull(parsePaise("12345678901"))
    }

    @Test fun format() {
        assertEquals("₹0", formatPaise(0))
        assertEquals("₹5", formatPaise(500))
        assertEquals("₹12.50", formatPaise(1250))
        assertEquals("₹999", formatPaise(99900))
        assertEquals("₹1,000", formatPaise(100000))
        assertEquals("₹12,345", formatPaise(1234500))
        assertEquals("₹1,23,456.50", formatPaise(12345650))
        assertEquals("₹12,34,56,789", formatPaise(123456789_00))
    }

    @Test fun edit() {
        assertEquals("12", editPaise(1200))
        assertEquals("12.05", editPaise(1205))
        assertEquals(1205L, parsePaise(editPaise(1205)))
    }
}
