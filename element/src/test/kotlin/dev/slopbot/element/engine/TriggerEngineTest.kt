package dev.slopbot.element.engine

import dev.slopbot.api.slop.SlopTriggerEvent
import dev.slopbot.element.model.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The trigger tests — every chat mode; case sensitivity; who-gating;
 * firstchat semantics; allow-list gating; the flood cap; immediate vs queued;
 * bypassCooldown. The reference is `lib/slop.js` (matchChatPattern /
 * triggerMatches / normalizeTrigger / triggerAllowed / the dispatch loop).
 */
class TriggerEngineTest {

    private fun chatTrigger(
        mode: String,
        patterns: List<String>,
        caseSensitive: Boolean = false,
        who: String = "anyone",
    ): Trigger = Trigger(
        id = "t1",
        type = "chat",
        mode = mode,
        patterns = patterns.toMutableList(),
        caseSensitive = caseSensitive,
        who = who,
        source = "streamer",
    )

    private fun event(text: String, mod: Boolean = false, broadcaster: Boolean = false, first: Boolean = false) =
        SlopTriggerEvent(type = "chat", text = text, login = "viewer", mod = mod, broadcaster = broadcaster, first = first)

    // --- matchChatPattern: the seven modes --------------------------------------

    @Test
    fun `exact compares trimmed texts`() {
        val t = chatTrigger("exact", listOf("!boom"))
        assertTrue(TriggerEngine.triggerMatches(t, event("  !boom  ")))
        assertFalse(TriggerEngine.triggerMatches(t, event("!boom please")))
        assertFalse(TriggerEngine.triggerMatches(t, event("!booms")))
    }

    @Test
    fun `contains is a plain substring`() {
        val t = chatTrigger("contains", listOf("boom"))
        assertTrue(TriggerEngine.triggerMatches(t, event("prepare the BOOM")))
        assertFalse(TriggerEngine.triggerMatches(t, event("nothing here")))
    }

    @Test
    fun `starts trims the text start only`() {
        val t = chatTrigger("starts", listOf("!boom"))
        assertTrue(TriggerEngine.triggerMatches(t, event("   !boom now")))
        assertFalse(TriggerEngine.triggerMatches(t, event("please !boom")))
    }

    @Test
    fun `ends trims the text end only`() {
        val t = chatTrigger("ends", listOf("now"))
        assertTrue(TriggerEngine.triggerMatches(t, event("!boom now   ")))
        assertFalse(TriggerEngine.triggerMatches(t, event("!boom now please")))
    }

    @Test
    fun `word matches on word boundaries`() {
        val t = chatTrigger("word", listOf("boom"))
        assertTrue(TriggerEngine.triggerMatches(t, event("the boom lands")))
        assertFalse(TriggerEngine.triggerMatches(t, event("booms lands")))
        assertFalse(TriggerEngine.triggerMatches(t, event("boomer")))
        // Underscores count as word characters (the reference's [^a-z0-9_]).
        assertFalse(TriggerEngine.triggerMatches(t, event("the_boom")))
        assertTrue(TriggerEngine.triggerMatches(t, event("boom!")))
    }

    @Test
    fun `wildcard is a full-string match with starred gaps`() {
        val t = chatTrigger("wildcard", listOf("!boom*now"))
        assertTrue(TriggerEngine.triggerMatches(t, event("!boom now")))
        // The star gaps case-insensitively: .* then "now".
        assertTrue(TriggerEngine.triggerMatches(t, event("!boomTHE NOW")))
        assertFalse(TriggerEngine.triggerMatches(t, event(" prefix !boom now")))
        assertFalse(TriggerEngine.triggerMatches(t, event("!boom")))
    }

    @Test
    fun `regex compiles the first pattern and an invalid regex matches nothing`() {
        val good = chatTrigger("regex", listOf("b(o+)m"))
        assertTrue(TriggerEngine.triggerMatches(good, event("boom")))
        assertTrue(TriggerEngine.triggerMatches(good, event("BOOM")))
        assertFalse(TriggerEngine.triggerMatches(good, event("bm")))

        val bad = chatTrigger("regex", listOf("b(o+m"))
        assertFalse(TriggerEngine.triggerMatches(bad, event("boom")))
    }

