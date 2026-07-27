package com.spiritwisestudios.gpstracker.ads

/**
 * What UMP's consent status means for the ads this app requests.
 *
 * The status arrives as an int from the ad SDK. `ConsentManager` translates it
 * using UMP's own constants — so the compiler checks that mapping — and this
 * file stays free of the SDK, which is what makes the rule testable. Anything
 * UMP adds later lands on [ConsentState.UNKNOWN] and gets the careful answer.
 */
object ConsentPolicy {

    /**
     * The four answers UMP gives about consent.
     *
     * [NOT_REQUIRED] is the one worth naming carefully: it does not mean
     * consent was refused, it means none was needed — the listener is outside
     * a consent region, and there is nothing to ask them.
     */
    enum class ConsentState {
        /** Not yet determined, or the SDK could not say. */
        UNKNOWN,

        /** No consent is needed here. */
        NOT_REQUIRED,

        /** Consent is needed and has not been given. */
        REQUIRED,

        /** The listener answered the form. */
        OBTAINED
    }

    /**
     * Whether an ad request must carry `npa=1`.
     *
     * Personalized advertising is allowed in two cases: the listener agreed to
     * it, or nobody had to ask. It used to be allowed in one — `OBTAINED` —
     * which meant every request outside a consent region, most of the world,
     * was marked non-personalized forever. That erred in the listener's favour
     * and cost the developer silently: no crash, no log, only revenue that
     * never arrived.
     */
    fun useNonPersonalizedAds(state: ConsentState): Boolean = when (state) {
        ConsentState.OBTAINED, ConsentState.NOT_REQUIRED -> false
        ConsentState.REQUIRED, ConsentState.UNKNOWN -> true
    }
}
