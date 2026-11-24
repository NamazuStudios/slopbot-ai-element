package dev.slopbot.element.dao

import dev.slopbot.element.spi.SlopbotDatabase
import dev.slopbot.element.model.Participant
import dev.slopbot.element.model.Slop
import dev.slopbot.element.model.SlopAsset
import dev.morphia.query.filters.Filters
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * The standard [SlopDao] — Morphia against the element's `slopbot` database,
 * write-through semantics ported from the reference `lib/store.js`.
 */
@Singleton
class StandardSlopDao @Inject constructor(
    private val database: SlopbotDatabase,
    private val sourceStore: SourceStore,
    private val assetDao: SlopAssetDao,
) : SlopDao {

    private val SLOP_MIME = "text/javascript"

    private val datastore get() = database.datastore

    override fun list(): List<Slop> =
        datastore.find(Slop::class.java)
            .iterator()
            .toList()
            .sortedByDescending { it.timestamp }

    override fun findByStreamer(streamerLogin: String): List<Slop> =
        datastore.find(Slop::class.java)
            .filter(Filters.eq("streamer.login", streamerLogin))
            .iterator()
            .toList()
            .sortedByDescending { it.timestamp }

    override fun findByName(name: String): Slop? =
        datastore.find(Slop::class.java)
            .filter(Filters.eq("name", name))
            .firstOrNull()

    override fun names(): Set<String> =
        datastore.find(Slop::class.java)
            .iterator()
            .toList()
            .map { it.name }
            .toSet()

    override fun put(slop: Slop, source: String, tags: List<String>, streamerLogin: String?): Slop {
        val now = System.currentTimeMillis()
        val previous = findByName(slop.name)
        val sourceRef = sourceStore.put(slop.name, SLOP_MIME, slop.name, source.toByteArray(Charsets.UTF_8))

        // JS putSlop: timestamp: previous?.timestamp ?? entry.createdAt ?? now
        slop.timestamp = previous?.timestamp ?: slop.timestamp.takeIf { it > 0 } ?: now
        // JS: tags: [...new Set([...(previous?.tags ?? []), ...tags])]
        slop.tags = linkedSetOf<String>().apply {
            addAll(previous?.tags ?: emptyList())
            addAll(tags)
        }.toMutableList()
        // JS: creator: entry.creator ?? previous?.creator ?? (entry.author ? {...} : null)
        if (slop.creator == null) slop.creator = previous?.creator
        if (slop.creator == null && slop.metadata.author != null) {
            slop.creator = Participant(
                id = null,
                login = slop.metadata.author,
                displayName = slop.metadata.author,
            )
        }
        // JS: streamer: previous?.streamer ?? { id: null, login: streamer ?? null }
        if (slop.streamer == null) slop.streamer = previous?.streamer
        if (slop.streamer == null) slop.streamer = Participant(id = null, login = streamerLogin, displayName = null)
        // JS: playCount: previous?.playCount ?? 0; lastPlayedAt: previous?.lastPlayedAt ?? null
        slop.playCount = previous?.playCount ?: 0
        slop.lastPlayedAt = previous?.lastPlayedAt
        slop.source = sourceRef

        datastore.save(slop)
        return slop
    }

    override fun update(name: String, patch: SlopPatch): Slop? {
        val slop = findByName(name) ?: return null
        var touched = false
        patch.triggers?.let { slop.triggers = it.toMutableList(); touched = true }
        patch.cooldownSeconds?.let { slop.cooldownSeconds = it; touched = true }
        patch.slopCommand?.let { slop.slopCommand = it; touched = true }
        patch.suspended?.let { slop.suspended = it; touched = true }
        patch.pending?.let { slop.moderation = if (it) Slop.MODERATION_PENDING else Slop.MODERATION_APPROVED; touched = true }
        patch.moderation?.let { slop.moderation = it; touched = true }
        patch.title?.let { slop.displayName = it; touched = true }
        patch.tags?.let { slop.tags = it.toMutableList(); touched = true }
        patch.agentRemarks?.let { slop.agentRemarks = it.toMutableList(); touched = true }
        patch.contentRemarks?.let { slop.contentRemarks = it.toMutableList(); touched = true }
        patch.owner?.let { slop.streamer = it; touched = true }
        patch.creator?.let { slop.creator = it; touched = true }
        if (!touched) return null
        datastore.save(slop)
        return slop
    }

    override fun bumpPlay(name: String): Boolean {
        val slop = findByName(name) ?: return false
        slop.playCount += 1
        slop.lastPlayedAt = System.currentTimeMillis()
        datastore.save(slop)
        return true
    }

    override fun delete(name: String): Boolean {
        val doc = findByName(name) ?: return false
        sourceStore.delete(doc.source)
        datastore.delete(doc)
        // Its own assets go with it (shared pool assets are untouched).
        assetDao.list(name).forEach { assetDao.delete(it.name) }
        return true
    }

    override fun readSource(name: String): String? {
        val doc = findByName(name) ?: return null
        val bytes = sourceStore.get(doc.source ?: return null) ?: return null
        return bytes.toString(Charsets.UTF_8)
    }
}

/**
 * The standard [SlopAssetDao].
 */
@Singleton
class StandardSlopAssetDao @Inject constructor(
    private val database: SlopbotDatabase,
    private val sourceStore: SourceStore,
) : SlopAssetDao {

    private val datastore get() = database.datastore

    override fun list(tag: String?): List<SlopAsset> {
        val query = datastore.find(SlopAsset::class.java)
        if (tag != null) query.filter(Filters.eq("tags", tag))
        return query.iterator().toList()
    }

    override fun findByName(name: String): SlopAsset? =
        datastore.find(SlopAsset::class.java)
            .filter(Filters.eq("name", name))
            .firstOrNull()

    override fun findById(id: String): SlopAsset? = try {
        datastore.find(SlopAsset::class.java)
            .filter(Filters.eq("_id", org.bson.types.ObjectId(id)))
            .firstOrNull()
    } catch (e: IllegalArgumentException) {
        null // not an ObjectId hex string
    }

    override fun put(asset: SlopAsset, bytes: ByteArray): SlopAsset {
        val ref = sourceStore.put(asset.name, asset.mimeType, asset.metadata.originalFilename, bytes)
        asset.source = ref
        asset.timestamp = if (asset.timestamp > 0) asset.timestamp else System.currentTimeMillis()
        datastore.save(asset)
        return asset
    }

    override fun replaceBytes(name: String, bytes: ByteArray): SlopAsset? {
        val asset = findByName(name) ?: return null
        val previous = asset.source
        val ref = sourceStore.put(asset.name, asset.mimeType, asset.metadata.originalFilename, bytes)
        // The previous revision is pinned: it stays in the store and in the
        // record's history — never deleted, so anything holding the old ref
        // (a bundle already inlined, an MCP `assets.read` id in flight) keeps
        // working against the old revision.
        if (previous != null) asset.revisions.add(previous)
        asset.source = ref
        datastore.save(asset)
        return asset
    }

    override fun readBytes(asset: SlopAsset): ByteArray? = sourceStore.get(asset.source)

    override fun setState(name: String, state: String): SlopAsset? {
        val asset = findByName(name) ?: return null
        asset.state = state
        datastore.save(asset)
        return asset
    }

    override fun delete(name: String): Boolean {
        val asset = findByName(name) ?: return false
        sourceStore.delete(asset.source)
        datastore.delete(asset)
        return true
    }
}
