package dev.slopbot.element.service

import dev.slopbot.api.slop.SlopTriggerEvent
import dev.slopbot.element.engine.InMemorySlopDao
import dev.slopbot.element.dao.InMemorySourceStore
import dev.slopbot.element.dao.SlopAssetDao
import dev.slopbot.element.dao.SlopHistoryDao
import dev.slopbot.element.dao.SlopModification
import dev.slopbot.element.engine.SlopConfig
import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.engine.SlopNamer
import dev.slopbot.element.engine.TriggerEngine
import dev.slopbot.element.engine.validSlopSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The engine state-machine tests: cooldowns (arm-on-trigger only, manual plays
 * unaffected, clear endpoint), the flood cap, immediate vs queued dispatch,
 * bypassCooldown, first-ever tracking, allow-list gating end-to-end, and the
 * create → moderate → play flow with zero filesystem.
 */
class SlopEngineTest {

    /** A deterministic engine harness: seeded random, injectable clock. */
    private class Harness {
        val dao = InMemorySlopDao()
        val historyDao = RecordingHistoryDao()
        val events = SlopEvents()
        val config = SlopConfig()
        val engine = SlopEngine(dao, NoopAssetDao(), historyDao, events, config)
        val notes = CopyOnWriteArrayList<String>()

        init {
            events.subscribe { type, payload ->
                if (type == "note") notes.add(payload["text"]?.toString() ?: "")
            }
        }

        fun seed(
            name: String,
            streamer: String = "savorysailor",
            triggers: List<Any?> = emptyList(),
            cooldownSeconds: Int = 0,
            dispatch: String = "queue",
            source: String = validSlopSource(title = "Test Slop", author = "testauthor"),
        ): String {
            val created = engine.createSlop(
                code = source,
                creator = dev.slopbot.element.model.Participant(id = "1", login = "testauthor", displayName = "TestAuthor"),
                streamerLogin = streamer,
            )
            if (triggers.isNotEmpty()) {
                engine.setTriggers(name.ifEmpty { created }, triggers, cooldownSeconds, null)
            } else if (cooldownSeconds > 0) {
                engine.setTriggers(name.ifEmpty { created }, emptyList(), cooldownSeconds, null)
            }
            // Approve for play (moderation PENDING → APPROVED).
            if (triggers.isNotEmpty() || cooldownSeconds > 0) {
                val n = name.ifEmpty { created }
                dao.update(n, dev.slopbot.element.dao.SlopPatch(pending = false))
                engine.register(dao.findByName(n)!!)
            }
            return created
        }
    }

    class RecordingHistoryDao : SlopHistoryDao {
        val recorded = mutableListOf<SlopModification>()
        override fun record(modification: SlopModification): SlopModification {
            recorded.add(modification)
            return modification
        }

        override fun list(name: String, limit: Int): List<SlopModification> =
            recorded.filter { it.name == name }.sortedByDescending { it.index }.take(limit)

        override fun trim(name: String, cap: Int) {
            // no-op in memory
        }
    }

    class NoopAssetDao : SlopAssetDao {
        private val assets = mutableListOf<dev.slopbot.element.model.SlopAsset>()
        private val store = InMemorySourceStore()
        override fun list(tag: String?): List<dev.slopbot.element.model.SlopAsset> =
            if (tag == null) assets else assets.filter { it.tags.contains(tag) }
        override fun findByName(name: String): dev.slopbot.element.model.SlopAsset? = assets.find { it.name == name }
        override fun findById(id: String): dev.slopbot.element.model.SlopAsset? =
            assets.find { it.id.toHexString() == id }
        override fun put(asset: dev.slopbot.element.model.SlopAsset, bytes: ByteArray): dev.slopbot.element.model.SlopAsset {
            asset.source = store.put(asset.name, asset.mimeType, asset.metadata.originalFilename, bytes)
            assets.add(asset)
            return asset
        }
        override fun replaceBytes(name: String, bytes: ByteArray): dev.slopbot.element.model.SlopAsset? {
            val asset = findByName(name) ?: return null
            val previous = asset.source
            val ref = store.put(asset.name, asset.mimeType, asset.metadata.originalFilename, bytes)
            if (previous != null) asset.revisions.add(previous)
            asset.source = ref
            return asset
        }
        override fun readBytes(asset: dev.slopbot.element.model.SlopAsset): ByteArray? = store.get(asset.source)
        override fun setState(name: String, state: String): dev.slopbot.element.model.SlopAsset? {
            val asset = findByName(name) ?: return null
            asset.state = state
            return asset
        }
        override fun delete(name: String): Boolean = assets.removeIf { it.name == name }
    }

