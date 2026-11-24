package dev.slopbot.element.service

import dev.slopbot.element.dao.SlopAuditDao
import dev.slopbot.element.dao.SlopAuditor
import dev.slopbot.element.dao.SlopHistoryDao
import dev.slopbot.element.dao.SlopModification
import dev.slopbot.element.engine.InMemorySlopAssetDao
import dev.slopbot.element.engine.InMemorySlopDao
import dev.slopbot.element.engine.SlopConfig
import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.engine.validSlopSource
import dev.slopbot.element.model.Participant
import dev.slopbot.element.model.SlopAsset
import dev.slopbot.element.rest.SlopMultipart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The asset-admin tests: upload → bundle find (the acceptance case), retire
 * semantics (graceful skip, existing bundles unaffected), the referenced-by
 * scan and the guarded delete, replace (new revision + pinned old), the
 * validation rejections, and the slop reassign with its audit row — plus the
 * multipart parser the upload routes ride on.
 */
class SlopAssetAdminTest {

    private class RecordingAuditDao : SlopAuditDao {
        val rows = mutableListOf<dev.slopbot.element.model.SlopAudit>()
        override fun record(audit: dev.slopbot.element.model.SlopAudit): dev.slopbot.element.model.SlopAudit {
            rows.add(audit)
            return audit
        }
        override fun list(limit: Int) = rows.sortedByDescending { it.timestamp }.take(limit)
    }

    private class NoopHistoryDao : SlopHistoryDao {
        override fun record(m: SlopModification) = m
        override fun list(name: String, limit: Int): List<SlopModification> = emptyList()
        override fun trim(name: String, cap: Int) {}
    }

    private val assetDao = InMemorySlopAssetDao()
    private val slopDao = InMemorySlopDao()
    private val auditDao = RecordingAuditDao()
    private val admin = SlopAssetAdmin(assetDao, slopDao, assetDao.store, SlopAuditor(auditDao))
    private val engine = SlopEngine(slopDao, assetDao, NoopHistoryDao(), SlopEvents(), SlopConfig())

    // --- the acceptance case: upload → bundle find -----------------------------------

