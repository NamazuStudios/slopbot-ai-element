package dev.slopbot.element.engine

/**
 * Scans slop source for asset references — the same scan the bundler uses.
 * Slops reach the shared pool through `sound.sample('name')` and per-slop
 * media through `ctx.media.video('name')`; the bundler inlines whatever a
 * slop references by name, and the admin surface computes referenced-by
 * counts with the same extraction (one scanner, so the two can't drift).
 */
object SlopReferenceScanner {

    private val SOUND = Regex("""sound\.sample\(\s*['"`]([^'"`]+)""")
    private val VIDEO = Regex("""ctx\.media\.video\(\s*['"`]([^'"`]+)""")

    /** Every `sound.sample('…')` reference in the source, lowercased. */
    fun soundRefs(code: String): List<String> =
        SOUND.findAll(code).map { it.groupValues[1].lowercase() }.toList()

    /** Every `ctx.media.video('…')` reference in the source, lowercased. */
    fun videoRefs(code: String): List<String> =
        VIDEO.findAll(code).map { it.groupValues[1].lowercase() }.toList()

    /** Every asset reference (sound + video) in the source, lowercased. */
    fun allRefs(code: String): Set<String> =
        linkedSetOf<String>().apply { addAll(soundRefs(code)); addAll(videoRefs(code)) }
}
