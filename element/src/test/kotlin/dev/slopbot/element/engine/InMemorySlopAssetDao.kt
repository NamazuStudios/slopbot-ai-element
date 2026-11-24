package dev.slopbot.element.engine

import dev.slopbot.element.dao.InMemorySourceStore
import dev.slopbot.element.dao.SlopAssetDao
import dev.slopbot.element.model.SlopAsset

/**
 * The in-memory SlopAsset DAO — the tests' asset-store double. Mirrors the
 * write-through semantics of the standard DAO (including the pinned-revision
 * replace) without MongoDB.
 */
class InMemorySlopAssetDao : SlopAssetDao {

    val store = InMemorySourceStore()
    private val assets = java.util.concurrent.ConcurrentHashMap<String, SlopAsset>()

    override fun list(tag: String?): List<SlopAsset> =
        assets.values.filter { tag == null || it.tags.contains(tag) }

    override fun findByName(name: String): SlopAsset? = assets[name]

    override fun findById(id: String): SlopAsset? =
        assets.values.find { it.id.toHexString() == id }

    override fun put(asset: SlopAsset, bytes: ByteArray): SlopAsset {
        val ref = store.put(asset.name, asset.mimeType, asset.metadata.originalFilename, bytes)
        asset.source = ref
        asset.timestamp = if (asset.timestamp > 0) asset.timestamp else System.currentTimeMillis()
        assets[asset.name] = asset
        return asset
    }

    override fun replaceBytes(name: String, bytes: ByteArray): SlopAsset? {
        val asset = assets[name] ?: return null
        val previous = asset.source
        val ref = store.put(asset.name, asset.mimeType, asset.metadata.originalFilename, bytes)
        // The previous revision is pinned: kept in the store and in the record.
        if (previous != null) asset.revisions.add(previous)
        asset.source = ref
        return asset
    }

    override fun readBytes(asset: SlopAsset): ByteArray? = store.get(asset.source)

    override fun setState(name: String, state: String): SlopAsset? {
        val asset = assets[name] ?: return null
        asset.state = state
        return asset
    }

    override fun delete(name: String): Boolean = assets.remove(name) != null
}