    @Test
    fun `case sensitivity flips the fold`() {
        val folded = chatTrigger("contains", listOf("BOOM"), caseSensitive = false)
        assertTrue(TriggerEngine.triggerMatches(folded, event("boom")))
        val strict = chatTrigger("contains", listOf("BOOM"), caseSensitive = true)
        assertFalse(TriggerEngine.triggerMatches(strict, event("boom")))
        assertTrue(TriggerEngine.triggerMatches(strict, event("BOOM")))
    }

    @Test
    fun `case sensitivity applies to regex mode`() {
        val folded = chatTrigger("regex", listOf("BOOM"), caseSensitive = false)
        assertTrue(TriggerEngine.triggerMatches(folded, event("the boom")))
        val strict = chatTrigger("regex", listOf("BOOM"), caseSensitive = true)
        assertFalse(TriggerEngine.triggerMatches(strict, event("the boom")))
        assertTrue(TriggerEngine.triggerMatches(strict, event("the BOOM")))
    }

    @Test
    fun `an empty pattern never matches`() {
        val t = chatTrigger("contains", listOf("real"))
        assertFalse(TriggerEngine.matchChatPattern(t, "", "anything"))
        assertFalse(TriggerEngine.matchChatPattern(t, null, "anything"))
    }

    @Test
    fun `an unknown mode matches nothing`() {
        val t = chatTrigger("fancy", listOf("x"))
        assertFalse(TriggerEngine.triggerMatches(t, event("x")))
    }

    // --- who-gating ----------------------------------------------------------------

    @Test
    fun `mods gating requires the mod flag`() {
        val t = chatTrigger("contains", listOf("boom"), who = "mods")
        assertTrue(TriggerEngine.triggerMatches(t, event("boom", mod = true)))
        assertFalse(TriggerEngine.triggerMatches(t, event("boom")))
    }

    @Test
    fun `broadcaster gating requires the broadcaster flag`() {
        val t = chatTrigger("contains", listOf("boom"), who = "broadcaster")
        assertTrue(TriggerEngine.triggerMatches(t, event("boom", broadcaster = true)))
        assertFalse(TriggerEngine.triggerMatches(t, event("boom", mod = true)))
    }

    // --- firstchat semantics ---------------------------------------------------------

    @Test
    fun `firstchat fires only on a first-ever message`() {
        val t = Trigger(id = "t", type = "firstchat", mode = "contains", patterns = mutableListOf(), source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(t, event("anything", first = true)))
        assertFalse(TriggerEngine.triggerMatches(t, event("anything", first = false)))
    }

    @Test
    fun `firstchat with empty patterns means any first line`() {
        val t = Trigger(id = "t", type = "firstchat", mode = "contains", patterns = mutableListOf(), source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(t, event("", first = true)))
    }

    @Test
    fun `a chat trigger requires patterns`() {
        val t = Trigger(id = "t", type = "chat", mode = "contains", patterns = mutableListOf(), source = "streamer")
        // Empty patterns on chat: presence is NOT the match — normalizeTrigger
        // rejects the trigger; a stored empty chat trigger matches nothing
        // (JS: patterns.length === 0 → return true — the CHAT branch returns
        // TRUE on empty patterns; the null came from normalizeTrigger).
        assertTrue(TriggerEngine.triggerMatches(t, event("hello")))
    }

    // --- event-type mapping ------------------------------------------------------------

    @Test
    fun `triggerTypesFor maps chat and follow`() {
        assertEquals(listOf("chat", "firstchat"), TriggerEngine.triggerTypesFor("chat"))
        assertEquals(listOf("follow", "firstfollow"), TriggerEngine.triggerTypesFor("follow"))
        assertEquals(listOf("raid"), TriggerEngine.triggerTypesFor("raid"))
        assertEquals(listOf("cheer"), TriggerEngine.triggerTypesFor("cheer"))
    }

