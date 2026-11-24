package dev.slopbot.element.model

import dev.morphia.annotations.Embedded


/**
 * A Twitch participant — the chat user who prompted (creator) or the tenant owner
 * (streamer). Embedded in [Slop] documents.
 */
@Embedded
class Participant(
    var id: String? = null,
    var login: String? = null,
    var displayName: String? = null,
) {
    override fun toString(): String = "Participant(id=$id, login=$login, displayName=$displayName)"
}
