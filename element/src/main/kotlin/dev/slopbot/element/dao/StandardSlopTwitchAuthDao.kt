package dev.slopbot.element.dao

import dev.slopbot.element.model.TwitchAuth
import dev.slopbot.element.spi.SlopbotDatabase
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class StandardSlopTwitchAuthDao @Inject constructor(
    private val database: SlopbotDatabase,
) : SlopTwitchAuthDao {

    private val datastore get() = database.datastore

    override fun findByStreamer(streamer: String): TwitchAuth? =
        datastore.find(TwitchAuth::class.java)
            .filter(dev.morphia.query.filters.Filters.eq("streamer", streamer.lowercase()))
            .first()

    override fun put(auth: TwitchAuth): TwitchAuth {
        datastore.save(auth)
        return auth
    }

    override fun delete(streamer: String) {
        datastore.find(TwitchAuth::class.java)
            .filter(dev.morphia.query.filters.Filters.eq("streamer", streamer.lowercase()))
            .delete()
    }
}
