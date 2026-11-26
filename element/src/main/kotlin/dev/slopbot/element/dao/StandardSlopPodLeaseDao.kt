package dev.slopbot.element.dao

import dev.slopbot.element.model.PodLease
import dev.slopbot.element.spi.SlopbotDatabase
import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.bson.types.ObjectId

/**
 * The Morphia implementation over the shared `slopbot` database (the
 * pre-mapped [PodLease]). The lease doc's unique key is the STREAMER —
 * enforced by a unique index (one pod per streamer, structurally).
 */
@Singleton
class StandardSlopPodLeaseDao @Inject constructor(
    private val database: SlopbotDatabase,
) : SlopPodLeaseDao {

    private val datastore get() = database.datastore

    init {
        datastore.ensureIndexes()
    }

    override fun liveLease(streamer: String): PodLease? =
        datastore.find(PodLease::class.java)
            .filter(dev.morphia.query.filters.Filters.eq("streamer", streamer.lowercase()))
            .filter(dev.morphia.query.filters.Filters.ne("status", PodLease.STATUS_RELEASED))
            .first()

    override fun put(lease: PodLease): PodLease {
        datastore.save(lease)
        return lease
    }

    override fun delete(streamer: String) {
        datastore.find(PodLease::class.java)
            .filter(dev.morphia.query.filters.Filters.eq("streamer", streamer.lowercase()))
            .delete()
    }
}

/** The in-memory double for tests. */
class InMemorySlopPodLeaseDao : SlopPodLeaseDao {
    private val leases = java.util.concurrent.ConcurrentHashMap<String, PodLease>()

    override fun liveLease(streamer: String): PodLease? =
        leases[streamer.lowercase()]?.takeIf { it.status != PodLease.STATUS_RELEASED }

    override fun put(lease: PodLease): PodLease {
        leases[lease.streamer.lowercase()] = lease
        return lease
    }

    override fun delete(streamer: String) {
        leases.remove(streamer.lowercase())
    }
}
