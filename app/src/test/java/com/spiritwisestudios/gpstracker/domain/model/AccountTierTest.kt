package com.spiritwisestudios.gpstracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTierTest {

    @Test
    fun `stored names round-trip`() {
        assertEquals(AccountTier.STANDARD, AccountTier.fromStorage("STANDARD"))
        assertEquals(AccountTier.PREMIUM, AccountTier.fromStorage("PREMIUM"))
    }

    @Test
    fun `unknown or missing names fall back to standard`() {
        assertEquals(AccountTier.STANDARD, AccountTier.fromStorage(null))
        assertEquals(AccountTier.STANDARD, AccountTier.fromStorage(""))
        assertEquals(AccountTier.STANDARD, AccountTier.fromStorage("GOLD"))
    }
}
