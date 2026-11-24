package dev.slopbot.element.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The metaOf tests — extraction, controls (template-literal + single-line),
 * triggers strict-JSON with trailing-comma tolerance, malformed → dropped,
 * the 12-entry cap. Every case asserts the reference behavior (`lib/slop.js`
 * `metaOf`) exactly. Note: the scalar regexes are line-anchored (`^\s*<key>`
 * with the multiline flag) — each meta key must sit at a line start, exactly
 * as in the reference.
 */
class SlopMetaParserTest {

    @Test
    fun `scalars extract with the single-quoted fallbacks`() {
        val code = """
            export const meta = {
                title: 'Tomato Tribunal',
                author: 'savorysailor',
                prompt: 'make tomatoes fly',
                createdAt: '2026-01-01',
                moderationLevel: 'teen',
            };
            export function init(ctx) {}
        """.trimIndent()
        val meta = SlopMetaParser.metaOf(code)
        assertEquals("Tomato Tribunal", meta.title)
        assertEquals("savorysailor", meta.author)
        assertEquals("make tomatoes fly", meta.prompt)
        assertEquals("2026-01-01", meta.createdAt)
        assertEquals("teen", meta.moderationLevel)
    }

    @Test
    fun `missing title and author get the fallbacks`() {
        val meta = SlopMetaParser.metaOf("export const meta = {};")
        assertEquals("Untitled slop", meta.title)
        assertEquals("unknown", meta.author)
        assertNull(meta.prompt)
        assertNull(meta.createdAt)
        assertNull(meta.moderationLevel)
        assertNull(meta.controls)
        assertNull(meta.triggers)
    }

    @Test
    fun `double-quoted and backtick scalars also extract`() {
        val code = """
            export const meta = {
                title: "Double",
                author: `Backticked`,
            };
        """.trimIndent()
        val meta = SlopMetaParser.metaOf(code)
        assertEquals("Double", meta.title)
        assertEquals("Backticked", meta.author)
    }

    @Test
    fun `the first scalar match wins`() {
        val code = """
            export const meta = {
                title: 'First',
            };
            // title: 'Second'
        """.trimIndent()
        assertEquals("First", SlopMetaParser.metaOf(code).title)
    }

    // --- controls ----------------------------------------------------------------

    @Test
    fun `controls from a backtick template literal keeps newlines`() {
        val code = """
            export const meta = {
                title: 'T',
                controls: `Squeeze to cheer
            !boom - explodes the tomatoes
            !reset - clears the stage`,
            };
        """.trimIndent()
        val meta = SlopMetaParser.metaOf(code)
        assertEquals(
            "Squeeze to cheer\n!boom - explodes the tomatoes\n!reset - clears the stage",
            meta.controls,
        )
    }

    @Test
    fun `an escaped backtick terminates the template literal like the reference`() {
        // The capture regex is non-greedy and cannot tell an escaped backtick
        // from the terminator — the match stops at the FIRST backtick, so the
        // content ends with the escape's backslash ("a\\"). (Reference
        // behavior: escaped backticks truncate the controls block.)
        val code = """
            export const meta = {
                controls: `a\`b`,
            };
        """.trimIndent()
        val meta = SlopMetaParser.metaOf(code)
        assertEquals("a\\", meta.controls)
    }

    @Test
    fun `controls from a single line unescape literal newline escapes`() {
        val code = """
            export const meta = {
                controls: 'line one\nline two',
            };
        """.trimIndent()
        val meta = SlopMetaParser.metaOf(code)
        assertEquals("line one\nline two", meta.controls)
    }

    @Test
    fun `a whitespace-only controls becomes null`() {
        val code = """
            export const meta = {
                controls: `   `,
            };
        """.trimIndent()
        assertNull(SlopMetaParser.metaOf(code).controls)
    }

    @Test
    fun `the template literal wins over a single-line controls`() {
        val code = """
            export const meta = {
                controls: 'single',
            };
            // controls: `block`
        """.trimIndent()
        // The block regex requires the backtick form INLINE at the key — the
        // commented form never matches; the single-line form wins.
        assertEquals("single", SlopMetaParser.metaOf(code).controls)
    }

    // --- triggers ----------------------------------------------------------------

    @Test
    fun `triggers parse as strict JSON`() {
        val code = """
            export const meta = {
                triggers: [{"type":"chat","mode":"word","patterns":["!boom"]}],
            };
        """.trimIndent()
        val triggers = SlopMetaParser.metaOf(code).triggers
        assertTrue(triggers is List<*>)
        assertEquals(1, (triggers as List<*>).size)
        @Suppress("UNCHECKED_CAST")
        val first = triggers[0] as Map<String, Any?>
        assertEquals("chat", first["type"])
        assertEquals("word", first["mode"])
    }

    @Test
    fun `triggers tolerate trailing commas`() {
        val code = """
            export const meta = {
                triggers: [
                    {"type":"chat","mode":"exact","patterns":["boo"]},
                    {"type":"raid","minViewers":10},   
                ],
            };
        """.trimIndent()
        val triggers = SlopMetaParser.metaOf(code).triggers
        assertEquals(2, (triggers as List<*>).size)
    }

    @Test
    fun `triggers tolerate trailing commas in nested objects`() {
        val code = """
            export const meta = {
                triggers: [{"type":"chat","patterns":["x"],},],
            };
        """.trimIndent()
        val triggers = SlopMetaParser.metaOf(code).triggers
        assertEquals(1, (triggers as List<*>).size)
    }

    @Test
    fun `malformed triggers are dropped entirely`() {
        val code = """
            export const meta = {
                triggers: [{"type": chat}],
            };
        """.trimIndent() // unquoted value
        assertNull(SlopMetaParser.metaOf(code).triggers)
    }

    @Test
    fun `triggers that are not an array are dropped`() {
        val code = """
            export const meta = {
                triggers: {"type":"chat"},
            };
        """.trimIndent()
        assertNull(SlopMetaParser.metaOf(code).triggers)
    }

    @Test
    fun `triggers is capped at 12 entries`() {
        val entries = (1..20).joinToString(",") { """{"type":"chat","patterns":["p$it"]}""" }
        val code = """
            export const meta = {
                triggers: [$entries],
            };
        """.trimIndent()
        val triggers = SlopMetaParser.metaOf(code).triggers as List<*>
        assertEquals(12, triggers.size)
    }

    @Test
    fun `a closing brace inside a string breaks the bracket match like the reference`() {
        // The bracket matcher counts [ ] { } textually — including inside
        // strings. The string's '}' decrements the depth, the scan ends at the
        // object's closing brace (before the outer array's), and the slice is
        // malformed JSON → the whole proposal is dropped. (Reference behavior:
        // the matcher is textual, not tokenizing.)
        val code = """
            export const meta = {
                triggers: [{"patterns":["a}b"]}],
            };
        """.trimIndent()
        assertNull(SlopMetaParser.metaOf(code).triggers)
    }

    @Test
    fun `a string brace imbalance drops the triggers like the reference`() {
        // Textual bracket matching: the string's '}' decrements the depth, so
        // the scan ends early, the slice is malformed JSON, and the whole
        // proposal is dropped.
        val code = """
            export const meta = {
                triggers: [{"patterns":["}] }"]],
            };
        """.trimIndent()
        assertNull(SlopMetaParser.metaOf(code).triggers)
    }

    @Test
    fun `a non-array character after the bracket stops the scan`() {
        // JS: depth === 0 && !/\s/.test(ch) → break — a stray token ends the scan.
        val code = "export const meta = { triggers: : [not-parsed] };"
        assertNull(SlopMetaParser.metaOf(code).triggers)
    }
}
