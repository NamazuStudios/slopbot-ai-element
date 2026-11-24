package dev.slopbot.element.engine

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * The parsed `meta` block of a slop's source — the [SlopMetaParser.metaOf] port
 * of the reference implementation (`lib/slop.js`).
 */
data class SlopMeta(
    val title: String?,
    val author: String?,
    val prompt: String?,
    val createdAt: String?,
    val moderationLevel: String?,
    val controls: String?,
    val triggers: Any?,
)

/**
 * Parses the meta block without importing (the code is untrusted until gated).
 * Ported regex-for-regex from the reference `metaOf`:
 *
 * - scalar fields: `^\s*<key>\s*:\s*['"`]([^'"`]+)['"`]` (multiline), first match wins;
 * - `controls`: a backtick template literal (multiline, `\`` unescaped, `\r` stripped)
 *   else a single-line value with literal `\n` unescaped;
 * - `triggers`: bracket-matched out of the source, then strict-parsed as JSON with
 *   trailing-comma tolerance (`,\s*[}\]]` stripped globally); malformed → dropped;
 *   capped at 12 entries;
 * - fallbacks: title "Untitled slop", author "unknown".
 */
object SlopMetaParser {

    /** The cap on proposed triggers — JS: `parsed.slice(0, 12)`. */
    const val MAX_TRIGGERS = 12

    private val JSON = ObjectMapper()

    private fun pick(code: String, key: String): String? {
        // JS: new RegExp(`^\\s*${key}\\s*:\\s*['"\`]([^'"\`]+)['"\`]`, 'm')
        val pattern = Regex(
            "^\\s*" + Regex.escape(key) + "\\s*:\\s*['\"`]([^'\"`]+)['\"`]",
            RegexOption.MULTILINE,
        )
        val match = pattern.find(code) ?: return null
        return match.groupValues[1]
    }

    fun metaOf(code: String): SlopMeta {
        // controls is multi-line (a description, then one '!command - effect' per
        // line) — written as a backtick template literal so the newlines survive.
        var controls: String? = null
        val block = Regex("""(?m)^\s*controls\s*:\s*`([\s\S]*?)`""").find(code)
        if (block != null) {
            controls = block.groupValues[1]
                .replace(Regex("""\\`"""), "`")   // JS: .replace(/\\`/g, '`')
                .replace("\r", "")                // JS: .replace(/\r/g, '')
                .trim()
                .takeIf { it.isNotEmpty() }
        } else {
            val single = pick(code, "controls")
            controls = single
                ?.replace("\\n", "\n")            // JS: .replace(/\\n/g, '\n')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        // triggers: the agent's proposed trigger list, written as a STRICT JSON array
        // inside the meta block (quoted keys, double quotes). Bracket-matched out of
        // the source, then JSON.parsed; anything malformed is dropped.
        var triggers: Any? = null
        val head = Regex("""(?m)^\s*triggers\s*:\s*(?=\[)""").find(code)
        if (head != null) {
            val start = head.range.first + head.value.length
            var depth = 0
            var i = start
            loop@ while (i < code.length) {
                when (val ch = code[i]) {
                    '[', '{' -> depth++
                    ']', '}' -> {
                        depth--
                        if (depth == 0) {
                            try {
                                // Tolerate the one JSON slip LLMs actually make — a
                                // trailing comma before a closing bracket — while
                                // staying strict about everything else.
                                val text = code.substring(start, i + 1)
                                    .replace(Regex(""",\s*([}\]])"""), "$1")
                                val parsed = JSON.readValue(text, Any::class.java)
                                if (parsed is List<*>) {
                                    triggers = parsed.take(MAX_TRIGGERS)
                                }
                            } catch (e: Exception) {
                                triggers = null // malformed proposal — ignored entirely
                            }
                            break@loop
                        }
                    }
                    else -> if (depth == 0 && !ch.isWhitespace()) break@loop // not actually an array
                }
                i++
            }
        }

        return SlopMeta(
            title = pick(code, "title") ?: "Untitled slop",
            author = pick(code, "author") ?: "unknown",
            prompt = pick(code, "prompt"),
            createdAt = pick(code, "createdAt"),
            moderationLevel = pick(code, "moderationLevel"),
            controls = controls,
            triggers = triggers,
        )
    }
}
