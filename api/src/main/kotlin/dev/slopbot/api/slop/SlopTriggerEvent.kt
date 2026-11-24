package dev.slopbot.api.slop

/**
 * A stream event as delivered by the worker's Twitch connections. The field set
 * mirrors the reference implementation's trigger events (`lib/slop.js`).
 */
data class SlopTriggerEvent(
    var type: String = "chat",
    var userId: String? = null,
    var login: String? = null,
    var displayName: String? = null,
    var text: String? = null,
    var mod: Boolean = false,
    var broadcaster: Boolean = false,
    var first: Boolean = false,
    var viewers: Int? = null,
    var bits: Int? = null,
    var tier: Int? = null,
    var months: Int? = null,
    var count: Int? = null,
    var rewardId: String? = null,
    /** Synthetic dispatches (the deck's trigger test) never consume first-ever slots. */
    var synthetic: Boolean = false,
)
