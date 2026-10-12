package com.github.melancholic.fintrace.importer.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NamesTest {

    @Test
    fun `a name inside Core's rules is unchanged`() {
        assertEquals("Coffee & Tea (home)", sanitizeName("Coffee & Tea (home)"))
        assertEquals("Мои деньги №2", sanitizeName("Мои деньги №2"))
    }

    @Test
    fun `disallowed characters become single spaces`() {
        assertEquals("Еда Продукты", sanitizeName("Еда/Продукты"))
        assertEquals("Dr Smith", sanitizeName("Dr. Smith"))
        assertEquals("Visa Mastercard", sanitizeName("Visa/Mastercard 💳"))
    }

    @Test
    fun `a leading non-letter is dropped`() {
        assertEquals("Savings", sanitizeName("- (Savings"))
    }

    @Test
    fun `a long name is cut to 30 characters`() {
        assertEquals("Groceries and household suppli", sanitizeName("Groceries and household supplies for the month"))
    }

    @Test
    fun `a cut never leaves a trailing space`() {
        assertEquals("Groceries and household abcde", sanitizeName("Groceries and household abcde fgh"))
    }

    @Test
    fun `nothing usable left is null`() {
        assertNull(sanitizeName("!!! ..."))
        assertNull(sanitizeName("💳"))
    }
}
