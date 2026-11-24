package dev.slopbot.element.engine

import dev.slopbot.element.dao.InMemorySourceStore
import dev.slopbot.element.dao.SlopDao
import dev.slopbot.element.dao.SlopPatch
import dev.slopbot.element.model.Participant
import dev.slopbot.element.model.Slop
import dev.slopbot.element.model.Trigger

/**
 * The in-memory Slop DAO — the engine tests' store double. Mirrors the
 * write-through semantics of [StandardSlopDao] without MongoDB.
 */
class InMemorySlopDao : SlopDao {

    val store = InMemorySourceStore()
    private val slops = java.util.concurrent.ConcurrentHashMap<String, Slop>()

    override fun list(): List<Slop> = slops.values.sortedByDescending { it.timestamp }

    override fun findByStreamer(streamerLogin: String): List<Slop> =
        slops.values.filter { it.streamer?.login?.equals(streamerLogin, ignoreCase = true) == true }
            .sortedByDescending { it.timestamp }

    override fun findByName(name: String): Slop? = slops[name]

    override fun names(): Set<String> = slops.keys.toSet()

    override fun put(slop: Slop, source: String, tags: List<String>, streamerLogin: String?): Slop {
        val previous = slops[slop.name]
        val now = System.currentTimeMillis()
        slop.timestamp = previous?.timestamp ?: slop.timestamp.takeIf { it > 0 } ?: now
        slop.tags = linkedSetOf<String>().apply {
            addAll(previous?.tags ?: emptyList())
            addAll(tags)
        }.toMutableList()
        if (slop.creator == null) slop.creator = previous?.creator
        if (slop.creator == null && slop.metadata.author != null) {
            slop.creator = Participant(id = null, login = slop.metadata.author, displayName = slop.metadata.author)
        }
        if (slop.streamer == null) slop.streamer = previous?.streamer
        if (slop.streamer == null) slop.streamer = Participant(id = null, login = streamerLogin, displayName = null)
        slop.playCount = previous?.playCount ?: 0
        slop.lastPlayedAt = previous?.lastPlayedAt
        slop.source = store.put(slop.name, "text/javascript", slop.name, source.toByteArray(Charsets.UTF_8))
        slops[slop.name] = slop
        return slop
    }

    override fun update(name: String, patch: SlopPatch): Slop? {
        val slop = slops[name] ?: return null
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
        return if (touched) slop else null
    }

    override fun bumpPlay(name: String): Boolean {
        val slop = slops[name] ?: return false
        slop.playCount += 1
        slop.lastPlayedAt = System.currentTimeMillis()
        return true
    }

    override fun delete(name: String): Boolean = slops.remove(name) != null

    override fun readSource(name: String): String? {
        val doc = slops[name] ?: return null
        return store.get(doc.source)?.toString(Charsets.UTF_8)
    }
}

/** Test helper — a minimal valid slop source. */
fun validSlopSource(
    title: String = "Test Slop",
    author: String = "testauthor",
    triggers: String = "",
): String = """
    export const meta = {
        title: '$title',
        author: '$author',
        $triggers
    };
    export function init(ctx) { ctx.clear?.(); }
    export function frame(ctx, dt) { return false; }
""".trimIndent()
