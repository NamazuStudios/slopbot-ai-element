package dev.slopbot.element.model

import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id
import dev.morphia.annotations.Indexed
import dev.morphia.annotations.IndexOptions
import org.bson.types.ObjectId

/**
 * The first-class Slop record — the database authority for a slop (ticket 03's
 * data model, ported to Morphia). One shared `slopbot` database, tenant-scoped
 * by [streamer]. (Morphia 2.4's @Entity has no useDatabase attribute — the
 * element builds its own Datastore on the `slopbot` database; see
 * SlopbotDatabase.)
 */
@Entity("slops")
class Slop {
    @Id
    var id: ObjectId = ObjectId()

    /** Unique system-readable reference, e.g. `savorysailor-tomato-tribunal.js`. */
    @Indexed(options = IndexOptions(unique = true))
    var name: String = ""

    /** The human title (meta.title). */
    var displayName: String = ""

    /** 64-bit ms since epoch. */
    var timestamp: Long = 0

    /** Indexed tags — e.g. `["legacy"]`. */
    var tags: MutableList<String> = mutableListOf()

    /** The chat user who prompted. */
    var creator: Participant? = null

    /** The tenant owner — the streamer whose chat created it. */
    var streamer: Participant? = null

    /** `PENDING|APPROVED|REJECTED|SUSPENDED`. */
    var moderation: String = "PENDING"

    var suspended: Boolean = false

    var triggers: MutableList<Trigger> = mutableListOf()

    var cooldownSeconds: Int = 0

    var slopCommand: Boolean = true

    var playCount: Long = 0

    var lastPlayedAt: Long? = null

    var metadata: Metadata = Metadata()

    /** The source code, in the Large Object store (GridFS). */
    var source: LargeObjectRef? = null

    /** Process remarks: narration, CHAT SUMMARY, build notes. */
    var agentRemarks: MutableList<Remark> = mutableListOf()

    /** Remarks about the contents. */
    var contentRemarks: MutableList<Remark> = mutableListOf()

    companion object {
        const val MODERATION_PENDING = "PENDING"
        const val MODERATION_APPROVED = "APPROVED"
        const val MODERATION_REJECTED = "REJECTED"
        const val MODERATION_SUSPENDED = "SUSPENDED"
    }
}
