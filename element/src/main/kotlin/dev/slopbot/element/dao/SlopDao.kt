package dev.slopbot.element.dao

import dev.slopbot.element.model.Participant
import dev.slopbot.element.model.Remark
import dev.slopbot.element.model.Slop
import dev.slopbot.element.model.Trigger

/**
 * The write-through patch shape of the reference `updateSlop` (`lib/store.js`):
 * never touches the source ref. `pending` maps onto `moderation`.
 */
data class SlopPatch(
    val triggers: List<Trigger>? = null,
    val cooldownSeconds: Int? = null,
    val slopCommand: Boolean? = null,
    val suspended: Boolean? = null,
    val pending: Boolean? = null,
    val title: String? = null,
    val moderation: String? = null,
    val tags: List<String>? = null,
    val agentRemarks: List<Remark>? = null,
    val contentRemarks: List<Remark>? = null,
)

/**
 * The Slop DAO — tenant-scoped queries (`streamer.login`) and the write-through
 * semantics ported from `lib/store.js`: register, triggers save, suspend,
 * cooldown changes, verdicts, `$inc` play statistics; the source is written to
 * the [SourceStore] on every registration.
 */
interface SlopDao {

    /** Every slop, newest first. */
    fun list(): List<Slop>

    /** Every slop owned by a streamer (tenant scope: `streamer.login`). */
    fun findByStreamer(streamerLogin: String): List<Slop>

    /** One slop by its unique name. */
    fun findByName(name: String): Slop?

    /** All known names — the naming collision check. */
    fun names(): Set<String>

    /**
     * The write-through upsert (`store.putSlop`): new builds and modified
     * builds both land here. The creator is the chat user who prompted; the
     * streamer is the tenant owner. The source bytes go to the [SourceStore]
     * and the ref lands on the record.
     */
    fun put(
        slop: Slop,
        source: String,
        tags: List<String> = emptyList(),
        streamerLogin: String? = null,
    ): Slop

    /** The partial write-through (`store.updateSlop`). */
    fun update(name: String, patch: SlopPatch): Slop?

    /** `$inc` play statistics (`store.bumpPlay`). */
    fun bumpPlay(name: String): Boolean

    /**
     * Deletes the record, its source bytes, and its own (non-shared) assets.
     */
    fun delete(name: String): Boolean

    /** Reads the slop's source through its ref (the DB ↔ playback round-trip). */
    fun readSource(name: String): String?
}
