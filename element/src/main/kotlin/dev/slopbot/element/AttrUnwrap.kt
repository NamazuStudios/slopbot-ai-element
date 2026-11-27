package dev.slopbot.element

/**
 * The element-attribute value unwrapper. Attribute values deserialize through
 * several shapes depending on how the deployment's pathAttributes carried them:
 *
 *   Document{{value=X}}      (the pathAttributes Document)
 *   Optional[Document{{value=X}}]
 *   Optional[{value=X}]
 *   {"value":"X"}            (the raw JSON shape)
 *   {value=X}                (a Map toString)
 *   Optional[X]
 *   X                        (plain — the shape the conductor's attributes use)
 *
 * A single-shot regex chain misses the combined shapes (e.g. the fourth
 * `Optional[...]` regex happily returns the inner `Document{{value=…}}`
 * un-unwrapped — which is exactly the garbage that leaked into the pod's
 * SLOP_ELM_URL). Unwrap in a loop until the string stops changing.
 */
object AttrUnwrap {

    private val SHAPES = listOf(
        Regex("^Document\\{\\{value=\\s*\"?(.*)\"?\\}\\}\\s*$"),
        Regex("^\\{\\s*\"value\"\\s*:\\s*\"(.*)\"\\s*\\}\\s*$"),
        Regex("^\\{value=\\s*\"?(.*)\"?\\}\\s*$"),
        Regex("^Optional\\[(.*)\\]\\s*$"),
    )

    /** Unwrap to the plain string; null for Optional.empty/blank. */
    fun unwrap(raw: Any?): String? {
        var s = raw?.toString() ?: return null
        while (true) {
            val next = SHAPES.firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1) } ?: break
            s = next
        }
        return s.takeIf { it != "Optional.empty" && it.isNotBlank() }
    }
}
