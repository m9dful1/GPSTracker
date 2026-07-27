package com.spiritwisestudios.gpstracker.ads

import com.spiritwisestudios.gpstracker.ads.ConsentPolicy.ConsentState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What consent means for an ad request.
 *
 * The rule was three copies of `consentStatus != OBTAINED` inside a singleton
 * that takes an `Activity`, so there was nothing to call and nothing to check —
 * and it was wrong in the one case nobody thinks about.
 */
class ConsentPolicyTest {

    @Test
    fun `no consent region means personalized ads are fine`() {
        // The case that was wrong: NOT_REQUIRED does not mean refused, it
        // means there was nobody to ask. Most of the world reports this, and
        // every request there used to carry npa=1 forever.
        assertFalse(ConsentPolicy.useNonPersonalizedAds(ConsentState.NOT_REQUIRED))
    }

    @Test
    fun `a listener who agreed gets personalized ads`() {
        assertFalse(ConsentPolicy.useNonPersonalizedAds(ConsentState.OBTAINED))
    }

    @Test
    fun `consent that is needed and not given means non-personalized`() {
        assertTrue(ConsentPolicy.useNonPersonalizedAds(ConsentState.REQUIRED))
    }

    @Test
    fun `not knowing means non-personalized`() {
        // The SDK could not say, or has not been asked yet. Anything UMP adds
        // later lands here too, and the careful answer is the right default.
        assertTrue(ConsentPolicy.useNonPersonalizedAds(ConsentState.UNKNOWN))
    }

    @Test
    fun `every state has an answer, and only two of them allow personalizing`() {
        // A new state added without a decision would fail to compile in the
        // policy; this pins the shape of the answer itself.
        val personalized = ConsentState.entries.filterNot(ConsentPolicy::useNonPersonalizedAds)

        assertTrue(personalized.containsAll(listOf(ConsentState.OBTAINED, ConsentState.NOT_REQUIRED)))
        assertTrue(personalized.size == 2)
    }
}
