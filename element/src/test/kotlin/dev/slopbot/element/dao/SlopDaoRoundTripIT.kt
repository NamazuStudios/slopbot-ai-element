package dev.slopbot.element.dao

import dev.morphia.Morphia
import dev.slopbot.element.model.Remark
import dev.slopbot.element.model.Slop
import dev.slopbot.element.model.SlopAsset
import dev.slopbot.element.model.Trigger
import dev.slopbot.element.spi.SlopbotDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * The Slop → GridFS source → bundle → equality round-trip (the
 * `store-roundtrip.js` port), against a REAL MongoDB. Requires MongoDB —
 * excluded from the default build; runs under the `-Pintegration` profile:
 *
 *     MONGO_URI=mongodb://localhost:27017 mvn verify -Pintegration -pl element
 *
 * Exercises: connect → idempotent-ish import (put) → DB-authoritative read →
 * source equality (DB ↔ playback round-trip) → write-throughs (triggers,
 * suspend, cooldown, moderation, play stats) → delete (record + source + own
 * assets).
 */
@EnabledIfEnvironmentVariable(named = "MONGO_URI", matches = ".+")
class SlopDaoRoundTripIT {

    private fun database(): SlopbotDatabase {
        val client = com.mongodb.client.MongoClients.create(System.getenv("MONGO_URI"))
        val datastore = Morphia.createDatastore(client, "slopbot-roundtrip-test")
        datastore.mapper.apply {
            map(Slop::class.java)
            map(SlopAsset::class.java)
            map(dev.slopbot.element.model.SlopAudit::class.java)
            map(SlopModification::class.java)
        }
        datastore.ensureIndexes()
        return SlopbotDatabase(datastore)
    }

    private fun slopSource(): String = """
        // slops/roundtrip — sound.sample("boing"); ctx.done();
        export const meta = { title: 'Round Trip Test', author: 'testviewer' };
        export function init(ctx) { return; }
        export function frame(ctx, dt) { return false; }
    """.trimIndent()

    @Test
    fun `round trip`() {
        val database = database()
        val sourceStore = GridFsSourceStore(database)
        val assetDao = StandardSlopAssetDao(database, sourceStore)
        val dao = StandardSlopDao(database, sourceStore, assetDao)

        val name = "roundtrip-${System.currentTimeMillis().toString(36)}.js"
        val slop = Slop()
        slop.name = name
        slop.displayName = "Round Trip Test"
        slop.moderation = Slop.MODERATION_PENDING
        slop.triggers = mutableListOf(
            Trigger(
                id = "t0",
                type = "chat",
                mode = "word",
                patterns = mutableListOf("boo"),
                source = "streamer",
            ),
        )
        slop.cooldownSeconds = 30
        slop.agentRemarks.add(Remark(text = "build remark", kind = Remark.KIND_AGENT_BUILD, timestamp = 1L, author = "agent"))
        slop.contentRemarks.add(Remark(text = "content remark", kind = Remark.KIND_CONTENT, timestamp = 2L))

        val code = slopSource()

        // put (upsert by name; the streamer recorded, the creator recorded).
        // The metadata.author mirrors the reference's entry.author (from metaOf).
        slop.metadata.author = "testviewer"
        val saved = dao.put(
            slop,
            code,
            tags = emptyList(),
            streamerLogin = "savorysailor",
        )
        assertEquals(name, saved.name)
        assertEquals("savorysailor", saved.streamer?.login)
        assertEquals("testviewer", saved.creator?.login)
        assertEquals(1, saved.agentRemarks.size)
        assertEquals(1, saved.contentRemarks.size)

        // DB-authoritative read.
        val doc = dao.findByName(name)
        assertNotNull(doc)
        assertEquals("Round Trip Test", doc!!.displayName)
        assertEquals(30, doc.cooldownSeconds)
        assertEquals(1, doc.triggers.size)

        // Source round-trip (DB ↔ playback): identical bytes.
        assertEquals(code, dao.readSource(name))

        // Write-throughs: triggers, suspend, cooldown, moderation.
        val updated = dao.update(
            name,
            SlopPatch(
                triggers = emptyList(),
                suspended = true,
                cooldownSeconds = 5,
                slopCommand = false,
                pending = false,
            ),
        )
        assertNotNull(updated)
        assertEquals(0, updated!!.triggers.size)
        assertTrue(updated.suspended)
        assertEquals(5, updated.cooldownSeconds)
        assertFalse(updated.slopCommand)
        assertEquals(Slop.MODERATION_APPROVED, updated.moderation)

        // Play statistics ride in the record ($inc + lastPlayedAt).
        dao.bumpPlay(name)
        dao.bumpPlay(name)
        assertEquals(2, dao.findByName(name)!!.playCount)
        assertNotNull(dao.findByName(name)!!.lastPlayedAt)

        // Tenant scoping: the streamer's own query finds it.
        assertEquals(1, dao.findByStreamer("savorysailor").count { it.name == name })

        // Delete: the record, its source, and its own assets go with it.
        assertTrue(dao.delete(name))
        assertNull(dao.findByName(name))
        assertNull(dao.readSource(name))
    }

    @Test
    fun `asset round trip`() {
        val database = database()
        val sourceStore = GridFsSourceStore(database)
        val assetDao = StandardSlopAssetDao(database, sourceStore)

        val name = "shared/roundtrip-${System.currentTimeMillis().toString(36)}"
        val asset = dev.slopbot.element.model.SlopAsset()
        asset.name = name
        asset.displayName = name.substringAfter('/')
        asset.kind = dev.slopbot.element.model.SlopAsset.KIND_AUDIO
        asset.mimeType = "audio/mpeg"
        asset.tags = mutableListOf("shared")
        asset.metadata.originalFilename = "boing.mp3"

        val bytes = "fake-mp3-bytes".toByteArray()
        val saved = assetDao.put(asset, bytes)
        assertEquals(name, saved.name)
        assertEquals("UPLOADED", saved.source?.state)

        assertEquals(bytes.toList(), assetDao.readBytes(saved)!!.toList())
        assertEquals(bytes.size, sourceStore.sizeOf(saved.source))

        assertTrue(assetDao.delete(name))
        assertNull(assetDao.findByName(name))
    }
}
