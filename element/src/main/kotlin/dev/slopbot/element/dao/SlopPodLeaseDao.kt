package dev.slopbot.element.dao

import dev.slopbot.element.model.PodLease
import dev.slopbot.element.model.SlopbotDatabaseNames

/**
 * The pod-lease DAO — the one-pod-per-streamer arbiter's storage. The ELM is
 * the single writer; pods never touch this directly (they speak the register
 * route, which writes through this).
 */
interface SlopPodLeaseDao {

    /** The ACTIVE or DISPATCHED lease for the streamer, or null. */
    fun liveLease(streamer: String): PodLease?

    fun put(lease: PodLease): PodLease

    fun delete(streamer: String)

    /** The database name (for the test double's parity with the real one). */
    companion object {
        const val DATABASE = SlopbotDatabaseNames.SLOPBOT
    }
}
