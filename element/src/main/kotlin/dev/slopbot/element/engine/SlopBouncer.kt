package dev.slopbot.element.engine

/**
 * Thrown by [SlopBouncer.inspect] when the code may not pass. The message is
 * the user-facing reason, ported verbatim from the reference implementation.
 */
class SlopRejectedException(message: String) : Exception(message)

/**
 * The bouncer (`inspectSlopCode` port, `lib/slop.js`) — the static gate every
 * slop's source passes before it may register. Byte-for-byte semantics:
 *
 * - the source is a string of at most 250*1024 UTF-16 code units
 *   ([MAX_SLOP_CODE_BYTES]);
 * - it must export `const meta`;
 * - it must export `function init` or `function frame`;
 * - it must not match any denylist pattern.
 *
 * Error messages match the original: "slop code exceeds the 250 KB cap"
 * (`Math.round(MAX_SLOP_CODE_BYTES / 1024)`), "no meta block",
 * "no init/frame exports", and "the bouncer says no: <label>".
 */
object SlopBouncer {

    /** JS: `const MAX_SLOP_CODE_BYTES = 250 * 1024`. */
    const val MAX_SLOP_CODE_BYTES = 250 * 1024

    /** JS: `Math.round(MAX_SLOP_CODE_BYTES / 1024)` — the number in the size message. */
    const val MAX_SLOP_CODE_KB = MAX_SLOP_CODE_BYTES / 1024

    // JS denylist — [pattern, label], tested in order. The Kotlin regexes mirror the
    // JS patterns exactly (note the escaping: JS `/\brequire\s*\(/` etc.).
    private val DENYLIST: List<Pair<Regex, String>> = listOf(
        Regex("""\brequire\s*\(""") to "require()",
        Regex("""from\s+['"]node:""") to "node: import",
        Regex("""\bfetch\s*\(""") to "fetch()",
        Regex("""\bXMLHttpRequest\b""") to "XMLHttpRequest",
        Regex("""new\s+WebSocket\b""") to "WebSocket",
        Regex("""importScripts\s*\(""") to "importScripts",
        Regex("""\beval\s*\(""") to "eval()",
        Regex("""new\s+Function\b""") to "new Function",
        Regex("""document\s*\.\s*cookie""") to "document.cookie",
        Regex("""\blocalStorage\b""") to "localStorage",
        Regex("""\bsessionStorage\b""") to "sessionStorage",
        Regex("""\bindexedDB\b""") to "indexedDB",
        Regex("""\bpostMessage\s*\(""") to "postMessage",
        Regex("""document\s*\.\s*domain""") to "document.domain",
    )

    private val META_EXPORT = Regex("""export\s+const\s+meta\b""")
    private val INIT_OR_FRAME_EXPORT = Regex("""export\s+function\s+(init|frame)\b""")

    /**
     * Gates the given source. Returns silently when the code passes; throws
     * [SlopRejectedException] with the original error message when it does not.
     */
    fun inspect(code: String?) {
        // JS: `typeof code !== 'string' || code.length > MAX_SLOP_CODE_BYTES` — the length
        // is UTF-16 code units (Kotlin's String.length is the same measure), so a boundary
        // behaves identically.
        if (code == null || code.length > MAX_SLOP_CODE_BYTES) {
            throw SlopRejectedException("slop code exceeds the $MAX_SLOP_CODE_KB KB cap")
        }
        if (!META_EXPORT.containsMatchIn(code)) throw SlopRejectedException("no meta block")
        if (!INIT_OR_FRAME_EXPORT.containsMatchIn(code)) throw SlopRejectedException("no init/frame exports")
        for ((pattern, label) in DENYLIST) {
            if (pattern.containsMatchIn(code)) throw SlopRejectedException("the bouncer says no: $label")
        }
    }
}
