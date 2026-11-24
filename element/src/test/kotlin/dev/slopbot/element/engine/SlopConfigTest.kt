package dev.slopbot.element.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The config tests: `configure()` merge semantics (never clobbers newer keys),
 * BOM-tolerant parses, and the per-key validation ported from `lib/slop.js`.
 */
class SlopConfigTest {

    @Test
    fun `defaults match the reference`() {
        val c = SlopConfig()
        val s = c.snapshot()
        assertEquals("Make It Slop", s["redeemTitle"])
        assertEquals(true, s["announce"])
        assertEquals(60, s["maxSlopSeconds"])
        assertEquals(10, s["maxQueueDepth"])
        assertEquals("teen", s["moderationLevel"])
        assertEquals(6, s["triggerDispatchesPerMinute"])
        assertEquals(listOf("chat", "firstchat"), c.allowedTriggers())
        assertNull(s["streamerCam"])
    }

    @Test
    fun `a patch applies its keys and echoes them back`() {
        val c = SlopConfig()
        val r = c.configure(mapOf("redeemTitle" to "Make It Slop Now", "maxSlopSeconds" to 30))
        assertTrue(r.ok)
        assertEquals("Make It Slop Now", r.applied["redeemTitle"])
        assertEquals(30, r.applied["maxSlopSeconds"])
        assertEquals(30, c.snapshot()["maxSlopSeconds"])
    }

    @Test
    fun `an empty patch is nothing to change`() {
        val r = SlopConfig().configure(emptyMap())
        assertFalse(r.ok)
        assertEquals("nothing to change", r.error)
    }

    @Test
    fun `numeric validation falls back to defaults only for unparseable values`() {
        val c = SlopConfig()
        // JS: Math.max(1, Math.round(Number(v))) || DEFAULT — 0 becomes 1, not the default.
        assertEquals(1, c.configure(mapOf("maxSlopSeconds" to 0)).applied["maxSlopSeconds"])
        assertEquals(1, c.configure(mapOf("maxQueueDepth" to "0.4")).applied["maxQueueDepth"])
        assertEquals(60, c.configure(mapOf("maxSlopSeconds" to "junk")).applied["maxSlopSeconds"])
        // The per-minute cap: 1..30.
        assertEquals(30, c.configure(mapOf("triggerDispatchesPerMinute" to 99)).applied["triggerDispatchesPerMinute"])
        assertEquals(1, c.configure(mapOf("triggerDispatchesPerMinute" to 0)).applied["triggerDispatchesPerMinute"])
        assertEquals(6, c.configure(mapOf("triggerDispatchesPerMinute" to "junk")).applied["triggerDispatchesPerMinute"])
    }

    @Test
    fun `the allowedTriggers list accepts comma strings and dedupes`() {
        val c = SlopConfig()
        val r = c.configure(mapOf("allowedTriggers" to "chat, raid, chat, junk"))
        assertEquals(listOf("chat", "raid"), r.applied["allowedTriggers"])
        // An empty list would mute everything — skipped.
        val r2 = c.configure(mapOf("allowedTriggers" to "junk,junk2"))
        assertFalse(r2.ok)
    }

    @Test
    fun `the streamer cam clamps and rejects degenerate rects and nulls`() {
        val c = SlopConfig()
        // A null is a NO-OP (the saved placement stands).
        assertFalse(c.configure(mapOf("streamerCam" to null)).ok)
        // A sane rect applies, clamped to 0..1.
        val r = c.configure(mapOf("streamerCam" to mapOf("x" to 0.1, "y" to 0.2, "w" to 5.0, "h" to 0.4)))
        val cam = r.applied["streamerCam"] as Map<*, *>
        assertEquals(0.1, cam["x"])
        assertEquals(1 - 0.1, cam["w"]) // w clamps to 1 - x
        // Degenerate → skipped.
        assertFalse(c.configure(mapOf("streamerCam" to mapOf("x" to 0.99, "y" to 0.0, "w" to 0.001, "h" to 0.4))).ok)
    }

    @Test
    fun `an unknown moderation tier keeps the saved one`() {
        val c = SlopConfig()
        val r = c.configure(mapOf("moderationLevel" to "mystery"))
        assertFalse(r.ok)
        assertEquals("teen", c.snapshot()["moderationLevel"])
        assertTrue(c.configure(mapOf("moderationLevel" to "adult")).ok)
        assertEquals("adult", c.snapshot()["moderationLevel"])
        assertEquals("adult", c.currentTier())
    }

    @Test
    fun `custom rules and agent instructions clear back to empty strings`() {
        val c = SlopConfig()
        assertTrue(c.configure(mapOf("customRules" to "no goats")).ok)
        assertEquals("no goats", c.snapshot()["customRules"])
        val r = c.configure(mapOf("customRules" to ""))
        assertTrue(r.ok)
        assertEquals("", r.applied["customRules"])
        // But other string keys skip blank values entirely.
        assertFalse(c.configure(mapOf("redeemTitle" to "   ")).ok)
    }

    @Test
    fun `the superusers list lowercases and dedupes`() {
        val r = SlopConfig().configure(mapOf("superusers" to "Root, ROOT, admin"))
        assertEquals(listOf("root", "admin"), r.applied["superusers"])
    }

    @Test
    fun `the BOM-tolerant parse strips a leading FEFF`() {
        val c = SlopConfig()
        val parsed = c.parseJsonTolerant("﻿{\"redeemTitle\": \"BOM Sloppy\"}")
        assertEquals("BOM Sloppy", parsed!!["redeemTitle"])
        assertNull(c.parseJsonTolerant("not json"))
        assertNull(c.parseJsonTolerant(null))
    }

    @Test
    fun `configure merges without clobbering newer keys`() {
        // The merge-never-clobber heritage: a patch only touches its own keys —
        // keys an older process doesn't know about survive on the state.
        val c = SlopConfig()
        assertTrue(c.configure(mapOf("model" to "provider/model")).ok)
        assertTrue(c.configure(mapOf("announce" to false)).ok)
        val s = c.snapshot()
        assertEquals("provider/model", s["model"])
        assertEquals(false, s["announce"])
        assertEquals("Make It Slop", s["redeemTitle"]) // untouched
    }
}
