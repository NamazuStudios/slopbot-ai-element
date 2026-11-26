package dev.slopbot.element.dao

import dev.slopbot.element.model.OverlayKey
import dev.slopbot.element.spi.SlopbotDatabase
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class StandardSlopOverlayKeyDao @Inject constructor(
    private val database: SlopbotDatabase,
) : SlopOverlayKeyDao {

    private val datastore get() = database.datastore

    init {
        datastore.ensureIndexes()
    }

    override fun findByStreamer(streamer: String): OverlayKey? =
        datastore.find(OverlayKey::class.java)
            .filter(dev.morphia.query.filters.Filters.eq("streamer", streamer.lowercase()))
            .first()

    override fun put(key: OverlayKey): OverlayKey {
        datastore.save(key)
        return key
    }

    override fun delete(streamer: String) {
        datastore.find(OverlayKey::class.java)
            .filter(dev.morphia.query.filters.Filters.eq("streamer", streamer.lowercase()))
            .delete()
    }
}
