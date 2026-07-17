package com.spiritwisestudios.gpstracker.data.repository

import com.spiritwisestudios.gpstracker.domain.model.AccountTier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account tier currently in effect, readable synchronously anywhere in
 * the app. Seeded from the persisted preference at startup (see
 * GPSTrackerApplication) and updated when the tier changes — the debug
 * testing toggle today, a Play Billing purchase later — so ad gating and
 * narration routing never need an async preference read on the hot path.
 */
@Singleton
class AccountTierHolder @Inject constructor() {

    @Volatile
    var current: AccountTier = AccountTier.STANDARD
        private set

    val isPremium: Boolean
        get() = current == AccountTier.PREMIUM

    fun set(tier: AccountTier) {
        current = tier
    }
}
