package dev.slopbot.element.engine

import dev.slopbot.element.dao.SlopAssetDao
import dev.slopbot.element.dao.SlopHistoryDao
import dev.slopbot.element.model.Participant
import dev.slopbot.element.service.SlopEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase D's end-to-end loop, at the engine level (the same calls the MCP
 * verbs and the REST twins make): generate → the build brief carries the
 * moderation contract → the agent "generates" (createSlop, the bouncer
 * gate) → the draft lands PENDING → the streamer's verdict moves it.
 */
class ModerationLoopE2ETest {

    private val dao = InMemorySlopDao()
    private val engine = SlopEngine(dao, NoopAssetDao(), NoopHistoryDao(), SlopEvents(), SlopConfig())

    private class NoopAssetDao : SlopAssetDao {
        override fun list(tag: String?) = emptyList<dev.slopbot.element.model.SlopAsset>()
        override fun findByName(name: String) = null
        override fun findById(id: String) = null
        override fun put(asset: dev.slopbot.element.model.SlopAsset, bytes: ByteArray) = asset
        override fun replaceBytes(name: String, bytes: ByteArray) = null
        override fun readBytes(asset: dev.slopbot.element.model.SlopAsset): ByteArray? = null
        override fun setState(name: String, state: String) = null
        override fun delete(name: String) = false
    }
    private class NoopHistoryDao : SlopHistoryDao {
        override fun record(modification: dev.slopbot.element.dao.SlopModification) = modification
        override fun list(name: String, limit: Int) = emptyList<dev.slopbot.element.dao.SlopModification>()
        override fun trim(name: String, cap: Int) {}
    }

    private val streamer = Participant(login = "savorysailor", displayName = "SavorySailor")

    private fun slopCode(title: String, body: String = "export function frame(ctx, t, dt) {}") = """
        export const meta = {
          title: '$title',
          author: 'chatgremlin',
          moderationLevel: 'teen',
        };
        export function init(ctx) {}
        $body
    """.trimIndent() + "\n"

    @Test
    fun `the full build loop — generate, brief, create, verdict`() {
        // 1. the streamer submits a prompt with per-build overrides
        assertTrue(engine.generate(
            text = "a raccoon DJ drops a beat when someone follows",
            user = streamer,
            overrides = mapOf("moderationLevel" to "teen", "maxSeconds" to 45),
        ))

        // 2. the agent takes the brief: the moderation contract rides along
        val brief = engine.generationBrief()
        assertNotNull(brief)
        assertEquals("a raccoon DJ drops a beat when someone follows", brief!!["prompt"])
        val mod = brief["moderation"] as Map<*, *>
        assertEquals("teen", mod["level"])
        assertTrue((mod["rules"] as String).contains("crude humor"), "the tier rules are verbatim")
        assertEquals(45, brief["maxSeconds"])

        // 3. the agent "generates" and creates — the bouncer gate runs
        val name = engine.createSlop(code = slopCode("Raccoon DJ"), creator = streamer, streamerLogin = "savorysailor")
        assertTrue(name.contains("raccoon-dj"))

        // the build's completion frees the generation slot and advances the queue
        assertNull(engine.generationBrief())

        // 4. the draft sits PENDING in the registry (the moderation queue)
        val entry = engine.registryEntry(name)!!
        assertTrue(entry.pending)

        // 5. the streamer's verdict: reject keeps it out of plays, approve frees it
        assertTrue(engine.rejectPending(name))
        val rejected = engine.registryEntry(name)
        // rejected docs leave the registry entirely
        assertNull(rejected)
    }

    @Test
    fun `the bouncer rejects a denylisted source before it ever reaches the queue`() {
        val bad = slopCode("Evil", body = "export function frame() { const x = eval('1+1'); }")
        val e = org.junit.jupiter.api.assertThrows<SlopRejectedException> {
            engine.createSlop(code = bad, creator = streamer, streamerLogin = "savorysailor")
        }
        assertTrue(e.message!!.contains("the bouncer says no"))
        // the generation slot is untouched by the rejected build (the caller frees it via builds.fail)
    }

    @Test
    fun `a failed build frees the slot and reports the reason`() {
        engine.generate(text = "the prompt that will fail", user = streamer, overrides = null)
        assertNotNull(engine.generationBrief())
        assertTrue(engine.failGeneration("the agent ran out of ideas"))
        assertNull(engine.generationBrief())
        // the queue advanced — a fresh brief would serve the NEXT build
    }

    @Test
    fun `per-build overrides ride the brief — a kid-tier build gets kid rules`() {
        engine.config.configure(mapOf("customRules" to "no alcohol references"))
        engine.generate(text = "a wholesome prompt", user = streamer, overrides = mapOf("moderationLevel" to "kid"))
        val brief = engine.generationBrief()!!
        val mod = brief["moderation"] as Map<*, *>
        assertEquals("kid", mod["level"])
        assertTrue((mod["rules"] as String).contains("child-safe"))
        assertEquals("no alcohol references", mod["additionalInstructions"])
        engine.failGeneration("done testing")
    }
}
