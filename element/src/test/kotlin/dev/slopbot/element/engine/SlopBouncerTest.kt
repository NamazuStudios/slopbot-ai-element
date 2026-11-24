package dev.slopbot.element.engine

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bouncer port tests — every denylist pattern fires; the 250 KB boundary;
 * the meta/init/frame requirements. Expected messages are the reference's
 * (`lib/slop.js` inspectSlopCode), byte-for-byte.
 */
class SlopBouncerTest {

    private fun slop(extra: String = ""): String = """
        export const meta = { title: 'T', author: 'a' };
        export function init(ctx) { return; }
        export function frame(ctx, dt) { return; }
        $extra
    """.trimIndent()

    @Test
    fun `a well-formed slop passes`() {
        SlopBouncer.inspect(slop())
    }

    @Test
    fun `null code hits the size cap message`() {
        // JS: typeof code !== 'string' → the same "exceeds the cap" error.
        val e = assertThrows(SlopRejectedException::class.java) { SlopBouncer.inspect(null) }
        assertTrue(e.message!!.endsWith(" KB cap"), e.message)
    }

    @Test
    fun `the 250KB boundary is exact`() {
        // code.length > 250*1024 rejects; == passes (UTF-16 units, like the JS).
        val exact = slop() + " ".repeat((SlopBouncer.MAX_SLOP_CODE_BYTES - slop().length).coerceAtLeast(0))
        if (exact.length == SlopBouncer.MAX_SLOP_CODE_BYTES) {
            SlopBouncer.inspect(exact)
        }
        val oversized = "x".repeat(SlopBouncer.MAX_SLOP_CODE_BYTES + 1)
        val e = assertThrows(SlopRejectedException::class.java) { SlopBouncer.inspect(oversized) }
        // JS: `slop code exceeds the ${Math.round(MAX_SLOP_CODE_BYTES / 1024)} KB cap` → 250
        org.junit.jupiter.api.Assertions.assertEquals("slop code exceeds the 250 KB cap", e.message)
        org.junit.jupiter.api.Assertions.assertEquals(256000, SlopBouncer.MAX_SLOP_CODE_BYTES)
        org.junit.jupiter.api.Assertions.assertEquals(250, SlopBouncer.MAX_SLOP_CODE_KB)
    }

    @Test
    fun `no meta block is rejected`() {
        val code = "export function init(ctx) {}"
        val e = assertThrows(SlopRejectedException::class.java) { SlopBouncer.inspect(code) }
        org.junit.jupiter.api.Assertions.assertEquals("no meta block", e.message)
    }

    @Test
    fun `meta without init or frame is rejected`() {
        val code = "export const meta = {}; export function other() {}"
        val e = assertThrows(SlopRejectedException::class.java) { SlopBouncer.inspect(code) }
        org.junit.jupiter.api.Assertions.assertEquals("no init/frame exports", e.message)
    }

    @Test
    fun `meta plus frame (no init) passes`() {
        SlopBouncer.inspect("export const meta = {}\nexport function frame(c, dt) {}")
    }

    // --- the denylist — every pattern fires, with the original label -------------

    private fun denylistCase(snippet: String, label: String) {
        val e = assertThrows(SlopRejectedException::class.java) { SlopBouncer.inspect(slop(snippet)) }
        org.junit.jupiter.api.Assertions.assertEquals("the bouncer says no: $label", e.message)
    }

    @Test
    fun `require() is rejected`() = denylistCase("const fs = require('fs');", "require()")

    @Test
    fun `require without space is rejected`() = denylistCase("const x = require('fs');", "require()")

    @Test
    fun `node single-quote import is rejected`() = denylistCase("import x from 'node:fs';", "node: import")

    @Test
    fun `node double-quote import is rejected`() = denylistCase("""import x from "node:path";""", "node: import")

    @Test
    fun `fetch() is rejected`() = denylistCase("fetch('http://x');", "fetch()")

    @Test
    fun `XMLHttpRequest is rejected`() = denylistCase("const x = new XMLHttpRequest();", "XMLHttpRequest")

    @Test
    fun `WebSocket is rejected`() = denylistCase("const ws = new WebSocket('ws://x');", "WebSocket")

    @Test
    fun `importScripts is rejected`() = denylistCase("importScripts('x.js');", "importScripts")

    @Test
    fun `eval() is rejected`() = denylistCase("eval('1+1');", "eval()")

    @Test
    fun `new Function is rejected`() = denylistCase("const f = new Function('return 1');", "new Function")

    @Test
    fun `document dot cookie is rejected`() = denylistCase("const c = document.cookie;", "document.cookie")

    @Test
    fun `document dot cookie with spaces is rejected`() = denylistCase("const c = document . cookie;", "document.cookie")

    @Test
    fun `localStorage is rejected`() = denylistCase("localStorage.setItem('k', 'v');", "localStorage")

    @Test
    fun `sessionStorage is rejected`() = denylistCase("sessionStorage.getItem('k');", "sessionStorage")

    @Test
    fun `indexedDB is rejected`() = denylistCase("indexedDB.open('db');", "indexedDB")

    @Test
    fun `postMessage is rejected`() = denylistCase("postMessage('hi');", "postMessage")

    @Test
    fun `document dot domain is rejected`() = denylistCase("const d = document.domain;", "document.domain")

    @Test
    fun `the denylist scans the whole file (not just the meta block)`() {
        denylistCase("\n// hidden deep\nconst sneaky = globalThis.localStorage;", "localStorage")
    }

    @Test
    fun `the word local in prose does not fire`() {
        // \blocalStorage\b — "my local storage" (with spaces) does not match.
        assertDoesNotThrow { SlopBouncer.inspect(slop("// my local storage convention")) }
    }
}
