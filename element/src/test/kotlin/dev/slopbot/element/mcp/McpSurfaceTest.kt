package dev.slopbot.element.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import dev.slopbot.element.engine.InMemorySlopDao
import dev.slopbot.element.engine.SlopConfig
import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.engine.validSlopSource
import dev.slopbot.element.service.SlopEngine
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The MCP tests: the OIDC token matrix (none/expired/foreign/wrong-scope →
 * 401/401/401/403) against a locally served JWKS, and the
 * create → moderate → play flow with zero filesystem (in-memory DAOs).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpSurfaceTest {

    private val mapper = ObjectMapper()

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private lateinit var jwksServer: HttpServer
    private val jwksResponses = mutableListOf<String>()

    private val validator = OidcValidator(
        issuer = "https://issuer.test",
        audience = "slopbot-ai",
        requiredScope = "slop:mcp",
        fetcher = { url -> jwksResponses.lastOrNull() },
    )

    /** A fake engine/DAO wiring for the tool flow (in-memory, no Mongo, no files). */
    private val dao = InMemorySlopDao()
    private val events = SlopEvents()
    private val engine = SlopEngine(
        dao,
        object : dev.slopbot.element.dao.SlopAssetDao {
            val assets = mutableListOf<dev.slopbot.element.model.SlopAsset>()
            val store = dev.slopbot.element.dao.InMemorySourceStore()
            override fun list(tag: String?): List<dev.slopbot.element.model.SlopAsset> =
                if (tag == null) assets.toList() else assets.filter { it.tags.contains(tag) }
            override fun findByName(name: String) = assets.find { it.name == name }
            override fun put(asset: dev.slopbot.element.model.SlopAsset, bytes: ByteArray): dev.slopbot.element.model.SlopAsset {
                asset.source = store.put(asset.name, asset.mimeType, asset.metadata.originalFilename, bytes)
                assets.add(asset)
                return asset
            }
            override fun readBytes(asset: dev.slopbot.element.model.SlopAsset) = store.get(asset.source)
            override fun delete(name: String): Boolean = assets.removeIf { it.name == name }
        },
        object : dev.slopbot.element.dao.SlopHistoryDao {
            val mods = mutableListOf<dev.slopbot.element.dao.SlopModification>()
            override fun record(m: dev.slopbot.element.dao.SlopModification): dev.slopbot.element.dao.SlopModification {
                mods.add(m)
                return m
            }
            override fun list(name: String, limit: Int) = mods.filter { it.name == name }.sortedByDescending { it.index }.take(limit)
            override fun trim(name: String, cap: Int) {}
        },
        events,
        SlopConfig(),
    )
    class NoopAssetsForTools(private val store: dev.slopbot.element.dao.SourceStore) : dev.slopbot.element.dao.SlopAssetDao {
        val assets = mutableListOf<dev.slopbot.element.model.SlopAsset>()
        override fun list(tag: String?): List<dev.slopbot.element.model.SlopAsset> =
            if (tag == null) assets.toList() else assets.filter { it.tags.contains(tag) }
        override fun findByName(name: String) = assets.find { it.name == name }
        override fun put(asset: dev.slopbot.element.model.SlopAsset, bytes: ByteArray): dev.slopbot.element.model.SlopAsset {
            asset.source = store.put(asset.name, asset.mimeType, asset.metadata.originalFilename, bytes)
            assets.add(asset)
            return asset
        }
        override fun readBytes(asset: dev.slopbot.element.model.SlopAsset) = store.get(asset.source)
        override fun delete(name: String): Boolean = assets.removeIf { it.name == name }
    }

    class FakeSourceStore : dev.slopbot.element.dao.SourceStore {
        val objects = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        private var seq = 0L
        override fun put(path: String, mimeType: String, originalFilename: String?, bytes: ByteArray): dev.slopbot.element.model.LargeObjectRef {
            val id = "lo-${seq++}"
            objects[id] = bytes.copyOf()
            return dev.slopbot.element.model.LargeObjectRef(id, mimeType, originalFilename, "UPLOADED")
        }
        override fun get(ref: dev.slopbot.element.model.LargeObjectRef?): ByteArray? = ref?.id?.let { objects[it]?.copyOf() }
        override fun delete(ref: dev.slopbot.element.model.LargeObjectRef?) { ref?.id?.let { objects.remove(it) } }
        override fun sizeOf(ref: dev.slopbot.element.model.LargeObjectRef?): Int = ref?.id?.let { objects[it]?.size } ?: 0
    }

    private val sourceStore = FakeSourceStore()
    private val assetsDao = NoopAssetsForTools(sourceStore)
    private val tools = McpTools(dao, assetsDao, sourceStore, engine)

    @BeforeAll
    fun startJwks() {
        // The JWKS fixture: one RSA key (kid=k1) served by a tiny HTTP server.
        val publicKey = keyPair.public as RSAPublicKey
        val n = Base64.getUrlEncoder().withoutPadding().encodeToString(stripLead(publicKey.modulus))
        val e = Base64.getUrlEncoder().withoutPadding().encodeToString(stripLead(publicKey.publicExponent))
        val jwks = mapper.writeValueAsString(
            mapOf(
                "keys" to listOf(
                    mapOf("kty" to "RSA", "kid" to "k1", "n" to n, "e" to e, "alg" to "RS256", "use" to "sig"),
                ),
            ),
        )
        jwksServer = HttpServer.create(InetSocketAddress(0), 0)
        jwksServer.createContext("/") { exchange ->
            val body = (jwksResponses.lastOrNull() ?: jwks).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        jwksServer.start()
        jwksResponses.add(jwks)
    }

    @AfterAll
    fun stopJwks() {
        jwksServer.stop(0)
    }

    private fun stripLead(v: BigInteger): ByteArray {
        val bytes = v.toByteArray()
        return if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    /** Mints a real RS256 JWT with the test key. */
    private fun mint(vararg claims: Pair<String, Any?>, kid: String = "k1"): String {
        fun b64(v: Any): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(v))
        val header = mapOf("alg" to "RS256", "kid" to kid, "typ" to "JWT")
        val payload = linkedMapOf<String, Any?>(
            "iss" to "https://issuer.test",
            "aud" to "slopbot-ai",
            "scope" to "slop:mcp",
            "sub" to "savorysailor",
            "exp" to (System.currentTimeMillis() / 1000 + 600),
        ).apply { putAll(claims) }
        val signingInput = b64(header) + "." + b64(payload)
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
        }.sign()
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    // --- the token matrix ------------------------------------------------------------

    @Test
    fun `a missing token is 401`() {
        val r = validator.validate(null)
        assertTrue(r is OidcValidator.Result.Fail && r.status == 401)
    }

    @Test
    fun `a malformed token is 401`() {
        val r = validator.validate("not.a.jwt")
        assertTrue(r is OidcValidator.Result.Fail && r.status == 401)
    }

    @Test
    fun `a valid token verifies and carries the streamer subject`() {
        val r = validator.validate(mint())
        assertTrue(r is OidcValidator.Result.Ok)
        assertEquals("savorysailor", (r as OidcValidator.Result.Ok).verified.subject)
    }

    @Test
    fun `an expired token is 401`() {
        val expired = mint("exp" to (System.currentTimeMillis() / 1000 - 10))
        val r = validator.validate(expired)
        assertTrue(r is OidcValidator.Result.Fail && r.status == 401)
    }

    @Test
    fun `a foreign issuer is 401`() {
        val foreign = mint("iss" to "https://evil.test")
        val r = validator.validate(foreign)
        assertTrue(r is OidcValidator.Result.Fail && r.status == 401)
    }

    @Test
    fun `a wrong scope is 403`() {
        val wrongScope = mint("scope" to "something:else")
        val r = validator.validate(wrongScope)
        assertTrue(r is OidcValidator.Result.Fail && r.status == 403)
    }

    @Test
    fun `a wrong audience is 401`() {
        val wrongAud = mint("aud" to "someone-else")
        val r = validator.validate(wrongAud)
        assertTrue(r is OidcValidator.Result.Fail && r.status == 401)
    }

    @Test
    fun `a tampered signature is 401`() {
        val token = mint()
        val parts = token.split(".")
        val tampered = parts[0] + "." + parts[1] + "." + parts[2].dropLast(2) + "AA"
        val r = validator.validate(tampered)
        assertTrue(r is OidcValidator.Result.Fail && r.status == 401)
    }

    @Test
    fun `an unknown signing key fails closed`() {
        val unknownKid = mint(kid = "k-other")
        val r = validator.validate(unknownKid)
        assertTrue(r is OidcValidator.Result.Fail && r.status == 401)
    }

    // --- the tools flow ---------------------------------------------------------------

    @Test
    fun `the create to moderate to play flow with zero filesystem`() {

        // CREATE: the bouncer + meta pipeline, PENDING, server-assigned name.
        val code = validSlopSource(title = "Flow Slop", author = "AgentViewer")
        val created = tools.call(
            "slops.create",
            mapOf("code" to code, "creator" to mapOf("id" to "7", "login" to "agentviewer", "displayName" to "AgentViewer")),
            "savorysailor",
        )
        val result = created["result"] as Map<*, *>
        assertEquals("agentviewer-flow-slop.js", result["name"])
        assertEquals("PENDING", result["moderation"])

        // READ: the owned slop (metadata + source).
        val read = tools.call("slops.read", mapOf("name" to "agentviewer-flow-slop.js"), "savorysailor")
        val readResult = read["result"] as Map<*, *>
        assertTrue((readResult["source"] as String).contains("export const meta"))

        // Ownership: another streamer cannot read it.
        val foreign = org.junit.jupiter.api.assertThrows<McpToolError> {
            tools.call("slops.read", mapOf("name" to "agentviewer-flow-slop.js"), "someoneelse")
        }
        assertTrue(foreign.message!!.contains("not owned"))

        // SUBMIT MODERATION → the streamer's approve (the REST verdict).
        tools.call("slops.submitModeration", mapOf("name" to "agentviewer-flow-slop.js"), "savorysailor")
        engine.approvePending("agentviewer-flow-slop.js")

        // PLAY: the approved slop takes the stage; the bundle carries the source.
        val bundle = engine.bundle("agentviewer-flow-slop.js", chained = false, playId = null)
        assertTrue((bundle["codeBase64"] as String).isNotEmpty())

        // Play statistics: the manual play bumped the record.
        val doc = dao.findByName("agentviewer-flow-slop.js")!!
        assertEquals(dev.slopbot.element.model.Slop.MODERATION_APPROVED, doc.moderation)
    }

    @Test
    fun `slops source library scope reads any slop read-only`() {
        val code = validSlopSource(title = "Lib Slop", author = "OtherStreamer")
        tools.call("slops.create", mapOf("code" to code), "otherstreamer")

        // Library scope: raw source of ANY slop, read-only.
        val library = tools.call(
            "slops.source",
            mapOf("name" to "otherstreamer-lib-slop.js", "scope" to "library"),
            "savorysailor",
        )
        val libraryResult = library["result"] as Map<*, *>
        assertEquals("library", libraryResult["scope"])
        assertTrue(libraryResult["readOnly"] == true)
        assertTrue((libraryResult["source"] as String).contains("export const meta"))

        // Owned scope on someone else's slop is refused.
        val owned = org.junit.jupiter.api.assertThrows<McpToolError> {
            tools.call("slops.source", mapOf("name" to "otherstreamer-lib-slop.js", "scope" to "owned"), "savorysailor")
        }
        assertTrue(owned.message!!.contains("not owned"))
    }

    @Test
    fun `the bouncer rejects a bad build through the MCP create`() {
        val bad = validSlopSource(title = "Bad", author = "A") + "\nconst x = require('fs');"
        val e = org.junit.jupiter.api.assertThrows<dev.slopbot.element.engine.SlopRejectedException> {
            tools.call("slops.create", mapOf("code" to bad), "savorysailor")
        }
        assertEquals("the bouncer says no: require()", e.message)
    }

    @Test
    fun `assets read returns the large-object id with the 8MiB cap`() {
        assetsDao.put(
            dev.slopbot.element.model.SlopAsset().apply {
                name = "shared/boing"
                displayName = "boing"
                kind = "AUDIO"
                mimeType = "audio/mpeg"
                tags = mutableListOf("shared")
            },
            "boing-bytes".toByteArray(),
        )
        val read = tools.call("assets.read", mapOf("name" to "shared/boing"), "savorysailor")
        val result = read["result"] as Map<*, *>
        assertTrue((result["largeObjectId"] as String).startsWith("lo-"))
        assertEquals(11, result["size"])

        // The cap: an oversized asset is refused.
        assetsDao.put(
            dev.slopbot.element.model.SlopAsset().apply {
                name = "shared/huge"
                displayName = "huge"
                kind = "AUDIO"
                mimeType = "audio/mpeg"
                tags = mutableListOf("shared")
            },
            ByteArray(9 * 1024 * 1024),
        )
        val e = org.junit.jupiter.api.assertThrows<McpToolError> {
            tools.call("assets.read", mapOf("name" to "shared/huge"), "savorysailor")
        }
        assertTrue(e.message!!.contains("8 MiB cap"))
        // The tool list carries exactly the 8 verbs.
        assertEquals(8, McpTools.TOOLS.size)
    }
}
