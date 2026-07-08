package com.spiritwisestudios.gpstracker.domain.service

/**
 * Reports whether the active network is unmetered (Wi-Fi, ethernet).
 * Kept as a fun interface so tests and callers can stub it trivially.
 */
fun interface ConnectivityChecker {
    fun isOnUnmeteredNetwork(): Boolean
}
