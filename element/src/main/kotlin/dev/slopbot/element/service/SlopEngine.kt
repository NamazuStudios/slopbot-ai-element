package dev.slopbot.element.service

import dev.slopbot.api.slop.PlayItem
import dev.slopbot.api.slop.SlopEngineService
import dev.slopbot.api.slop.SlopSnapshot
import dev.slopbot.api.slop.SlopSummary
import dev.slopbot.api.slop.SlopTriggerEvent
import dev.slopbot.element.dao.SlopAssetDao
import dev.slopbot.element.dao.SlopDao
import dev.slopbot.element.dao.SlopHistoryDao
import dev.slopbot.element.dao.SlopModification
import dev.slopbot.element.dao.SlopPatch
import dev.slopbot.element.engine.CooldownRegistry
import dev.slopbot.element.engine.FirstEverTracker
import dev.slopbot.element.engine.FloodCap
import dev.slopbot.element.engine.RegistryEntry
import dev.slopbot.element.engine.SlopBouncer
import dev.slopbot.element.engine.SlopConfig
import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.engine.SlopMetaParser
import dev.slopbot.element.engine.SlopNamer
import dev.slopbot.element.engine.SlopReferenceScanner
import dev.slopbot.element.engine.TriggerEngine
import dev.slopbot.element.model.Participant
import dev.slopbot.element.model.Remark
import dev.slopbot.element.model.Slop
import dev.slopbot.element.model.SlopAsset
import dev.slopbot.element.model.Trigger
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlin.collections.ArrayDeque
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A queued redemption item (the review trail). */
data class QueueItem(
    var id: String = "",
    var at: Long = 0,
    var user: Participant? = null,
    var input: String = "",
    var rewardId: String? = null,
    var rewardTitle: String? = null,
    var rewardCost: Long = 0,
    var redemptionId: String? = null,
    var synthetic: Boolean = false,
    var status: String = "pending",
    var model: String = "",
    var target: Target? = null,
    var reason: String? = null,
    /** The workshop's per-build overrides (moderationLevel/model/agentNotes/maxSeconds). */
    var overrides: Map<String, Any?>? = null,
) {
    data class Target(var name: String = "", var mode: String = "")
}

/** The play on the stage, or queued for it. */
data class CurrentPlay(
    var playId: String = "",
    var name: String = "",
    var title: String = "",
    var author: String = "",
    var startedAt: Long = 0,
    var chained: Boolean = false,
    var via: String = "",
    var redeem: QueueItem? = null,
    var triggerEvent: SlopTriggerEvent? = null,
)

/** The generation slot — one build at a time. */
data class Generation(var itemId: String, var startedAt: Long, var text: String?, var model: String?)

/**
 * The slop engine — the ELM's control-plane state machine, ported from the
 * reference `lib/slop.js`. All durable state writes through the DAO layer
 * (the DB is the authority); the in-memory state here mirrors the reference.
 */
