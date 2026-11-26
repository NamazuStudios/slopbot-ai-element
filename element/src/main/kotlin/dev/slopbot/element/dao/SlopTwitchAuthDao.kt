package dev.slopbot.element.dao

import dev.slopbot.element.model.TwitchAuth

/** The per-streamer Twitch bot-authorization store. */
interface SlopTwitchAuthDao {
    fun findByStreamer(streamer: String): TwitchAuth?
    fun put(auth: TwitchAuth): TwitchAuth
    fun delete(streamer: String)
}
