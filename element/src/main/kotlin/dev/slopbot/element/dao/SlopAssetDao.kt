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

    /** Upserts the asset; the bytes go to the [SourceStore]. */
    fun put(asset: SlopAsset, bytes: ByteArray): SlopAsset

    /** The asset's bytes through its source ref. */
    fun readBytes(asset: SlopAsset): ByteArray?

    fun delete(name: String): Boolean
}