    @Test
    fun `upload boing mp3 and a slop sampling boing finds it at the next bundle`() {
        val name = engine.createSlop(
            code = validSlopSource(title = "Boing Slop", author = "testauthor") +
                "\nfunction extra() { sound.sample('boing'); }",
            creator = null,
            streamerLogin = "savorysailor",
        )
        // Before the upload: no assets in the bundle.
        assertTrue((engine.bundle(name, chained = false, playId = null)["assets"] as List<*>).isEmpty())

        val bytes = ByteArray(128) { it.toByte() }
        val asset = admin.upload("boing.mp3", null, null, null, bytes, actor = "root")
        assertEquals("shared/boing", asset.name)
        assertEquals("boing", asset.displayName)
        assertEquals(SlopAsset.KIND_AUDIO, asset.kind)
        assertEquals("audio/mpeg", asset.mimeType)
        assertEquals(listOf("shared"), asset.tags)
        assertEquals("boing.mp3", asset.metadata.originalFilename)
        assertEquals(SlopAsset.STATE_ACTIVE, asset.state)

        // The next bundle carries the sample, byte-for-byte.
        val bundle = engine.bundle(name, chained = false, playId = null)
        val assets = bundle["assets"] as List<Map<String, Any?>>
        assertEquals(1, assets.size)
        assertEquals("boing", assets[0]["name"])
        assertEquals("audio", assets[0]["kind"])
        assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), assets[0]["base64"])

        // And the audit row: who, whom, what, when.
        val row = auditDao.rows.last()
        assertEquals("asset-upload", row.action)
        assertEquals("shared/boing", row.target)
        assertEquals("root", row.actor)
        assertTrue(row.payloadDigest!!.isNotEmpty())
    }

    @Test
    fun `a shared video sampled by ctx media video is inlined`() {
        val name = engine.createSlop(
            code = validSlopSource(title = "Video Slop", author = "testauthor") +
                "\nfunction extra() { ctx.media.video('clip'); }",
            creator = null,
            streamerLogin = "savorysailor",
        )
        val bytes = "VIDEOBYTES".toByteArray()
        val asset = admin.upload("clip.webm", null, null, null, bytes, actor = "root")
        assertEquals(SlopAsset.KIND_VIDEO, asset.kind)
        assertEquals("video/webm", asset.mimeType)

        val assets = engine.bundle(name, chained = false, playId = null)["assets"] as List<Map<String, Any?>>
        assertEquals(1, assets.size)
        assertEquals("clip", assets[0]["name"])
        assertEquals("video", assets[0]["kind"])
        assertEquals("video/webm", assets[0]["mime"])
    }

    // --- retire semantics -----------------------------------------------------------

    @Test
    fun `retire excludes the asset from new bundles but existing bundles keep working`() {
        val name = engine.createSlop(
            code = validSlopSource(title = "Boing Slop", author = "testauthor") +
                "\nfunction extra() { sound.sample('boing'); }",
            creator = null,
            streamerLogin = "savorysailor",
        )
        val asset = admin.upload("boing.mp3", null, null, null, "AUDIO".toByteArray(), actor = "root")

        // The existing bundle (before retire) carries the sample — it keeps working.
        val before = engine.bundle(name, chained = false, playId = null)
        assertEquals(1, (before["assets"] as List<*>).size)

        val retired = admin.retire(asset.id.toHexString(), actor = "root")
        assertEquals(SlopAsset.STATE_RETIRED, retired.state)
        assertEquals("asset-retire", auditDao.rows.last().action)

        // New bundles skip it — gracefully (log + skip), never a failure.
        val after = engine.bundle(name, chained = false, playId = null)
        assertTrue((after["assets"] as List<*>).isEmpty())
        // The code itself still bundles fine.
        assertTrue((after["codeBase64"] as String).isNotEmpty())

        // Unretire: back in the pool for new bundles.
        val restored = admin.unretire(asset.id.toHexString(), actor = "root")
        assertEquals(SlopAsset.STATE_ACTIVE, restored.state)
        assertEquals(1, (engine.bundle(name, chained = false, playId = null)["assets"] as List<*>).size)
    }

    // --- referenced-by + guarded delete ------------------------------------------------

    @Test
    fun `referenced-by counts the sampling slops and delete refuses referenced assets`() {
        val bytes = "AUDIO".toByteArray()
        admin.upload("boing.mp3", null, null, null, bytes, actor = "root")
        val referencing1 = engine.createSlop(
            code = validSlopSource(title = "One", author = "a") + "\nfunction x() { sound.sample('boing'); }",
            creator = null,
            streamerLogin = "savorysailor",
        )
        val referencing2 = engine.createSlop(
            code = validSlopSource(title = "Two", author = "b") + "\nfunction x() { sound.sample('boing'); }",
            creator = null,
            streamerLogin = "savorysailor",
        )
        engine.createSlop(
            code = validSlopSource(title = "Three", author = "c"),
            creator = null,
            streamerLogin = "savorysailor",
        )

        val asset = assetDao.findByName("shared/boing")!!
        assertEquals(listOf(referencing1, referencing2).sorted(), admin.referencedBy(asset).sorted())
        assertEquals(2, admin.referencedBy(asset, admin.sharedReferenceIndex()).size)

        // DELETE refuses a referenced asset.
        val refused = assertThrows<IllegalStateException> {
            admin.delete(asset.id.toHexString(), actor = "root")
        }
        assertTrue(refused.message!!.contains("referenced by 2 slop(s)"))
        assertNotNull(assetDao.findByName("shared/boing"))

        // An unreferenced asset deletes fine.
        val spare = admin.upload("spare.mp3", null, null, null, bytes, actor = "root")
        admin.delete(spare.id.toHexString(), actor = "root")
        assertNull(assetDao.findByName("shared/spare"))
        assertEquals("asset-delete", auditDao.rows.last().action)
    }

    @Test
    fun `a per-slop asset is referenced by its owning slop while it exists`() {
        val name = engine.createSlop(
            code = validSlopSource(title = "Owns", author = "a"),
            creator = null,
            streamerLogin = "savorysailor",
        )
        val bytes = "AUDIO".toByteArray()
        val asset = admin.upload("jingle.mp3", null, name, null, bytes, actor = "root")
        assertEquals("$name/jingle", asset.name)
        assertEquals(listOf("slop", name), asset.tags)

        // The owning slop exists → referenced, delete refused.
        assertThrows<IllegalStateException> { admin.delete(asset.id.toHexString(), actor = "root") }

        // Once the owning slop is gone, the asset is unreferenced → deletable.
        slopDao.delete(name)
        admin.delete(asset.id.toHexString(), actor = "root")
        assertNull(assetDao.findByName("$name/jingle"))
    }

    // --- replace: new revision + pinned old ---------------------------------------------

    @Test
    fun `replace creates a new revision with the old one pinned in the store`() {
        val name = engine.createSlop(
            code = validSlopSource(title = "Boing Slop", author = "testauthor") +
                "\nfunction extra() { sound.sample('boing'); }",
            creator = null,
            streamerLogin = "savorysailor",
        )
        val first = "OLD".toByteArray()
        val asset = admin.upload("boing.mp3", null, null, null, first, actor = "root")
        val oldId = asset.source!!.id!!

        val replaced = admin.replaceBytes(asset.id.toHexString(), "boing.mp3", null, "NEW".toByteArray(), actor = "root")

        // A NEW LargeObject revision; the record points at it.
        assertTrue(replaced.source!!.id != oldId)
        assertEquals(1, replaced.revisions.size)
        assertEquals(oldId, replaced.revisions[0].id)

        // The old revision is still in the store (pinned) with its bytes…
        assertTrue(first.contentEquals(assetDao.store.get(replaced.revisions[0])), "old revision bytes pinned")
        // …and the current bytes read through.
        assertTrue("NEW".toByteArray().contentEquals(assetDao.readBytes(replaced)))

        // Slops keep working: the next bundle inlines the new revision.
        val assets = engine.bundle(name, chained = false, playId = null)["assets"] as List<Map<String, Any?>>
        assertEquals(java.util.Base64.getEncoder().encodeToString("NEW".toByteArray()), assets[0]["base64"])

        val row = auditDao.rows.last()
        assertEquals("asset-replace", row.action)
        assertEquals("shared/boing", row.target)
    }

    @Test
    fun `replace rejects a kind mismatch and an oversize payload`() {
        val asset = admin.upload("boing.mp3", null, null, null, "OLD".toByteArray(), actor = "root")
        val id = asset.id.toHexString()

        assertThrows<IllegalArgumentException> {
            admin.replaceBytes(id, "clip.mp4", null, "VIDEO".toByteArray(), actor = "root")
        }
        assertThrows<IllegalArgumentException> {
            admin.replaceBytes(id, "boing.mp3", null, ByteArray(SlopAssetAdmin.MAX_UPLOAD_BYTES + 1), actor = "root")
        }
    }

    // --- validation rejections ------------------------------------------------------------

    @Test
    fun `validation rejects wrong extensions oversize and path shapes`() {
        // Wrong extension.
        val e1 = assertThrows<IllegalArgumentException> {
            admin.validateUpload("boing.txt", null, null, "X".toByteArray())
        }
        assertTrue(e1.message!!.contains("unsupported asset extension"))

        // Oversize: a hair over the 8 MiB cap.
        val e2 = assertThrows<IllegalArgumentException> {
            admin.validateUpload("boing.mp3", null, null, ByteArray(SlopAssetAdmin.MAX_UPLOAD_BYTES + 1))
        }
        assertTrue(e2.message!!.contains("MiB cap"))

        // Path-shaped name (after bare-name extraction, `..` is still a path shape).
        val e3 = assertThrows<IllegalArgumentException> {
            admin.validateUpload(null, "..mp3", null, "X".toByteArray())
        }
        assertTrue(e3.message!!.contains("bare name"))

        // No extension at all.
        val e4 = assertThrows<IllegalArgumentException> {
            admin.validateUpload("boing", null, null, "X".toByteArray())
        }
        assertTrue(e4.message!!.contains("no extension"))

        // Mime/extension family mismatch.
        assertThrows<IllegalArgumentException> {
            admin.validateUpload("boing.mp3", null, "video/mp4", "X".toByteArray())
        }

        // Empty upload.
        assertThrows<IllegalArgumentException> {
            admin.validateUpload("boing.mp3", null, null, ByteArray(0))
        }
    }

    @Test
    fun `name normalization lowercases strips directories and collapses whitespace`() {
        assertEquals("boing.mp3", admin.normalizeBareName("BOING.MP3"))
        assertEquals("boing.mp3", admin.normalizeBareName("sounds/boing.mp3"))
        assertEquals("boing.mp3", admin.normalizeBareName("C:\\temp\\boing.mp3"))
        assertEquals("laser-zap.mp3", admin.normalizeBareName("laser zap.mp3"))
        // The upload derives its display name (bare, extensionless) from it.
        val asset = admin.upload("sounds/Laser Zap.MP3", null, null, null, "X".toByteArray(), actor = "root")
        assertEquals("shared/laser-zap", asset.name)
        assertEquals("laser-zap", asset.displayName)
    }

    @Test
    fun `duplicate uploads are refused`() {
        admin.upload("boing.mp3", null, null, null, "X".toByteArray(), actor = "root")
        val e = assertThrows<IllegalStateException> {
            admin.upload("boing.mp3", null, null, null, "Y".toByteArray(), actor = "root")
        }
        assertTrue(e.message!!.contains("already exists"))
    }

    // --- reassign (attribution repair) ---------------------------------------------------

    @Test
    fun `reassign changes owner and creator with an audit row`() {
        val created = slopDao.put(
            dev.slopbot.element.model.Slop().apply {
                name = "testauthor-attrib.js"
                displayName = "Attrib"
            },
            validSlopSource(title = "Attrib", author = "testauthor"),
            tags = emptyList(),
            streamerLogin = "savorysailor",
        )
        assertEquals("savorysailor", created.streamer?.login)

        val updated = admin.reassign(
            name = "testauthor-attrib.js",
            ownerLogin = "Othersailor",
            creator = Participant(id = "9", login = "newcreator", displayName = "NewCreator"),
            actor = "root",
            actingAs = null,
            payload = mapOf("owner" to "Othersailor"),
        )
        assertEquals("othersailor", updated.streamer?.login)
        assertEquals("newcreator", updated.creator?.login)
        assertEquals("NewCreator", updated.creator?.displayName)
        // The write-through landed on the record.
        assertEquals("othersailor", slopDao.findByName("testauthor-attrib.js")!!.streamer?.login)

        val row = auditDao.rows.last()
        assertEquals("reassign", row.action)
        assertEquals("testauthor-attrib.js", row.target)
        assertEquals("root", row.actor)
        assertNotNull(row.payloadDigest)
    }

    @Test
    fun `reassign with nothing to change is rejected and unknown slops are 404-shaped`() {
        slopDao.put(
            dev.slopbot.element.model.Slop().apply { name = "testauthor-nothing.js"; displayName = "Nothing" },
            validSlopSource(title = "Nothing", author = "testauthor"),
            tags = emptyList(),
            streamerLogin = "savorysailor",
        )
        assertThrows<IllegalArgumentException> {
            admin.reassign("testauthor-nothing.js", null, null, "root", null, null)
        }
        assertThrows<NoSuchElementException> {
            admin.reassign("no-such-slop.js", "someone", null, "root", null, mapOf("owner" to "someone"))
        }
    }

    // --- the multipart parser the upload routes ride on ----------------------------------

    @Test
    fun `the multipart parser handles binary parts and quoted boundaries`() {
        val boundary = "XbOuNdArY"
        // Binary payload that embeds `--` sequences (but not the full boundary).
        val binary = ByteArray(64) { if (it % 2 == 0) 0x2D.toByte() else it.toByte() }
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"boing.mp3\"\r\n")
            append("Content-Type: audio/mpeg\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1) + binary + (
            "\r\n--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"name\"\r\n" +
                "\r\n" +
                "boing" +
                "\r\n--$boundary--\r\n"
            ).toByteArray(Charsets.ISO_8859_1)

        val parts = SlopMultipart.parse("multipart/form-data; boundary=\"$boundary\"", body)
        assertEquals(2, parts.size)
        assertEquals("file", parts[0].name)
        assertEquals("boing.mp3", parts[0].filename)
        assertEquals("audio/mpeg", parts[0].mimeType)
        assertTrue(parts[0].bytes.contentEquals(binary))
        assertEquals("name", parts[1].name)
        assertEquals("boing", parts[1].bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `the multipart parser fails loudly on a missing boundary or an unterminated body`() {
        assertThrows<IllegalArgumentException> {
            SlopMultipart.parse("multipart/form-data", "no boundary here".toByteArray())
        }
        assertThrows<IllegalArgumentException> {
            SlopMultipart.parse("multipart/form-data; boundary=b", "--b\r\nX".toByteArray())
        }
        // A zero-byte file part parses (headers only, empty content).
        val body = "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"e.mp3\"\r\n\r\n--b--\r\n"
        val parts = SlopMultipart.parse("multipart/form-data; boundary=b", body.toByteArray())
        assertEquals(1, parts.size)
        assertEquals(0, parts[0].bytes.size)
        assertEquals("e.mp3", parts[0].filename)
    }
}
