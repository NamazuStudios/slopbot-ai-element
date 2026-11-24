package dev.slopbot.api.slop

/**
 * The playback/registration state of one slop as seen in the registry snapshot.
 * Mirrors the reference implementation's registry entry (slopbot-ai `lib/slop.js`).
 */
data class SlopSummary(
    var name: String = "",
    var title: String = "",
    var author: String = "",
    var short: String = "",
    var suspended: Boolean = false,
    var pending: Boolean = false,
    var moderationLevel: String? = null,
    var cooldownSeconds: Int = 0,
    var cooldownUntil: Long = 0,
    var slopCommand: Boolean = true,
    var playCount: Long = 0,
    var createdAt: Long? = null,
    var summary: String? = null,
)

/**
 * A queued play waiting for (or on) the stage.
 */
data class PlayItem(
    var playId: String = "",
    var name: String = "",
    var title: String = "",
    var author: String = "",
    var via: String = "",
    var startedAt: Long = 0,
    var chained: Boolean = false,
)

/**
 * The status snapshot served by `GET /slop/api/slop` — registry, queue, cooldowns,
 * config echo, and connection liveness.
 */
data class SlopSnapshot(
    var registered: List<SlopSummary> = emptyList(),
    var queue: List<PlayItem> = emptyList(),
    var current: PlayItem? = null,
    var upcoming: List<PlayItem> = emptyList(),
    var paused: Boolean = false,
    var pausedPlay: PlayItem? = null,
    var cooldownUntil: Map<String, Long> = emptyMap(),
    var config: Map<String, Any?> = emptyMap(),
    var ircConnected: Boolean = false,
    var lastTrafficAgoMs: Long? = null,
)
