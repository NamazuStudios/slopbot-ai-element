package dev.slopbot.element.ws

import dev.slopbot.element.dao.SlopPatch
import dev.slopbot.element.engine.InMemorySlopDao
import dev.slopbot.element.engine.SlopConfig
import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.engine.validSlopSource
import dev.slopbot.element.service.CurrentPlay
import dev.slopbot.element.service.SlopEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The websocket transport tests: the ELM's pod-client protocol (events in →
 * the ELM-side trigger loop; playback commands out) and the pages' server
 * relay. The thin Node forwarder owns the pod side; the ELM owns evaluation.
 */
class PodWebsocketTest {

    class NoopAssetDao : dev.slopbot.element.dao.SlopAssetDao {
        val assets = mutableListOf<dev.slopbot.element.model.SlopAsset>()
        val store = dev.slopbot.element.dao.InMemorySourceStore()
        override fun list(tag: String?): List<dev.slopbot.element.model.SlopAsset> =
            if (tag == null) assets.toList() else assets.filter { it.tags.contains(tag) }
        override fun findByName(name: String) = assets.find { it.name == name }
        override fun findById(id: String) = assets.find { it.id.toHexString() == id }
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
        override fun readBytes(asset: dev.slopbot.element.model.SlopAsset) = store.get(asset.source)
        override fun setState(name: String, state: String): dev.slopbot.element.model.SlopAsset? {
            val asset = findByName(name) ?: return null
            asset.state = state
            return asset
        }
        override fun delete(name: String): Boolean = assets.removeIf { it.name == name }
    }

    class NoopHistoryDao : dev.slopbot.element.dao.SlopHistoryDao {
        override fun record(m: dev.slopbot.element.dao.SlopModification) = m
        override fun list(name: String, limit: Int) = emptyList<dev.slopbot.element.dao.SlopModification>()
        override fun trim(name: String, cap: Int) {}
    }

    private class Harness {
        val dao = InMemorySlopDao()
        val events = SlopEvents()
        val config = SlopConfig()
        val engine = SlopEngine(dao, NoopAssetDao(), NoopHistoryDao(), events, config)
        val protocol = PodWsProtocol(engine, events)

        /** The recorded outbound command frames (what the pod would receive). */
        val sentFrames = mutableListOf<String>()

        init {
            protocol.wire(object : PodWsProtocol.CommandSink {
                override fun send(frame: String): Boolean {
                    sentFrames.add(frame)
                    return true
                }
            })
        }

        fun seedPending(title: String): String {
            val created = engine.createSlop(
                code = validSlopSource(title = title, author = "TestViewer"),
                creator = null,
                streamerLogin = "savorysailor",
            )
            return created
        }

        fun approve(name: String) {
            dao.update(name, SlopPatch(pending = false))
            engine.register(dao.findByName(name)!!)
        }
    }

    // --- pod → ELM: the trigger loop over the websocket ---------------------------------

    @Test
    fun `a forwarded chat line runs the trigger loop and dispatches`() {
        val h = Harness()
        val created = h.seedPending("Boom Slop")
        h.approve(created)

        // The pod forwards a chat line — the default activation trigger
        // ("!boom-slop", word-matched) fires.
        val results = h.protocol.handlePodFrame("""{"type":"chat","text":"hey !boom-slop","userId":"u1","login":"viewer"}""")
        assertEquals(1, results.size)
        assertEquals(true, results[0]["dispatched"])
        @Suppress("UNCHECKED_CAST")
        val plays = h.engine.snapshotMap()["plays"] as Map<String, Any?>
        assertEquals(created, (plays["current"] as CurrentPlay).name)
    }

    @Test
    fun `a coalesced frame batch runs every event`() {
        val h = Harness()
        val created = h.seedPending("Batch Slop")
        h.approve(created)

        val results = h.protocol.handlePodFrame(
            """{"events":[{"type":"chat","text":"nothing","userId":"u1"},""" +
                """{"type":"chat","text":"!batch-slop","userId":"u2"}]}""",
        )
        assertEquals(2, results.size)
        assertEquals(false, results[0]["dispatched"])
        assertEquals(true, results[1]["dispatched"])
    }

    @Test
    fun `stream notices flip the stream state over the wire`() {
        val h = Harness()
        assertFalse(h.engine.streamLive)
        h.protocol.handlePodFrame("""{"type":"stream-start"}""")
        assertTrue(h.engine.streamLive)
        h.protocol.handlePodFrame("""{"type":"stream-end"}""")
        assertFalse(h.engine.streamLive)
    }

