package dev.slopbot.element.engine

import dev.slopbot.element.model.Participant
import dev.slopbot.element.model.Trigger

/**
 * The registry entry — the engine's live view of one slop. The DB is
 * authoritative for the streamer-saved state (title/author, triggers,
 * cooldown, !name command, suspension, moderation) — the reference's
 * `store.entryFields` merged over the meta-derived fields.
 */
data class RegistryEntry(
    var id: String = "",
    var name: String = "",
    var title: String = "",
    var author: String = "anonymous",
    var short: String = "",
    var summary: String? = null,
    var controls: String? = null,
    var moderationLevel: String? = null,
    var suspended: Boolean = false,
    var nsfw: Boolean = false,
    var triggers: List<Trigger> = emptyList(),
    var cooldownSeconds: Int = 60,
    var cooldownUntil: Long = 0,
    var slopCommand: Boolean = true,
    var pending: Boolean = false,
    var creator: Participant? = null,
    var streamer: Participant? = null,
    var createdAt: Long? = null,
    var playCount: Long = 0,
)
