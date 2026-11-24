package dev.slopbot.element.dao

import dev.slopbot.element.model.LargeObjectRef
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [SourceStore] — the test double for [GridFsSourceStore]. Bytes are
 * kept keyed by a synthetic id; references behave identically to the GridFS
 * store's.
 */
class InMemorySourceStore : SourceStore {

    val objects = ConcurrentHashMap<String, Pair<LargeObjectRef, ByteArray>>()

    private var seq = 0L

    override fun put(path: String, mimeType: String, originalFilename: String?, bytes: ByteArray): LargeObjectRef {
        val id = "lo-${seq++}"
        val ref = LargeObjectRef(id, mimeType, originalFilename, "UPLOADED")
        objects[id] = ref to bytes.copyOf()
        return ref
    }

    override fun get(ref: LargeObjectRef?): ByteArray? {
        val id = ref?.id ?: return null
        return objects[id]?.second?.copyOf()
    }

    override fun delete(ref: LargeObjectRef?) {
        val id = ref?.id ?: return
        objects.remove(id)
    }

    override fun sizeOf(ref: LargeObjectRef?): Int {
        val id = ref?.id ?: return 0
        return objects[id]?.second?.size ?: 0
    }
}