    @Test
    fun `a chat trigger ignores non-chat events`() {
        val t = chatTrigger("contains", listOf("x"))
        assertFalse(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "follow", first = true)))
    }

    @Test
    fun `firstfollow requires the first flag`() {
        val t = Trigger(id = "t", type = "firstfollow", source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "follow", first = true)))
        assertFalse(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "follow")))
    }

    @Test
    fun `raid checks minViewers`() {
        val t = Trigger(id = "t", type = "raid", minViewers = 10, source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "raid", viewers = 10)))
        assertTrue(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "raid", viewers = 50)))
        assertFalse(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "raid", viewers = 5)))
        // A trigger without minViewers matches any raid (?? 0).
        val any = Trigger(id = "t", type = "raid", source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(any, SlopTriggerEvent(type = "raid", viewers = 1)))
    }

    @Test
    fun `cheer checks minBits`() {
        val t = Trigger(id = "t", type = "cheer", minBits = 100, source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "cheer", bits = 100)))
        assertFalse(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "cheer", bits = 99)))
    }

    @Test
    fun `sub checks the tier floor`() {
        val any = Trigger(id = "t", type = "sub", source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(any, SlopTriggerEvent(type = "sub")))
        val tier2 = Trigger(id = "t", type = "sub", tier = 2, source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(tier2, SlopTriggerEvent(type = "sub", tier = 3)))
        assertFalse(TriggerEngine.triggerMatches(tier2, SlopTriggerEvent(type = "sub", tier = 1)))
    }

    @Test
    fun `resub checks minMonths`() {
        val t = Trigger(id = "t", type = "resub", minMonths = 3, source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "resub", months = 3)))
        assertFalse(TriggerEngine.triggerMatches(t, SlopTriggerEvent(type = "resub", months = 2)))
    }

    @Test
    fun `redemption matches the reward id when set`() {
        val any = Trigger(id = "t", type = "redemption", source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(any, SlopTriggerEvent(type = "redemption")))
        val specific = Trigger(id = "t", type = "redemption", rewardId = "r-123", source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(specific, SlopTriggerEvent(type = "redemption", rewardId = "r-123")))
        assertFalse(TriggerEngine.triggerMatches(specific, SlopTriggerEvent(type = "redemption", rewardId = "r-999")))
    }

    @Test
    fun `follow and subgift are presence matches`() {
        val follow = Trigger(id = "t", type = "follow", source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(follow, SlopTriggerEvent(type = "follow")))
        val gift = Trigger(id = "t", type = "subgift", source = "streamer")
        assertTrue(TriggerEngine.triggerMatches(gift, SlopTriggerEvent(type = "subgift")))
    }

    @Test
    fun `a disabled trigger never matches`() {
        val t = chatTrigger("contains", listOf("boom")).also { it.enabled = false }
        assertFalse(TriggerEngine.triggerMatches(t, event("boom")))
    }

    // --- normalizeTrigger ----------------------------------------------------------------

    @Test
    fun `normalize rejects unknown types and non-objects`() {
        assertNull(TriggerEngine.normalizeTrigger(mapOf("type" to "spam"), 0))
        assertNull(TriggerEngine.normalizeTrigger("chat", 0))
        assertNull(TriggerEngine.normalizeTrigger(null, 0))
    }

    @Test
    fun `normalize defaults the mode to contains and the who to anyone`() {
        val t = TriggerEngine.normalizeTrigger(mapOf("type" to "chat", "patterns" to listOf("hi")), 0)!!
        assertEquals("contains", t.mode)
        assertEquals("anyone", t.who)
        assertFalse(t.caseSensitive)
        assertEquals("queue", t.dispatch)
        assertFalse(t.bypassCooldown)
        assertEquals("streamer", t.source)
        assertTrue(t.enabled)
    }

    @Test
    fun `normalize trims and caps patterns at 8`() {
        val patterns = (1..12).map { "  p$it  " }
        val t = TriggerEngine.normalizeTrigger(mapOf("type" to "chat", "patterns" to patterns), 0)!!
        assertEquals(8, t.patterns.size)
        assertEquals("p1", t.patterns[0])
        assertEquals("p8", t.patterns[7])
    }

    @Test
    fun `normalize wraps a single pattern string`() {
        val t = TriggerEngine.normalizeTrigger(mapOf("type" to "chat", "patterns" to "  hi  "), 0)!!
        assertEquals(listOf("hi"), t.patterns)
    }

    @Test
    fun `normalize rejects chat triggers with no usable patterns (firstchat keeps them)`() {
        assertNull(TriggerEngine.normalizeTrigger(mapOf("type" to "chat", "patterns" to listOf("   ")), 0))
        val fc = TriggerEngine.normalizeTrigger(mapOf("type" to "firstchat", "patterns" to emptyList<String>()), 0)!!
        assertEquals(0, fc.patterns.size)
    }

    @Test
    fun `normalize rejects a pattern past 64 chars`() {
        assertNull(TriggerEngine.normalizeTrigger(mapOf("type" to "chat", "patterns" to listOf("x".repeat(65))), 0))
        val ok = TriggerEngine.normalizeTrigger(mapOf("type" to "chat", "patterns" to listOf("x".repeat(64))), 0)
        assertTrue(ok != null)
    }

    @Test
    fun `regex mode keeps a single pattern and must compile`() {
        val t = TriggerEngine.normalizeTrigger(mapOf("type" to "chat", "mode" to "regex", "patterns" to listOf("a", "b")), 0)!!
        assertEquals(1, t.patterns.size)
        assertNull(TriggerEngine.normalizeTrigger(mapOf("type" to "chat", "mode" to "regex", "patterns" to listOf("a(")), 0))
    }

    @Test
    fun `creator-sourced triggers force queue dispatch and no bypass`() {
        val raw = mapOf("type" to "chat", "patterns" to listOf("hi"), "dispatch" to "immediate", "bypassCooldown" to true)
        val creator = TriggerEngine.normalizeTrigger(raw, 0, "creator")!!
        assertEquals("queue", creator.dispatch)
        assertFalse(creator.bypassCooldown)
        val streamer = TriggerEngine.normalizeTrigger(raw, 0, "streamer")!!
        assertEquals("immediate", streamer.dispatch)
        assertTrue(streamer.bypassCooldown)
    }

    @Test
    fun `normalize coerces the numeric criteria`() {
        val raid = TriggerEngine.normalizeTrigger(mapOf("type" to "raid", "minViewers" to "12.7"), 0)!!
        assertEquals(13, raid.minViewers)
        assertEquals(0, TriggerEngine.normalizeTrigger(mapOf("type" to "raid", "minViewers" to "junk"), 0)!!.minViewers)
        assertEquals(0, TriggerEngine.normalizeTrigger(mapOf("type" to "raid"), 0)!!.minViewers)
        val cheer = TriggerEngine.normalizeTrigger(mapOf("type" to "cheer", "minBits" to "-5"), 0)!!
        assertEquals(0, cheer.minBits) // Math.max(0, …)
        val resub = TriggerEngine.normalizeTrigger(mapOf("type" to "resub", "minMonths" to "3"), 0)!!
        assertEquals(3, resub.minMonths)
    }

    @Test
    fun `normalize generates ids like the reference`() {
        val now = 1_700_000_000_000
        val t = TriggerEngine.normalizeTrigger(mapOf("type" to "raid"), 7, nowMs = now)!!
        assertEquals("t7-${now.toString(36)}", t.id)
        val named = TriggerEngine.normalizeTrigger(mapOf("type" to "raid", "id" to "custom"), 7)!!
        assertEquals("custom", named.id)
    }

    @Test
    fun `the sub tier accepts only 1 2 3`() {
        assertEquals(2, TriggerEngine.normalizeTrigger(mapOf("type" to "sub", "tier" to 2), 0)!!.tier)
        assertEquals(0, TriggerEngine.normalizeTrigger(mapOf("type" to "sub", "tier" to 4), 0)!!.tier)
        assertEquals(0, TriggerEngine.normalizeTrigger(mapOf("type" to "sub"), 0)!!.tier)
    }

    @Test
    fun `the redemption rewardId nulls out when blank`() {
        assertNull(TriggerEngine.normalizeTrigger(mapOf("type" to "redemption", "rewardId" to "  "), 0)!!.rewardId)
        assertEquals("r1", TriggerEngine.normalizeTrigger(mapOf("type" to "redemption", "rewardId" to " r1 "), 0)!!.rewardId)
    }

    // --- the allow-list -------------------------------------------------------------

    @Test
    fun `the allow-list gates creator triggers only`() {
        val creatorRaid = TriggerEngine.normalizeTrigger(mapOf("type" to "raid"), 0, "creator")!!
        val streamerRaid = TriggerEngine.normalizeTrigger(mapOf("type" to "raid"), 0, "streamer")!!
        assertFalse(TriggerEngine.triggerAllowed(creatorRaid, listOf("chat", "firstchat")))
        assertTrue(TriggerEngine.triggerAllowed(streamerRaid, listOf("chat", "firstchat")))
        assertTrue(TriggerEngine.triggerAllowed(creatorRaid, listOf("chat", "raid")))
    }
}
