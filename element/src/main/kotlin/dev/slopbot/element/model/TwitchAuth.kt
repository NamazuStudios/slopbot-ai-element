package dev.slopbot.element.model

import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id
import org.bson.types.ObjectId

/**
 * The per-streamer Twitch bot authorization (Pod Boot Mechanics §credentials):
 * the streamer authorizes the bot's Twitch app once (chat:read, chat:edit,
 * EventSub channel scopes) and the ELM holds the token for dispatch.
 */
@Entity("twitch_auth")
class TwitchAuth {
    @Id
    var id: ObjectId = ObjectId()

    /** The streamer login (the tenant key). */
    var streamer: String = ""

    /** The token exchange result — the bot's operating credentials. */
    var accessToken: String = ""
    var refreshToken: String = ""
    var scope: String = ""

    /** The authorized Twitch account — the broadcaster for EventSub. */
    var broadcasterId: String = ""
    var broadcasterLogin: String = ""
    var broadcasterDisplayName: String = ""

    var obtainedAt: Long = 0
    var expiresAt: Long = 0
}