    @Test
    fun `a malformed frame is refused without breaking the connection`() {
        val h = Harness()
        val results = h.protocol.handlePodFrame("not json")
        assertEquals(false, results[0]["ok"])
        // And a JSON array element that is not an object:
        val results2 = h.protocol.handlePodFrame("""{"events":["junk"]}""")
        assertEquals(false, results2[0]["ok"])
    }

    @Test
    fun `cooldowns and the flood cap hold over the websocket`() {
        val h = Harness()
        val created = h.seedPending("Cool Slop")
        h.engine.setTriggers(created, listOf<Any?>(mapOf("type" to "chat", "patterns" to listOf("boom"))), 60, null)
        h.approve(created)

        val first = h.protocol.handlePodFrame("""{"type":"chat","text":"boom","userId":"u1"}""")
        assertEquals(true, first[0]["dispatched"])
        assertTrue(h.engine.cooldowns.onCooldown(created))
        val second = h.protocol.handlePodFrame("""{"type":"chat","text":"boom","userId":"u2"}""")
        assertEquals(false, second[0]["dispatched"])

        // The streamer's clear frees it over the same path.
        h.engine.cooldownClear(created)
        val third = h.protocol.handlePodFrame("""{"type":"chat","text":"boom","userId":"u3"}""")
        assertEquals(true, third[0]["dispatched"])
    }

    // --- ELM → pod: the playback commands --------------------------------------------------

    @Test
    fun `a dispatched play relays a command frame to the pod`() {
        val h = Harness()
        val created = h.seedPending("Relay Slop")
        h.approve(created)

        h.engine.play(created, "deck")
        assertEquals(1, h.sentFrames.size)
        val frame = com.fasterxml.jackson.databind.ObjectMapper().readValue(h.sentFrames[0], Map::class.java)
        assertEquals("play", frame["command"])
        assertEquals(created, frame["name"])
    }

    @Test
    fun `a stage clear relays and a non-play event does not`() {
        val h = Harness()
        val created = h.seedPending("Stage Slop")
        h.approve(created)

        h.engine.play(created, "deck") // play → relayed
        h.engine.playEnded(created, null) // the plays updates → relayed
        // play (1) + the playEnded queue broadcasts (plays ×2: the drop and
        // the empty-queue update) — all relayed types.
        assertEquals(3, h.sentFrames.size)
        assertEquals("play", com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(h.sentFrames[0], Map::class.java)["command"])
        assertEquals("plays", com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(h.sentFrames[1], Map::class.java)["command"])

        // An unrelated broadcast (config) never reaches the pod.
        h.events.broadcast("config", mapOf("key" to "value"))
        assertEquals(3, h.sentFrames.size)
    }

    // --- the pages' server relay -------------------------------------------------------------

    @Test
    fun `every engine event fans out to each connected page`() {
        val h = Harness()
        val relay = PageRelay(h.events).wire()
        val page1 = RecordingPage()
        val page2 = RecordingPage()
        relay.register(page1)
        relay.register(page2)

        val created = h.seedPending("Page Slop")
        h.approve(created)
        h.engine.play(created, "deck")

        // Both pages saw the play.
        assertTrue(page1.frames.any { it.contains("\"play\"") })
        assertTrue(page2.frames.any { it.contains("\"play\"") })

        // A failed page is reaped on the failed send, the good ones still get
        // the next broadcast.
        val dead = RecordingPage(fail = true)
        relay.register(dead)
        assertEquals(3, relay.sessionCount())
        h.events.broadcast("note", mapOf("text" to "goodbye"))
        assertEquals(2, relay.sessionCount())
        assertTrue(page1.frames.any { it.contains("goodbye") })
        assertTrue(page2.frames.any { it.contains("goodbye") })
    }

    class RecordingPage(private val fail: Boolean = false) : PageRelay.PageSession {
        val frames = mutableListOf<String>()
        override fun send(text: String): Boolean {
            if (fail) return false
            frames.add(text)
            return true
        }

        override fun close() {}
    }

    // --- raw passthrough ------------------------------------------------------------------

    @Test
    fun `a forwarded chat event also passes through to the pages`() {
        val h = Harness()
        val relay = PageRelay(h.events).wire()
        val page = RecordingPage()
        relay.register(page)

        h.protocol.handlePodFrame("""{"type":"chat","text":"hello chat","login":"viewer"}""")
        assertTrue(page.frames.any { it.contains("hello chat") })
    }
}
