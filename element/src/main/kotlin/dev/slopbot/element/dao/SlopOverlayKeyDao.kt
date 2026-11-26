package dev.slopbot.element.dao

import dev.slopbot.element.model.OverlayKey

/** The per-streamer overlay-key store. */
interface SlopOverlayKeyDao {
    fun findByStreamer(streamer: String): OverlayKey?
    fun put(key: OverlayKey): OverlayKey
    fun delete(streamer: String)
}