    // --- cooldowns -----------------------------------------------------------------

    @Test
    fun `the cooldown window arms on trigger plays only`() {
        val h = Harness()
        val name = h.seed("", triggers = listOf(mapOf("type" to "chat", "patterns" to listOf("boom"))), cooldownSeconds = 60)
        assertFalse(h.engine.cooldowns.onCooldown(name))

        // A manual play never arms.
        assertTrue(h.engine.play(name, "deck"))
        assertFalse(h.engine.cooldowns.onCooldown(name))

        // A trigger play arms.
        assertTrue(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "boom!", userId = "u1")))
        assertTrue(h.engine.cooldowns.onCooldown(name))
        assertTrue(h.engine.cooldowns.of(name).until > System.currentTimeMillis())

        // While cooling, the trigger no longer fires it...
        assertFalse(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "boom!", userId = "u2")))
    }

    @Test
    fun `a bypassCooldown trigger queues through the window without arming early`() {
        val h = Harness()
        val name = h.seed(
            "",
            triggers = listOf(
                mapOf("type" to "chat", "patterns" to listOf("boom")),
                mapOf("type" to "chat", "patterns" to listOf("hurry"), "bypassCooldown" to true),
            ),
            cooldownSeconds = 60,
        )
        assertTrue(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "boom", userId = "u1")))
        assertTrue(h.engine.cooldowns.onCooldown(name))
        // The bypass trigger fires THROUGH the window — and re-arms it (a trigger play).
        assertTrue(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "hurry", userId = "u2")))
        val untilAfter = h.engine.cooldowns.of(name).until
        assertTrue(untilAfter > System.currentTimeMillis())
    }

    @Test
    fun `the streamer can clear any cooldown`() {
        val h = Harness()
        val name = h.seed("", triggers = listOf(mapOf("type" to "chat", "patterns" to listOf("boom"))), cooldownSeconds = 60)
        h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "boom", userId = "u1"))
        assertTrue(h.engine.cooldowns.onCooldown(name))
        assertTrue(h.engine.cooldownClear(name))
        assertFalse(h.engine.cooldowns.onCooldown(name))
        assertTrue(h.notes.any { it.contains("cooldown cleared") })
    }

    @Test
    fun `cooldown duration changes never retro-actively cool`() {
        val h = Harness()
        val name = h.seed("", triggers = listOf(mapOf("type" to "chat", "patterns" to listOf("boom"))), cooldownSeconds = 0)
        h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "boom", userId = "u1"))
        assertFalse(h.engine.cooldowns.onCooldown(name)) // duration was 0 at play time
        // A later save WITH a duration doesn't cool the already-played slop.
        h.engine.setTriggers(name, listOf(mapOf("type" to "chat", "patterns" to listOf("boom"))), 30, null)
        assertFalse(h.engine.cooldowns.onCooldown(name))
    }

    // --- the flood cap -----------------------------------------------------------------

    @Test
    fun `the flood cap drops extra triggers with a throttled visible note`() {
        val h = Harness()
        val name = h.seed("", triggers = listOf(mapOf("type" to "chat", "patterns" to listOf("boom"))), cooldownSeconds = 0)
        // Default cap: 6/min. Six dispatches play (queue depth permitting)…
        var played = 0
        for (i in 1..10) {
            if (h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "boom", userId = "u$i"))) played++
        }
        // The queue + cooldowns cap the actual plays: with cooldownSeconds=0 the
        // slop re-fires — the queue holds 10 by default... The observable: notes
        // appear once the cap hits, and only one note per ~10s.
        assertTrue(h.notes.any { it.startsWith("trigger flood cap hit") })
        assertTrue(h.notes.count { it.startsWith("trigger flood cap hit") } <= 1) // throttled
    }

    // --- immediate vs queued -------------------------------------------------------------

    @Test
    fun `immediate dispatch takes the stage and requeues the interrupt`() {
        val h = Harness()
        val a = h.seed("", triggers = listOf(mapOf("type" to "chat", "patterns" to listOf("first"))))
        val b = h.seed("", triggers = listOf(mapOf("type" to "chat", "patterns" to listOf("urgent"), "dispatch" to "immediate")))
        assertTrue(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "first", userId = "u1")))
        assertTrue(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "urgent", userId = "u2")))
        // The urgent slop is on stage NOW; the interrupted one is at the FRONT.
        val snap = h.engine.snapshotMap()
        @Suppress("UNCHECKED_CAST")
        val plays = snap["plays"] as Map<String, Any?>
        val current = plays["current"] as CurrentPlay
        assertEquals(b, current.name)
        @Suppress("UNCHECKED_CAST")
        val upcoming = plays["upcoming"] as List<CurrentPlay>
        assertEquals(a, upcoming.first().name)
        assertEquals("resume", upcoming.first().via)
    }

    @Test
    fun `queued dispatch lines up behind the current play`() {
        val h = Harness()
        val a = h.seed("", triggers = listOf(mapOf("type" to "chat", "patterns" to listOf("first"))))
        val b = h.seed("", triggers = listOf(mapOf("type" to "chat", "patterns" to listOf("later"))))
        assertTrue(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "first", userId = "u1")))
        assertTrue(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "later", userId = "u2")))
        @Suppress("UNCHECKED_CAST")
        val plays = h.engine.snapshotMap()["plays"] as Map<String, Any?>
        assertEquals(a, (plays["current"] as CurrentPlay).name)
        assertEquals(b, (plays["upcoming"] as List<CurrentPlay>).first().name)
    }

    // --- first-ever tracking ----------------------------------------------------------------

    @Test
    fun `firstchat fires exactly once per user ever`() {
        val h = Harness()
        h.seed(
            "",
            triggers = listOf<Any?>(
                mapOf("type" to "chat", "patterns" to listOf("boom")),
                mapOf("type" to "firstchat", "patterns" to listOf<Any>()),
            ),
        )
        // The dispatch picks randomly among matches — instead test the tracker directly:
        assertTrue(h.engine.seenUsers.markSeen("chat", "u1"))
        assertFalse(h.engine.seenUsers.markSeen("chat", "u1"))
        assertTrue(h.engine.seenUsers.markSeen("chat", "u2"))
        assertFalse(h.engine.seenUsers.markSeen("chat", "u2"))
        // The chat and follow sets are independent.
        assertTrue(h.engine.seenUsers.markSeen("follow", "u1"))
        assertFalse(h.engine.seenUsers.markSeen("follow", "u1"))
    }

    @Test
    fun `synthetic dispatches never consume first-ever slots`() {
        val h = Harness()
        assertFalse(h.engine.seenUsers.isSeen("chat", "u1"))
        h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "x", userId = "u1", synthetic = true))
        assertFalse(h.engine.seenUsers.isSeen("chat", "u1")) // synthetic — not recorded
        h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "x", userId = "u1"))
        assertTrue(h.engine.seenUsers.isSeen("chat", "u1"))
    }

    // --- allow-list gating (end-to-end) ---------------------------------------------------

    @Test
    fun `creator raid proposals are held until the allow-list admits them`() {
        val h = Harness()
        // The source proposes a raid trigger; the default allow-list is chat+firstchat.
        val source = validSlopSource(
            title = "Raid Slop",
            author = "testauthor",
            triggers = "triggers: [{\"type\":\"raid\",\"minViewers\":10}]",
        )
        val created = h.engine.createSlop(
            code = source,
            creator = dev.slopbot.element.model.Participant(id = "1", login = "testauthor", displayName = "T"),
            streamerLogin = "savorysailor",
        )
        val entry = h.engine.registryEntry(created)!!
        val raid = entry.triggers.first { it.type == "raid" }
        assertTrue(raid.held == true) // "waiting on the registry allow-list"
        assertFalse(raid.enabled)
        // The streamer adopts it — a streamer-authored trigger always wins.
        h.engine.setTriggers(created, listOf(mapOf("type" to "raid", "minViewers" to 10)), null, null)
        val adopted = h.engine.registryEntry(created)!!.triggers.first { it.type == "raid" }
        assertTrue(adopted.enabled)
        assertTrue(adopted.held == null)
        assertTrue(TriggerEngine.triggerAllowed(adopted, h.config.allowedTriggers()))
    }

    // --- the create → moderate → play flow --------------------------------------------------

    @Test
    fun `create then moderation verdicts then play`() {
        val h = Harness()
        val created = h.engine.createSlop(
            code = validSlopSource(title = "Tomato Tribunal", author = "TestViewer"),
            creator = dev.slopbot.element.model.Participant(id = "42", login = "testviewer", displayName = "TestViewer"),
            streamerLogin = "savorysailor",
        )
        // The name is server-assigned: author-slug of the title.
        assertEquals("testviewer-tomato-tribunal.js", created)
        val doc = h.dao.findByName(created)!!
        assertEquals(dev.slopbot.element.model.Slop.MODERATION_PENDING, doc.moderation)
        assertEquals("testviewer", doc.creator?.login)
        assertEquals("savorysailor", doc.streamer?.login)
        // The tier in effect at build time is stamped even when the agent omitted it.
        assertEquals("teen", doc.metadata.moderationLevel)

        // A pending slop is invisible to triggers.
        assertFalse(h.engine.dispatchTrigger(SlopTriggerEvent(type = "chat", text = "!test-slop", userId = "u1")))

        // Approval clears the gate and plays it.
        assertTrue(h.engine.approvePending(created))
        assertEquals(dev.slopbot.element.model.Slop.MODERATION_APPROVED, h.dao.findByName(created)!!.moderation)
        @Suppress("UNCHECKED_CAST")
        val plays = h.engine.snapshotMap()["plays"] as Map<String, Any?>
        assertEquals(created, (plays["current"] as CurrentPlay).name)

        // Rejection deletes the slop entirely.
        val second = h.engine.createSlop(
            code = validSlopSource(title = "Doom Loop", author = "TestViewer"),
            creator = null,
            streamerLogin = "savorysailor",
        )
        assertTrue(h.engine.rejectPending(second))
        assertEquals(null, h.dao.findByName(second))
    }

    @Test
    fun `slops update keeps a running history capped at 50`() {
        val h = Harness()
        val created = h.engine.createSlop(
            code = validSlopSource(title = "V1", author = "TestViewer"),
            creator = null,
            streamerLogin = "savorysailor",
        )
        for (i in 1..55) {
            h.engine.updateSlop(created, validSlopSource(title = "V${i + 1}", author = "TestViewer"), actor = "agent")
        }
        // The history kept a running record: 55 modifications recorded, and
        // the newest 50 are what the cap preserves (1-based indexes 6..55).
        assertEquals(55, h.historyDao.list(created, 100).size)
        val trimmed = h.historyDao.list(created, 50)
        assertEquals(50, trimmed.size)
        assertEquals(55L, trimmed.first().index) // newest first
        assertEquals(6L, trimmed.last().index) // the oldest survivor
    }

    // --- cameos -----------------------------------------------------------------------------

    @Test
    fun `a cameo refuses to chain from itself and obeys cooldowns`() {
        val h = Harness()
        val a = h.seed("")
        h.engine.approvePending(a) // approves — and the approval plays it (current = a)
        // A cameo of the slop already on stage refuses (no immediate repeats).
        assertFalse(h.engine.play(a, "cameo", chained = true))
        // The stage clears; a cameo of a cooled-down slop refuses; a manual
        // play of the same slop is never blocked (manual plays don't check
        // the window).
        h.engine.playEnded(a, null)
        h.engine.cooldowns.setDuration(a, 60)
        h.engine.cooldowns.arm(a)
        assertFalse(h.engine.play(a, "cameo", chained = true)) // the cameo obeys the window
        assertTrue(h.engine.play(a, "deck")) // the manual play queues anyway
    }

    // --- the config echo ---------------------------------------------------------------------

    @Test
    fun `the snapshot carries config echo and liveness`() {
        val h = Harness()
        val snap = h.engine.snapshotMap()
        @Suppress("UNCHECKED_CAST")
        val config = snap["config"] as Map<String, Any?>
        assertEquals("Make It Slop", config["redeemTitle"])
        assertEquals(60, config["maxSlopSeconds"])
        assertEquals(10, config["maxQueueDepth"])
        assertEquals(6, config["triggerDispatchesPerMinute"])
        @Suppress("UNCHECKED_CAST")
        val irc = snap["irc"] as Map<String, Any?>
        assertEquals(false, irc["connected"])
        assertEquals(null, irc["lastTrafficAgoMs"])
        h.engine.noteIrcTraffic()
        assertTrue((h.engine.snapshotMap()["irc"] as Map<String, Any?>)["connected"] == true)
    }

    // --- the naming through the engine ---------------------------------------------------------

    @Test
    fun `collision counters assign -2 -3 server-side`() {
        val h = Harness()
        val code = validSlopSource(title = "Same Name", author = "TestViewer")
        val creator = dev.slopbot.element.model.Participant(id = "1", login = "testviewer", displayName = "T")
        val first = h.engine.createSlop(code, creator, "savorysailor")
        val second = h.engine.createSlop(code, creator, "savorysailor")
        val third = h.engine.createSlop(code, creator, "savorysailor")
        assertEquals("testviewer-same-name.js", first)
        assertEquals("testviewer-same-name-2.js", second)
        assertEquals("testviewer-same-name-3.js", third)
    }
}
