package dev.slopbot.element.dao

import dev.slopbot.element.model.LargeObjectRef

/**
 * The byte-store seam behind the slop/asset `source` references — GridFS at
 * runtime (the manual's Large Object API shape, ported from the reference
 * `lib/store.js`), in-memory in tests.
 */
interface SourceStore {

    /**
     * Stores the bytes and returns the reference. The reference carries the
     * manual's metadata fields (id, mimeType, state=UPLOADED, originalFilename).
     */
    fun put(path: String, mimeType: String, originalFilename: String?, bytes: ByteArray): LargeObjectRef

    /** Reads the bytes for a reference, or null when absent. */
    fun get(ref: LargeObjectRef?): ByteArray?

    /** Deletes the bytes for a reference (idempotent). */
    fun delete(ref: LargeObjectRef?)

    /** The stored size in bytes for a reference (0 when absent). */
    fun sizeOf(ref: LargeObjectRef?): Int
}