@Singleton
class SlopEngine @Inject constructor(
    private val slopDao: SlopDao,
    private val assetDao: SlopAssetDao,
    private val historyDao: SlopHistoryDao,
    val events: SlopEvents,
    val config: SlopConfig,
) : SlopEngineService {

    private val log = org.slf4j.LoggerFactory.getLogger(SlopEngine::class.java)

    private fun now(): Long = System.currentTimeMillis()
    private val random = java.util.Random()

    private val registered = ConcurrentHashMap<String, RegistryEntry>()
    private val sourceCache = ConcurrentHashMap<String, String>()

    private val queue = LinkedHashMap<String, QueueItem>()
    private val history = ArrayDeque<QueueItem>()
    private val buildQueue = ArrayDeque<String>()
    private var generation: Generation? = null

    private val playQueue = ArrayDeque<CurrentPlay>()
    @Volatile
    var paused = false
        private set
    private var pausedPlay: CurrentPlay? = null
    private var currentPlay: CurrentPlay? = null

    @Volatile
    var overlaySeenAt: Long = now()
        private set

    /** Stream state, as noticed by the worker's ingestion surface. */
    @Volatile
    var streamLive: Boolean = false
        private set

    @Volatile
    var ircLastTrafficAt: Long = 0

    @Volatile
    var ircConnected: Boolean = false

    private val seq = AtomicLong(0)

    val cooldowns = CooldownRegistry(this::now)
    val seenUsers = FirstEverTracker()
    val floodCap = FloodCap(this::now)

    // --- registration ------------------------------------------------------------

    /**
     * Builds the registry entry for a Slop record and caches its source — the
     * reference's `register` (the store write-through happens in the caller).
     */
    fun register(doc: Slop): RegistryEntry {
        val code = slopDao.readSource(doc.name) ?: ""
        val meta = SlopMetaParser.metaOf(code)
        val short = SlopNamer.shortName(doc.name, meta.author)
        val entry = RegistryEntry(
            id = doc.id.toString(),
            name = doc.name,
            title = doc.displayName,
            author = meta.author ?: "anonymous",
            short = short,
            summary = doc.metadata.summary,
            controls = doc.metadata.controls,
            moderationLevel = doc.metadata.moderationLevel,
            suspended = doc.suspended,
            nsfw = doc.nsfw,
            triggers = mergedTriggers(doc, code),
            cooldownSeconds = doc.cooldownSeconds,
            cooldownUntil = cooldowns.of(doc.name).until,
            slopCommand = doc.slopCommand,
            pending = doc.moderation == Slop.MODERATION_PENDING,
            creator = doc.creator,
            streamer = doc.streamer,
            createdAt = doc.timestamp,
            playCount = doc.playCount,
        )
        registered[doc.name] = entry
        sourceCache[doc.name] = code
        if (doc.cooldownSeconds > 0) cooldowns.setDuration(doc.name, doc.cooldownSeconds)
        return entry
    }

    /** Boot-time load — the store is the authority (`loadRegistry`). */
    fun loadRegistry() {
        for (doc in slopDao.list()) {
            // Rejected / suspended moderation records stay out of the registry.
            if (doc.moderation == Slop.MODERATION_REJECTED) continue
            register(doc)
        }
        events.broadcast("registry", mapOf("registered" to registered.values.toList()))
    }

    fun registryEntry(name: String): RegistryEntry? = registered[name]

    fun registryNames(): Set<String> = registered.keys.toSet()

    // --- triggers ----------------------------------------------------------------

    /**
     * The streamer's saved set — the triggers in the record authored by the
     * streamer. A saved (even empty) list is the streamer's word; creator
     * proposals are re-derived from the source's meta at every registration.
     */
    private fun savedTriggers(doc: Slop): List<Trigger> = doc.triggers.filter { it.source == "streamer" }

    /**
     * The live trigger list of a slop: the streamer's saved set plus the
     * creator's meta.triggers proposal (gated by the allow-list, refreshed from
     * the source's meta at every (re)registration — an agent edit replaces its
     * earlier proposals; streamer entries survive untouched). A proposal the
     * streamer has already adopted is not re-appended. When nothing else is
     * specified, a default activation command rides along: "!<short-name>".
     */
    fun mergedTriggers(doc: Slop, code: String): List<Trigger> {
        val meta = SlopMetaParser.metaOf(code)
        val proposals: List<Any?> = if (meta.triggers is List<*>) meta.triggers else emptyList()
        val streamerList = savedTriggers(doc)
        val streamerHasSaved = doc.triggers.any { it.source == "streamer" }
        var effective = proposals
        if (proposals.isEmpty() && streamerList.isEmpty() && !streamerHasSaved) {
            val slug = SlopNamer.shortName(doc.name, meta.author)
                .lowercase()
                .replace(Regex("[^a-z0-9-]+"), "-")
                .replace(Regex("^-+|-+$"), "")
            if (slug.isNotEmpty()) {
                effective = listOf(mapOf("type" to "chat", "mode" to "word", "patterns" to listOf("!$slug")))
            }
        }

        fun keyOf(t: Trigger): String = listOf(
            t.type, t.mode, t.patterns, t.caseSensitive, t.who,
            t.minViewers, t.minBits, t.tier, t.minMonths, t.rewardId,
        ).toString()

        val adopted = streamerList.map { keyOf(it) }.toSet()
        val creator = mutableListOf<Trigger>()
        for (i in effective.indices) {
            val trigger = TriggerEngine.normalizeTrigger(effective[i], i, "creator") ?: continue // malformed — dropped, gate stays shut
            if (adopted.contains(keyOf(trigger))) continue // the streamer already took it
            if (!TriggerEngine.triggerAllowed(trigger, config.allowedTriggers())) {
                trigger.enabled = false
                trigger.held = true // "waiting on the registry allow-list" badge
            }
            creator.add(trigger)
        }
        return (streamerList + creator).take(TriggerEngine.MAX_TRIGGERS_PER_SLOP)
    }

    private fun triggersFor(entry: RegistryEntry): List<Trigger> = entry.triggers

    private fun eligibleForTrigger(entry: RegistryEntry): Boolean = !entry.suspended && !entry.pending

    /**
     * An event arrived: find every slop whose triggers match (allow-list
     * respected), pick one at random, then play it — queued like `!slop`, or
     * immediate when the matched trigger says so. First-ever tracking (chat
     * and follow) marks the event `first` on the user's first-ever event.
     */
    override fun dispatchTrigger(event: SlopTriggerEvent): Boolean {
        if (event.synthetic.not() && (event.type == "follow" || event.type == "chat")) {
            if (!event.first && seenUsers.markSeen(event.type, event.userId)) event.first = true
        }
        val candidates = mutableListOf<Pair<RegistryEntry, Trigger>>()
        for (entry in registered.values) {
            if (!eligibleForTrigger(entry)) continue
            // A cooled-down slop is invisible to triggers — unless the matched
            // trigger explicitly bypasses the cooldown (#5).
            val cooled = cooldowns.onCooldown(entry.name)
            val matched = triggersFor(entry).find { trigger ->
                if (!TriggerEngine.triggerAllowed(trigger, config.allowedTriggers())) return@find false
                if (!TriggerEngine.triggerMatches(trigger, event)) return@find false
                if (cooled && !trigger.bypassCooldown) return@find false
                true
            }
            if (matched != null) candidates.add(entry to matched)
        }
        if (candidates.isEmpty()) return false
        if (!floodCap.take(config.triggerDispatchesPerMinute(), this::noteBroadcast)) return false
        val pick = candidates[random.nextInt(candidates.size)]
        val via = "trigger: ${event.type}"
        val played = if (pick.second.dispatch == "immediate") {
            playImmediate(pick.first.name, via, event)
        } else {
            play(pick.first.name, via, chained = false, redeem = null, triggerEvent = event)
        }
        if (played) {
            // The play arms the cooldown window (unless this trigger bypasses it).
            if (!pick.second.bypassCooldown) cooldowns.arm(pick.first.name)
            broadcastCooldowns()
        }
        return played
    }

    private fun noteBroadcast(text: String) {
        events.broadcast("note", mapOf("text" to text))
    }

    /**
     * Immediate dispatch (issue #4): the triggered slop takes the stage NOW —
     * the current one stops and goes back to the FRONT of the queue.
     */
    fun playImmediate(name: String, via: String, triggerEvent: SlopTriggerEvent?): Boolean {
        val entry = registered[name] ?: return false
        if (entry.suspended) {
            events.broadcast("note", mapOf("text" to "${entry.title} is suspended by the streamer"))
            return false
        }
        val busy = currentPlay
        if (busy != null) {
            currentPlay = null
            if (playQueue.size >= config.maxQueueDepth()) {
                events.broadcast(
                    "note",
                    mapOf("text" to "${busy.title} was interrupted for ${entry.title} — the queue is full, it won't resume"),
                )
            } else {
                // Redeem already aired at its original start — the resume is silent.
                // The interrupt keeps its own trigger event (it replays with its data).
                playQueue.addFirst(
                    CurrentPlay(
                        playId = busy.playId,
                        name = busy.name,
                        title = busy.title,
                        author = busy.author,
                        startedAt = busy.startedAt,
                        chained = busy.chained,
                        via = "resume",
                        redeem = null,
                        triggerEvent = busy.triggerEvent,
                    ),
                )
            }
            events.broadcast("stageclear", emptyMap())
        }
        if (paused) {
            // Frozen pipeline: the immediate lines up in front instead of forcing
            // through the pause — the streamer's freeze outranks any trigger.
            if (playQueue.size >= config.maxQueueDepth()) {
                events.broadcast("note", mapOf("text" to "the play queue is full"))
                return false
            }
            playQueue.addLast(
                CurrentPlay(
                    name = entry.name,
                    title = entry.title,
                    author = entry.author,
                    via = via,
                    triggerEvent = triggerEvent,
                ),
            )
            events.broadcast(
                "plays",
                mapOf("current" to null, "upcoming" to playQueue.toList(), "paused" to true, "pausedPlay" to pausedPlay),
            )
            events.broadcast("note", mapOf("text" to "${entry.title} queued — playback is paused"))
            return true
        }
        startPlay(entry, via, chained = false, redeem = null, triggerEvent = triggerEvent)
        return true
    }

    /**
     * The deck settings screen's matcher test — same server-side matcher, so
     * what the streamer sees is exactly what an event would hit.
     */
    override fun testTriggers(name: String, text: String): List<String> {
        val entry = registered[name] ?: return emptyList()
        val event = SlopTriggerEvent(type = "chat", text = text, login = "deck-test", mod = true, broadcaster = true)
        return triggersFor(entry).filter { TriggerEngine.triggerMatches(it, event) }.map { it.id }
    }

    override fun play(name: String, via: String, chained: Boolean): Boolean =
        play(name, via, chained, redeem = null, triggerEvent = null)

    /**
     * The full play path (deck buttons, `!slop`, cameos, trigger dispatches).
     * Cameos — `chained = true` — obey the cooldown window (a cameo can never
     * bypass it) and refuse a repeat of the slop already on stage; the chain
     * depth is 1: a cameo cannot chain further.
     */
    fun play(
        name: String,
        via: String,
        chained: Boolean,
        redeem: QueueItem?,
        triggerEvent: SlopTriggerEvent?,
    ): Boolean {
        val entry = registered[name]
        if (entry == null) {
            events.broadcast("note", mapOf("text" to "no slop named $name"))
            return false
        }
        slopDao.bumpPlay(name) // play statistics ride in the record (ticket 03)
        if (entry.suspended) {
            events.broadcast("note", mapOf("text" to "${entry.title} is suspended by the streamer"))
            return false
        }
        if (chained) {
            // Cameo rules: cooldowns apply, and the slop already on stage does
            // not cameo itself (no immediate repeats).
            if (cooldowns.onCooldown(name)) {
                events.broadcast("note", mapOf("text" to "${entry.title} is cooling down"))
                return false
            }
            if (currentPlay?.name == name) {
                events.broadcast("note", mapOf("text" to "${entry.title} is already playing"))
                return false
            }
        }
        // A stalled stage (overlay closed → no fuse report) auto-advances, so the
        // queue can't deadlock behind a play that ended nobody-knows-when.
        val busy = currentPlay
        if (busy != null && now() - busy.startedAt > (config.maxSlopSeconds() + 15) * 1000L) {
            playEnded(busy.name, busy.playId)
        }
        if (currentPlay != null || paused) {
            // The stage is busy — the request lines up instead of replacing. Any
            // slop queues at any time; the timeout only decides when it starts.
            if (playQueue.size >= config.maxQueueDepth()) {
                events.broadcast("note", mapOf("text" to "the play queue is full"))
                return false
            }
            if (playQueue.any { it.name == name }) {
                events.broadcast("note", mapOf("text" to "${entry.title} is already queued to play"))
                return false
            }
            playQueue.addLast(
                CurrentPlay(
                    name = entry.name,
                    title = entry.title,
                    author = entry.author,
                    via = via,
                    chained = chained,
                    redeem = redeem,
                    triggerEvent = triggerEvent,
                ),
            )
            if (paused) {
                events.broadcast(
                    "plays",
                    mapOf("current" to null, "upcoming" to playQueue.toList(), "paused" to true, "pausedPlay" to pausedPlay),
                )
                events.broadcast("note", mapOf("text" to "${entry.title} queued — playback is paused"))
            } else {
                events.broadcast("plays", mapOf("current" to currentPlay, "upcoming" to playQueue.toList()))
                events.broadcast("note", mapOf("text" to "${entry.title} queued to play"))
            }
            return true
        }
        startPlay(entry, via, chained, redeem, triggerEvent)
        return true
    }

    /**
     * Pause the play pipeline: the current slop leaves the stage immediately
     * (remembered), queued plays freeze. Resume restarts the interrupted slop
     * from the beginning, then the queue continues.
     */
    fun setPaused(value: Boolean): Boolean {
        paused = value
        if (paused) {
            val busy = currentPlay
            if (busy != null) {
                pausedPlay = busy
                currentPlay = null
                events.broadcast("stageclear", emptyMap()) // the stage drops right now
            }
            events.broadcast(
                "plays",
                mapOf("current" to null, "upcoming" to playQueue.toList(), "paused" to true, "pausedPlay" to pausedPlay),
            )
            events.broadcast("note", mapOf("text" to "slop playback paused"))
        } else {
            val resume = pausedPlay
            pausedPlay = null
            events.broadcast(
                "plays",
                mapOf("current" to null, "upcoming" to playQueue.toList(), "paused" to false, "pausedPlay" to null),
            )
            events.broadcast("note", mapOf("text" to "slop playback resumed"))
            val resumeEntry = resume?.let { registered[it.name] }
            if (resumeEntry != null) startPlay(resumeEntry, "resume", chained = false, redeem = null, triggerEvent = null)
        }
        return true
    }

    /** ✕ in the stage monitor: un-queue a lined-up slop, or stop the playing/paused one. */
    fun playRemove(name: String): Boolean {
        val index = playQueue.indexOfFirst { it.name == name }
        if (index != -1) {
            val removed = playQueue.removeAt(index)
            events.broadcast(
                "plays",
                mapOf("current" to currentPlay, "upcoming" to playQueue.toList(), "paused" to paused, "pausedPlay" to pausedPlay),
            )
            events.broadcast("note", mapOf("text" to "${removed.title} removed from the play queue"))
            return true
        }
        val busy = currentPlay
        if (busy != null && busy.name == name) {
            currentPlay = null
            events.broadcast("stageclear", emptyMap())
            events.broadcast(
                "plays",
                mapOf("current" to null, "upcoming" to playQueue.toList(), "paused" to paused, "pausedPlay" to null),
            )
            events.broadcast("note", mapOf("text" to "${busy.title} stopped"))
            if (!paused) tryDequeue() // the next queued play takes the stage
            return true
        }
        val pausedOne = pausedPlay
        if (pausedOne != null && pausedOne.name == name) {
            pausedPlay = null
            events.broadcast("plays", mapOf("current" to null, "upcoming" to playQueue.toList(), "paused" to true, "pausedPlay" to null))
            events.broadcast("note", mapOf("text" to "the paused slop will not resume"))
            return true
        }
        return false
    }

    /** A slop's play ended — the next queued play takes the stage. */
    fun playEnded(name: String, playId: String?): Boolean {
        val busy = currentPlay ?: return false
        if (busy.name != name) return false
        // A replay of the same slop collides by name — the report must carry the
        // play instance id of the stage it describes.
        if (!playId.isNullOrEmpty() && busy.playId != playId) return false
        currentPlay = null
        events.broadcast("plays", mapOf("current" to null, "upcoming" to playQueue.toList(), "paused" to paused, "pausedPlay" to pausedPlay))
        if (paused) return true
        tryDequeue()
        return true
    }

    /** Advance the stage: the first queued play starts; the rest keep waiting. */
    fun tryDequeue(): Boolean {
        if (paused || currentPlay != null) return false
        val rotations = playQueue.size
        for (i in 0 until rotations) {
            val next = playQueue.removeFirst()
            val entry = registered[next.name] ?: continue
            if (entry.suspended) continue
            startPlay(entry, next.via, next.chained, next.redeem, next.triggerEvent)
            return true
        }
        events.broadcast("plays", mapOf("current" to null, "upcoming" to playQueue.toList()))
        return false
    }

    fun startPlay(entry: RegistryEntry, via: String, chained: Boolean, redeem: QueueItem?, triggerEvent: SlopTriggerEvent?) {
        currentPlay = CurrentPlay(
            playId = newPlayId(),
            name = entry.name,
            title = entry.title,
            author = entry.author,
            startedAt = now(),
            chained = chained,
            via = via,
            redeem = redeem,
            triggerEvent = triggerEvent,
        )
        if (redeem != null) {
            // A built slop taking the stage is the moment it "airs".
            events.broadcast("complete", mapOf("name" to entry.name, "title" to entry.title, "author" to entry.author))
        }
        events.broadcast(
            "play",
            mapOf(
                "name" to entry.name,
                "title" to entry.title,
                "author" to entry.author,
                "via" to via,
                "chained" to chained,
                "current" to currentPlay,
                "upcoming" to playQueue.toList(),
            ),
        )
    }

    private fun newPlayId(): String = "play-${now().toString(36)}-${seq.getAndIncrement()}"

    // --- cooldowns ----------------------------------------------------------------

    /** The broadcast of the live cooldown state so the deck's countdown chips stay true. */
    fun broadcastCooldowns() {
        for ((name, entry) in registered) {
            entry.cooldownUntil = cooldowns.of(name).until
            entry.cooldownSeconds = cooldowns.of(name).duration
        }
        events.broadcast("cooldowns", mapOf("cooldownUntil" to cooldowns.liveUntil()))
    }

    /** The streamer's reset: any slop, cleared immediately. */
    fun cooldownClear(name: String): Boolean {
        val entry = registered[name] ?: return false
        cooldowns.clear(name)
        entry.cooldownUntil = 0
        broadcastCooldowns()
        events.broadcast("note", mapOf("text" to "${entry.title}'s cooldown cleared"))
        return true
    }

    // --- suspend / moderation verdicts / delete --------------------------------------

    /** Deck quick-suspend: a suspended slop can't play until restored. */
    /** Sets the NSFW flag on the stored doc and refreshes the registry entry. */
    fun setNsfw(name: String, nsfw: Boolean): Boolean {
        val entry = registered[name] ?: return false
        if (!slopDao.update(name, SlopPatch(nsfw = nsfw)).let { it != null }) return false
        entry.nsfw = nsfw
        events.broadcast("registry", mapOf("registered" to registered.values.toList()))
        return true
    }

    fun suspend(name: String, value: Boolean): Boolean {
        val entry = registered[name] ?: return false
        entry.suspended = value
        slopDao.update(name, SlopPatch(suspended = value))
        events.broadcast("registry", mapOf("registered" to registered.values.toList()))
        events.broadcast("note", mapOf("text" to "${entry.title} ${if (value) "suspended" else "restored"}"))
        return true
    }

    /** The operator's verdict: approve airs it (confetti + play). */
    fun approvePending(name: String): Boolean {
        val entry = registered[name] ?: return false
        if (!entry.pending) return false
        entry.pending = false
        slopDao.update(name, SlopPatch(pending = false))
        events.broadcast("registry", mapOf("registered" to registered.values.toList()))
        events.broadcast("complete", mapOf("name" to name, "title" to entry.title, "author" to entry.author))
        play(name, "slop")
        return true
    }

    /** Rejection deletes the slop entirely. */
    fun rejectPending(name: String): Boolean {
        val entry = registered[name] ?: return false
        if (!entry.pending) return false
        deleteSlop(name)
        events.broadcast("note", mapOf("text" to "${entry.title} rejected"))
        return true
    }

    /** Delete a registered slop entirely — record, source, own assets, settings. */
    fun deleteSlop(name: String): Boolean {
        val entry = registered[name] ?: return false
        registered.remove(name)
        sourceCache.remove(name)
        cooldowns.remove(name)
        slopDao.delete(name)
        events.broadcast("registry", mapOf("registered" to registered.values.toList()))
        events.broadcast("note", mapOf("text" to "${entry.title} deleted"))
        return true
    }

    // --- MCP create / update ------------------------------------------------------------

    /**
     * `slops.create` — runs the bouncer then `metaOf`, assigns the name
     * server-side (author-slug of the title, capped 39 chars, `-2`/`-3`…
     * collision counters), records the creator, lands `moderation: PENDING`.
     * Returns the assigned name.
     */
    fun createSlop(
        code: String,
        creator: Participant?,
        streamerLogin: String,
        agentRemarks: List<Remark> = emptyList(),
        contentRemarks: List<Remark> = emptyList(),
        nowMs: Long = now(),
    ): String {
        SlopBouncer.inspect(code)
        val meta = SlopMetaParser.metaOf(code)
        val knownNames = slopDao.names()
        val name = SlopNamer.assignName(
            metaAuthor = meta.author,
            fallbackAuthor = creator?.login,
            title = meta.title,
            nameTaken = { n -> knownNames.contains(n) },
            nowMs = nowMs,
        )
        // The tier in effect at build time is stamped on the result even if the
        // agent omitted it.
        val doc = Slop()
        doc.name = name
        doc.displayName = meta.title ?: name
        doc.moderation = Slop.MODERATION_PENDING
        doc.creator = creator
        doc.metadata.short = SlopNamer.shortName(name, meta.author)
        doc.metadata.author = meta.author
        doc.metadata.controls = meta.controls
        doc.metadata.moderationLevel = meta.moderationLevel ?: config.currentTier()
        doc.agentRemarks = agentRemarks.toMutableList()
        doc.contentRemarks = contentRemarks.toMutableList()
        val saved = slopDao.put(doc, code, tags = emptyList(), streamerLogin = streamerLogin)
        register(saved)
        events.broadcast(
            "built",
            mapOf("name" to name, "title" to doc.displayName, "author" to meta.author, "pending" to true),
        )
        events.broadcast("registry", mapOf("registered" to registered.values.toList()))
        // A successful creation IS the build's completion (Phase C): the
        // generation slot frees and the queue advances.
        val gen = generation
        if (gen != null) {
            generation = null
            queue[gen.itemId]?.let { it.status = "complete" }
            events.broadcast("queue", mapOf("queue" to queue.values.toList()))
            startNextBuild()
        }
        return name
    }

    /**
     * `slops.update` — same bouncer + meta pipeline on the same name; a running
     * history of 50 modifications (previous source revisions kept in
     * large-object history). Updates land back in `PENDING` (the reference's
     * modify flow re-enters approval).
     */
    fun updateSlop(
        name: String,
        code: String,
        actor: String? = null,
        agentRemarks: List<Remark> = emptyList(),
        contentRemarks: List<Remark> = emptyList(),
    ): String? {
        val doc = slopDao.findByName(name) ?: return null
        SlopBouncer.inspect(code)
        val meta = SlopMetaParser.metaOf(code)

        // The running history: the previous source revision is kept in
        // large-object history (a new large object per revision).
        val existing = historyDao.list(name, 1)
        val nextIndex = (existing.firstOrNull()?.index ?: 0L) + 1L
        val modification = SlopModification()
        modification.name = name
        modification.index = nextIndex
        modification.timestamp = now()
        modification.actor = actor
        modification.previousSource = doc.source
        modification.changes = "source + metadata"
        historyDao.record(modification)

        doc.displayName = meta.title ?: doc.displayName
        doc.metadata.short = SlopNamer.shortName(name, meta.author)
        doc.metadata.author = meta.author
        doc.metadata.controls = meta.controls
        doc.metadata.moderationLevel = meta.moderationLevel ?: config.currentTier()
        if (agentRemarks.isNotEmpty()) doc.agentRemarks = agentRemarks.toMutableList()
        if (contentRemarks.isNotEmpty()) doc.contentRemarks = contentRemarks.toMutableList()
        doc.moderation = Slop.MODERATION_PENDING
        val saved = slopDao.put(doc, code, tags = emptyList(), streamerLogin = doc.streamer?.login)
        register(saved)

        // The cap: 50 modifications — the newest 50 survive.
        historyDao.trim(name, 50)

        events.broadcast(
            "built",
            mapOf("name" to name, "title" to doc.displayName, "author" to meta.author, "pending" to true),
        )
        events.broadcast("registry", mapOf("registered" to registered.values.toList()))
        return name
    }

    // --- bundle ---------------------------------------------------------------------

    /**
     * The playable bundle: the gated code (base64) plus the slop's own assets
     * and the shared sound library inlined, the streamer cam, the hard cap,
     * the on-screen controls, and the triggering event. Cameos (chained plays)
     * cannot chain further.
     */
    fun bundle(name: String, chained: Boolean, playId: String?): Map<String, Any?> {
        val entry = registered[name] ?: throw IllegalArgumentException("no registered slop named $name")
        val code = sourceCache[name] ?: throw IllegalArgumentException("no source in store for $name")
        SlopBouncer.inspect(code)

        // The triggering event (if any) rides along for a specific playId —
        // strict match: a preview or unknown play gets no trigger context.
        val play = if (currentPlay != null && !playId.isNullOrEmpty() && currentPlay?.playId == playId) currentPlay else null
        val trigger = triggerEventForSlop(play?.triggerEvent)

        var total = 0
        val assets = mutableListOf<Map<String, Any?>>()
        // The slop's own assets (tagged ["slop", "<file name>"]). A retired or
        // missing asset is logged and skipped — never a bundle failure.
        for (asset in assetDao.list(name)) {
            if (asset.state == SlopAsset.STATE_RETIRED) {
                log.info("asset {}: retired — skipping in bundle {}", asset.name, name)
                continue
            }
            val bytes = assetDao.readBytes(asset)
            if (bytes == null) {
                log.info("asset {}: no bytes in store — skipping in bundle {}", asset.name, name)
                continue
            }
            total += bytes.size
            if (total > MAX_ASSET_BYTES) {
                throw IllegalArgumentException("asset bundle exceeds ${MAX_ASSET_BYTES / (1024 * 1024)} MB")
            }
            assets.add(
                mapOf(
                    "name" to asset.displayName,
                    "file" to (asset.metadata.originalFilename ?: asset.name),
                    "kind" to asset.kind.lowercase(),
                    "mime" to asset.mimeType,
                    "base64" to Base64.getEncoder().encodeToString(bytes),
                ),
            )
        }
        // Shared library: whatever the slop samples by name is inlined here, so
        // slops get a full effect library without bundling files — sounds via
        // `sound.sample('name')`, videos via `ctx.media.video('name')`. The
        // same scan powers the admin surface's referenced-by counts.
        val refs = SlopReferenceScanner.allRefs(code)
        val shared = assetDao.list("shared").groupBy { it.displayName.lowercase() }
        for (ref in refs) {
            if (assets.any { it["name"] == ref }) continue
            val asset = shared[ref]?.firstOrNull()
            if (asset == null || asset.state == SlopAsset.STATE_RETIRED) continue
            val bytes = assetDao.readBytes(asset)
            if (bytes == null) {
                log.info("asset {}: no bytes in store — skipping in bundle {}", asset.name, name)
                continue
            }
            total += bytes.size
            if (total > MAX_ASSET_BYTES) {
                throw IllegalArgumentException("asset bundle exceeds ${MAX_ASSET_BYTES / (1024 * 1024)} MB")
            }
            assets.add(
                mapOf(
                    "name" to ref,
                    "file" to (asset.metadata.originalFilename ?: asset.name),
                    "kind" to asset.kind.lowercase(),
                    "mime" to asset.mimeType,
                    "base64" to Base64.getEncoder().encodeToString(bytes),
                ),
            )
        }
        return mapOf(
            "name" to name,
            "chained" to chained,
            "playId" to playId,
            "codeBase64" to Base64.getEncoder().encodeToString(code.toByteArray(Charsets.UTF_8)),
            "assets" to assets,
            "maxSeconds" to config.maxSlopSeconds(),
            "controls" to entry.controls,
            "trigger" to trigger,
            "streamerCam" to config.streamerCam(),
            "ctx" to mapOf("streamer" to entry.streamer),
        )
    }

    // --- snapshot ------------------------------------------------------------------

    /**
     * The status snapshot — registry, queue, cooldowns, config echo, and
     * connection liveness (`irc.connected`, `lastTrafficAgoMs`).
     */
    fun snapshotMap(): Map<String, Any?> = mapOf(
        "queue" to queue.values.toList(),
        "generation" to generation?.let {
            mapOf("id" to it.itemId, "startedAt" to it.startedAt, "text" to it.text, "model" to it.model)
        },
        "plays" to mapOf(
            "current" to currentPlay,
            "upcoming" to playQueue.toList(),
            "paused" to paused,
            "pausedPlay" to pausedPlay,
        ),
        "history" to history.take(10),
        "registered" to registered.values.toList(),
        "cooldownUntil" to cooldowns.liveUntil(),
        "irc" to mapOf(
            "connected" to ircConnected,
            "lastTrafficAgoMs" to if (ircLastTrafficAt > 0) now() - ircLastTrafficAt else null,
        ),
        "stream" to mapOf("live" to streamLive),
        "config" to config.snapshot(),
        "overlaySeenAt" to overlaySeenAt,
    )

    override fun snapshot(): SlopSnapshot {
        val plays = mapOf(
            "current" to currentPlay?.let { toPlayItem(it) },
            "upcoming" to playQueue.map { toPlayItem(it) },
        )
        @Suppress("UNCHECKED_CAST")
        return SlopSnapshot(
            registered = registered.values.map { toSummary(it) },
            current = plays["current"] as PlayItem?,
            upcoming = plays["upcoming"] as List<PlayItem>,
            paused = paused,
            cooldownUntil = cooldowns.liveUntil(),
            ircConnected = ircConnected,
            lastTrafficAgoMs = if (ircLastTrafficAt > 0) now() - ircLastTrafficAt else null,
            config = config.snapshot(),
        )
    }

    private fun toSummary(entry: RegistryEntry): SlopSummary = SlopSummary(
        name = entry.name,
        title = entry.title,
        author = entry.author,
        short = entry.short,
        suspended = entry.suspended,
        pending = entry.pending,
        moderationLevel = entry.moderationLevel,
        cooldownSeconds = entry.cooldownSeconds,
        cooldownUntil = entry.cooldownUntil,
        slopCommand = entry.slopCommand,
        playCount = entry.playCount,
        createdAt = entry.createdAt,
        summary = entry.summary,
    )

    private fun toPlayItem(play: CurrentPlay): PlayItem = PlayItem(
        playId = play.playId,
        name = play.name,
        title = play.title,
        author = play.author,
        via = play.via,
        startedAt = play.startedAt,
        chained = play.chained,
    )

    // --- the redemption queue ------------------------------------------------------

    fun pendingCount(): Int = queue.values.count { it.status == "pending" }

    /**
     * The redemption intake — a reward redemption for the slop machine.
     * "@name …" targets an existing slop: modify it, or delete it — but only
     * its creator may touch it (ownership checked at intake).
     */
    fun handleRedemption(
        user: Participant?,
        inputRaw: String?,
        rewardId: String?,
        rewardTitle: String?,
        rewardCost: Long,
        redemptionId: String?,
        synthetic: Boolean = false,
    ): QueueItem {
        val item = QueueItem(
            id = "slop-${now().toString(36)}-${seq.getAndIncrement()}",
            at = now(),
            user = user,
            input = (inputRaw ?: "").trim(),
            rewardId = rewardId,
            rewardTitle = rewardTitle,
            rewardCost = rewardCost,
            redemptionId = redemptionId,
            synthetic = synthetic,
            status = "pending",
            model = (config.snapshot()["model"]?.toString() ?: "").ifEmpty { "agent default" },
        )

        fun refuse(reason: String): QueueItem {
            item.status = "refused"
            item.reason = reason
            history.addFirst(item)
            if (history.size > 30) history.removeLast()
            events.broadcast("note", mapOf("text" to "${item.user?.login ?: "someone"}'s slop refused: $reason"))
            return item
        }

        if (item.input.isEmpty()) return refuse("the redeem came without text — nothing to slop")
        if (pendingCount() >= config.maxQueueDepth()) return refuse("the slop queue is full")

        val targetMatch = Regex("""^@([a-z0-9]+(?:-[a-z0-9]+)*)(?:\.js)?\s*[,;—-]*\s*(.*)$""", RegexOption.IGNORE_CASE)
            .find(item.input)
        if (targetMatch != null) {
            val rest = targetMatch.groupValues[2].trim()
            val wantsDelete = Regex("""^(delete|remove|kill|get rid of)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rest)
            val candidates = matchSlopNames(targetMatch.groupValues[1])
            val entry = if (candidates.size == 1) registered[candidates[0]] else null
            if (entry == null) {
                return refuse(
                    if (candidates.size > 1) {
                        "\"${targetMatch.groupValues[1]}\" matches several slops — be more specific"
                    } else {
                        "no slop named \"${targetMatch.groupValues[1]}\" exists"
                    },
                )
            }
            if (entry.author.lowercase() != (item.user?.login ?: "").lowercase()) {
                return refuse("\"${entry.title}\" belongs to ${entry.author} — only its creator can modify or delete it")
            }
            if (!wantsDelete && rest.isEmpty()) {
                return refuse("tell me what to change about \"${entry.title}\" after @${targetMatch.groupValues[1]}")
            }
            item.target = QueueItem.Target(name = entry.name, mode = if (wantsDelete) "delete" else "modify")
            item.input = rest.ifEmpty { "delete this slop" }
        }

        queue[item.id] = item
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        events.broadcast("note", mapOf("text" to "${item.user?.login} queued a slop for review"))
        return item
    }

    fun findItem(id: String): QueueItem? = queue[id]

    fun present(id: String): Boolean {
        val item = queue[id] ?: return false
        if (item.status != "pending") return false
        item.status = "presented"
        events.broadcast("presented", mapOf("item" to item))
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        return true
    }

    fun dismiss(id: String): Boolean {
        val item = queue[id] ?: return false
        if (item.status != "presented") return false
        item.status = "pending"
        events.broadcast("dismissed", mapOf("id" to id))
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        return true
    }

    fun reject(id: String): Boolean {
        val item = queue[id] ?: return false
        if (item.status != "presented" && item.status != "pending") return false
        item.status = "rejected"
        queue.remove(id)
        history.addFirst(item)
        if (history.size > 30) history.removeLast()
        events.broadcast("dismissed", mapOf("id" to id)) // denied — back to the stream immediately
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        events.broadcast("note", mapOf("text" to "rejected — refunding ${item.rewardCost} points to ${item.user?.login}"))
        return true
    }

    /** Pull an item off the queue without the "rejected" theater. */
    fun remove(id: String): Boolean {
        val item = queue[id] ?: return false
        if (item.status != "presented" && item.status != "pending") return false
        item.status = "removed"
        queue.remove(id)
        history.addFirst(item)
        if (history.size > 30) history.removeLast()
        events.broadcast("dismissed", mapOf("id" to id))
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        events.broadcast("note", mapOf("text" to "removed from queue — ${item.rewardCost} points refunded to ${item.user?.login}"))
        return true
    }

    /** The operator approves a queued redeem — it becomes a build job. */
    fun approve(id: String): Boolean {
        val item = queue[id] ?: return false
        if (item.status != "presented" && item.status != "pending") return false
        events.broadcast("dismissed", mapOf("id" to id)) // review is over the moment it's approved
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))

        // Deleting skips the agent entirely — the review was the point.
        if (item.target?.mode == "delete") {
            val name = item.target!!.name
            registered.remove(name)
            sourceCache.remove(name)
            cooldowns.remove(name)
            slopDao.delete(name)
            item.status = "complete"
            queue.remove(id)
            history.addFirst(item)
            if (history.size > 30) history.removeLast()
            events.broadcast("queue", mapOf("queue" to queue.values.toList()))
            events.broadcast("note", mapOf("text" to "$name deleted at its creator's request"))
            events.broadcast("registry", mapOf("registered" to registered.values.toList()))
            return true
        }

        item.status = "buildqueued"
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        buildQueue.addLast(id)
        startNextBuild()
        return true
    }

    /** Manual controls for jobs in the build queue: restart a failed/stuck build. */
    fun restartJob(id: String): Boolean {
        val item = queue[id] ?: return false
        if (item.status != "failed" && item.status != "generating") return false
        if (item.status == "generating" && generation?.itemId == id) generation = null
        item.status = "buildqueued"
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        buildQueue.addLast(id)
        startNextBuild()
        return true
    }

    /** Cancel a build job entirely. */
    fun cancelJob(id: String): Boolean {
        val item = queue[id] ?: return false
        if (item.status != "buildqueued" && item.status != "generating" && item.status != "failed") return false
        if (item.status == "generating" && generation?.itemId == id) generation = null
        buildQueue.remove(id)
        item.status = "canceled"
        queue.remove(id)
        history.addFirst(item)
        if (history.size > 30) history.removeLast()
        events.broadcast(
            "queue",
            mapOf("queue" to queue.values.toList()),
        )
        events.broadcast(
            "note",
            mapOf("text" to "${item.user?.login ?: "someone"}'s slop canceled"),
        )
        startNextBuild()
        return true
    }

    /** The deck's direct line: build a slop from the deck, no redeem/review. */
    fun buildDirect(text: String?): Boolean = generate(text = text, user = null, overrides = null)

    /**
     * The formal build entry (POST /slop/api/slop/generate): the prompt is
     * attributed to the session user and carries their per-build overrides
     * (the workshop's toggle cards). `buildDirect` is the un-attributed form.
     */
    fun generate(text: String?, user: Participant?, overrides: Map<String, Any?>?): Boolean {
        val input = (text ?: "").trim()
        if (input.isEmpty()) {
            events.broadcast("note", mapOf("text" to "give the slop a prompt first"))
            return false
        }
        val author = user?.login
            ?: config.snapshot()["channel"]?.toString()?.ifEmpty { "streamer" }
            ?: "streamer"
        val item = QueueItem(
            id = "slop-" + now().toString(36) + "-" + seq.getAndIncrement(),
            at = now(),
            user = user ?: Participant(id = "0", login = author, displayName = author),
            input = input,
            rewardTitle = "Deck build",
            rewardCost = 0,
            synthetic = true,
            status = "buildqueued",
            model = (overrides?.get("model") ?: config.snapshot()["model"])?.toString()?.ifEmpty { "agent default" }
                ?: "agent default",
            overrides = overrides,
        )
        queue[item.id] = item
        buildQueue.addLast(item.id)
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        startNextBuild()
        return true
    }

    private fun startNextBuild() {
        // One build at a time — the `generation` slot. When the slot is free and
        // jobs are waiting, the oldest queued job becomes the generation; the
        // agent (the MCP client) consumes it.
        if (generation != null) return
        while (buildQueue.isNotEmpty()) {
            val id = buildQueue.removeFirst()
            val item = queue[id] ?: continue
            if (item.status != "buildqueued") continue
            generation = Generation(itemId = id, startedAt = now(), text = item.input, model = item.model)
            item.status = "generating"
            events.broadcast("queue", mapOf("queue" to queue.values.toList()))
            return
        }
    }

    fun takeGeneration(): Generation? = generation

    /**
     * The agent's build brief (Phase C): everything the in-pod agent needs
     * to generate — the prompt, the model, the moderation tier IN EFFECT
     * (its rules verbatim), the streamer's additional moderation
     * instructions, the agent instructions, and the per-build overrides.
     * Taking the brief leaves the item "generating" (idempotent — the same
     * brief until the build completes or fails).
     */
    fun generationBrief(): Map<String, Any?>? {
        val gen = generation ?: return null
        val item = queue[gen.itemId]
        val snap = config.snapshot()
        val level = (item?.overrides?.get("moderationLevel") as? String) ?: snap["moderationLevel"]?.toString() ?: "teen"
        val brief = linkedMapOf<String, Any?>(
            "itemId" to gen.itemId,
            "prompt" to (item?.input ?: gen.text),
            "model" to (item?.overrides?.get("model") ?: gen.model ?: ""),
            "user" to mapOf(
                "login" to (item?.user?.login ?: ""),
                "displayName" to (item?.user?.displayName ?: ""),
            ),
            "moderation" to mapOf(
                "level" to level,
                "rules" to (config.moderationLevels[level] ?: ""),
                "additionalInstructions" to (item?.overrides?.get("agentNotes")
                    ?: snap["customRules"] ?: "").toString(),
            ),
            "agentInstructions" to (snap["agentInstructions"] ?: "").toString(),
            "allowedTriggers" to snap["allowedTriggers"],
            "maxSeconds" to (item?.overrides?.get("maxSeconds") ?: snap["maxSlopSeconds"]),
        )
        return brief
    }

    /** The agent reports a build failure: the slot frees, the item fails visibly. */
    fun failGeneration(reason: String?): Boolean {
        val gen = generation ?: return false
        val item = queue[gen.itemId]
        generation = null
        if (item != null) {
            item.status = "failed"
            item.reason = reason ?: "the agent reported a failure"
            history.addFirst(item)
            if (history.size > 50) history.removeLast()
        }
        events.broadcast("note", mapOf("text" to ("build failed: " + (reason ?: "agent failure"))))
        events.broadcast("queue", mapOf("queue" to queue.values.toList()))
        startNextBuild()
        return true
    }

    // --- triggers save ---------------------------------------------------------------

    /**
     * The deck's trigger save (GET|POST /slop/api/slop/triggers/{name}): the
     * streamer's saved state is authoritative — everything saved here becomes
     * streamer-authored, which also lifts any held creator proposal. The
     * per-slop cooldown duration and the !slop toggle ride along.
     */
    fun setTriggers(
        name: String,
        rawList: List<Any?>?,
        cooldownSecondsInput: Any?,
        slopCommand: Any?,
    ): Map<String, Any?> {
        val doc = slopDao.findByName(name)
            ?: return mapOf("ok" to false, "error" to "no such slop")
        if (rawList == null) return mapOf("ok" to false, "error" to "triggers must be a list")
        if (rawList.size > TriggerEngine.MAX_TRIGGERS_PER_SLOP) {
            return mapOf("ok" to false, "error" to "at most ${TriggerEngine.MAX_TRIGGERS_PER_SLOP} triggers per slop")
        }
        val clean = mutableListOf<Trigger>()
        for (i in rawList.indices) {
            val trigger = TriggerEngine.normalizeTrigger(rawList[i], i, "streamer")
                ?: return mapOf("ok" to false, "error" to "trigger ${i + 1} is invalid (type, mode, patterns, or regex)")
            clean.add(trigger)
        }
        // NOTE: an empty saved list is KEPT (not deleted) — it's the streamer's
        // word that this slop gets no triggers.
        var cooldownSeconds: Int? = null
        if (cooldownSecondsInput != null) {
            // JS: Math.max(0, Math.min(24 * 60 * 60, Math.round(Number(x) || 0)))
            val n = TriggerEngine.numberOrZero(cooldownSecondsInput)
            val seconds = Math.max(0, Math.min(24 * 60 * 60, Math.round(n).toInt()))
            cooldownSeconds = seconds
            cooldowns.setDuration(name, seconds)
            broadcastCooldowns()
        }
        var slopCommandValue: Boolean? = null
        if (slopCommand != null) {
            slopCommandValue = TriggerEngine.truthy(slopCommand)
        }

        // Re-merge with the source's creator proposals (a streamer save may
        // adopt or lift held proposals).
        val code = sourceCache[name] ?: slopDao.readSource(name) ?: ""
        val merged = clean + mergedCreatorOnly(doc, code, clean)
        doc.triggers = merged.toMutableList()
        val saved = slopDao.update(
            name,
            SlopPatch(
                triggers = merged,
                cooldownSeconds = cooldownSeconds,
                slopCommand = slopCommandValue,
            ),
        )
        register(saved ?: doc)

        return mapOf(
            "ok" to true,
            "triggers" to merged,
            "cooldownSeconds" to cooldowns.of(name).duration,
            "slopCommand" to (slopCommandValue ?: doc.slopCommand),
        )
    }

    /** The creator proposals for a doc, minus the ones the streamer has adopted. */
    private fun mergedCreatorOnly(doc: Slop, code: String, streamerList: List<Trigger>): List<Trigger> {
        val holder = Slop()
        holder.name = doc.name
        holder.displayName = doc.displayName
        holder.triggers = streamerList.toMutableList()
        holder.metadata = doc.metadata
        val merged = mergedTriggers(holder, code)
        return merged.drop(streamerList.size)
    }

    // --- helpers -------------------------------------------------------------------

    /**
     * Resolve a user-typed slop name: exact filename, a unique suffix match,
     * or a unique all-tokens match — the reference's `matchSlopNames`. Returns
     * the candidate list; callers decide how to treat zero or several.
     */
    fun matchSlopNames(asked: String): List<String> {
        val query = asked.lowercase().trim().replace(Regex("""\.js$"""), "")
        if (query.isEmpty()) return emptyList()
        val names = registered.keys.toList()
        // An exact filename or an exact short handle wins OUTRIGHT —
        // token-subset siblings that share every token must never make an
        // exact name "ambiguous".
        val exact = "$query.js"
        if (names.contains(exact)) return listOf(exact)
        val exactShort = names.filter { n ->
            (registered[n]?.short ?: "").lowercase() == query
        }
        if (exactShort.isNotEmpty()) return exactShort
        return names.filter { n -> query.split("-").all { token -> n.contains(token) } }
    }

    /**
     * The trigger event, sanitized for the slop's eyes: a whitelist of safe,
     * useful fields. Raw chat text and internal ids never cross the boundary —
     * the slop gets what the event means (who, how big, first or not), not the
     * raw traffic.
     */
    fun triggerEventForSlop(event: SlopTriggerEvent?): Map<String, Any?>? {
        if (event == null) return null
        val out = LinkedHashMap<String, Any?>()
        out["type"] = event.type
        event.login?.let { out["login"] = it.take(30) }
        if (event.first) out["first"] = true
        for (key in listOf("viewers", "bits", "tier", "months", "count")) {
            val raw = when (key) {
                "viewers" -> event.viewers
                "bits" -> event.bits
                "tier" -> event.tier
                "months" -> event.months
                else -> event.count
            }
            val n = TriggerEngine.numberOrZero(raw)
            if (n > 0) out[key] = n.toInt()
        }
        event.rewardId?.let { out["rewardId"] = it.take(40) }
        return out
    }

    /** The overlay's liveness ping — the /slop page pings every 2s. */
    fun noteOverlayAlive() {
        overlaySeenAt = now()
    }

    fun noteIrcTraffic() {
        ircLastTrafficAt = now()
        ircConnected = true
    }

    fun noteIrcDisconnected() {
        ircConnected = false
    }

    // --- the worker ingestion surface (the thin forwarder's event feed) --------------

    /**
     * Stream-start notice from the pod: the stream is live. Trigger evaluation
     * resumes; first-ever sets persist (they never reset — first means first
     * ever, not first tonight).
     */
    fun noteStreamStart() {
        streamLive = true
        events.broadcast("stream", mapOf("live" to true))
        events.broadcast("note", mapOf("text" to "the stream is live — triggers armed"))
    }

    /** Stream-end notice from the pod: triggers idle; the queue drains. */
    fun noteStreamEnd() {
        streamLive = false
        events.broadcast("stream", mapOf("live" to false))
        events.broadcast("note", mapOf("text" to "the stream ended — triggers idle"))
    }

    /**
     * One ingested event — the thin forwarder's feed. Runs the trigger loop
     * ELM-side: first-ever tracking, allow-list, cooldowns, the flood cap,
     * then immediate-vs-queued dispatch. Returns the dispatch outcome.
     */
    fun ingest(event: SlopTriggerEvent): Map<String, Any?> {
        val dispatched = try {
            dispatchTrigger(event)
        } catch (e: Exception) {
            return mapOf("ok" to false, "dispatched" to false, "error" to (e.message ?: "dispatch failed"))
        }
        return mapOf("ok" to true, "dispatched" to dispatched)
    }

    companion object {
        const val MAX_ASSET_BYTES = 6 * 1024 * 1024
    }
}
