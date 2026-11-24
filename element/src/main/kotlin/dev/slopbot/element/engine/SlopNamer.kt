package dev.slopbot.element.engine

/**
 * The naming port (reference `lib/slop.js` — `settleGeneration`, `safeName`,
 * `shortName`). Assigns the file name server-side from the meta block:
 *
 * - author = meta.author lowercased, `[a-z0-9]`-only (caller's fallback login
 *   when the meta omits it, then `anonymous`);
 * - slug = title lowercased, non-alphanumerics → `-`, trimmed of leading and
 *   trailing hyphens, ≤ 25 chars (fallback `slop`);
 * - name = `author-slug.js` capped at 39 chars; when that is not a safe name,
 *   `author-<base36 ts>.js`; when that is not either, `slop-<base36 ts>.js`;
 * - on collision, `-2`, `-3`… counters appended (safe-name permitting), else
 *   `slop-<base36 ts>-<n>.js`.
 */
object SlopNamer {

    /** JS: `const safeName = (name) => /^[a-z0-9][a-z0-9-]{0,47}\.js$/.test(name) ? name : null`. */
    private val SAFE_NAME = Regex("""^[a-z0-9][a-z0-9-]{0,47}\.js$""")

    /**
     * JS `safeName` — the name, or null when it is not a safe file name.
     */
    fun safeName(name: String?): String? =
        if (name != null && SAFE_NAME.containsMatchIn(name)) name else null

    /**
     * JS `shortName` — the user-facing handle: the file name minus the creator
     * login prefix (`<login>-rubbery-hotdog.js` → `rubbery-hotdog`).
     */
    fun shortName(name: String?, author: String?): String {
        val base = (name ?: "").replace(Regex("""\.js$"""), "")
        val login = (author ?: "").lowercase()
        return if (login.isNotEmpty() && base.lowercase().startsWith("$login-")) {
            base.substring(login.length + 1)
        } else {
            base
        }
    }

    /**
     * Builds the base (collision-free) name from meta values, mirroring the
     * reference's `settleGeneration` naming block.
     *
     * @param metaAuthor the meta block's author (may be null/blank)
     * @param fallbackAuthor the caller-provided author fallback (the creator's
     *        login fed through the prompt; may be null)
     * @param title the meta block's title (may be null)
     * @param nameTaken predicate — true when the name already exists
     * @param nowMs the epoch ms (base-36 timestamp source)
     * @return the assigned name (never taken, always non-empty)
     */
    fun assignName(
        metaAuthor: String?,
        fallbackAuthor: String?,
        title: String?,
        nameTaken: (String) -> Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        // JS: String(meta.author ?? item.user?.login ?? 'anonymous')
        //       .toLowerCase().replace(/[^a-z0-9]/g, '') || 'anonymous'
        val author = ((metaAuthor ?: fallbackAuthor ?: "anonymous").lowercase()
            .replace(Regex("[^a-z0-9]"), ""))
            .ifEmpty { "anonymous" }

        // JS: String(meta.title ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '-')
        //       .replace(/^-+|-+$/g, '').slice(0, 25) || 'slop'
        val slug = (title ?: "").lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("""^-+|-+$"""), "")
            .take(25)
            .ifEmpty { "slop" }

        val ts36 = nowMs.toString(36)

        // JS: safeName(`${author}-${slug}`.slice(0, 39) + '.js')
        //  ?? safeName(`${author}-${ts36}`.slice(0, 39) + '.js')
        //  ?? `slop-${ts36}.js`
        var name = safeName("$author-$slug".take(39) + ".js")
            ?: safeName("$author-$ts36".take(39) + ".js")
            ?: "slop-$ts36.js"

        // JS: let n = 2; while (taken) { name = safeName(`${author}-${slug}-${n}`.slice(0,39)+'.js')
        //      ?? `slop-${ts36}-${n}.js`; n += 1; }
        var n = 2
        while (nameTaken(name)) {
            name = safeName("$author-$slug-$n".take(39) + ".js")
                ?: "slop-$ts36-$n.js"
            n += 1
        }
        return name
    }
}
