package dev.slopbot.element.dao

import dev.slopbot.element.model.SlopAsset

/**
 * The SlopAsset DAO — the shared asset pool (tagged `shared`) and per-slop
 * assets (tagged `["slop", "<slop-name>"]`), with the same write-through
 * semantics as [SlopDao].
 */
interface SlopAssetDao {

    /** Every asset, optionally filtered by an indexed tag. */
    fun list(tag: String? = null): List<SlopAsset>

    fun findByName(name: String): SlopAsset?

    /** One asset by its record id (the hex ObjectId). */
    fun findById(id: String): SlopAsset?

    /** Upserts the asset; the bytes go to the [SourceStore]. */
    fun put(asset: SlopAsset, bytes: ByteArray): SlopAsset

    /**
     * Replaces the asset's bytes: a NEW LargeObject per revision, the previous
     * revision pinned (kept in the store and in the record's `revisions`
     * history — slops pinned to the old revision keep working).
     */
    fun replaceBytes(name: String, bytes: ByteArray): SlopAsset?

    /** The asset's bytes through its source ref. */
    fun readBytes(asset: SlopAsset): ByteArray?

    /** The write-through state flip (`ACTIVE` ↔ `RETIRED`). */
    fun setState(name: String, state: String): SlopAsset?

    fun delete(name: String): Boolean
}
