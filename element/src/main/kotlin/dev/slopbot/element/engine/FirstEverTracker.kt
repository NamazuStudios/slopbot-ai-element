package dev.slopbot.element.engine

        /**
 * First-ever event tracking (reference issue #4): a "first-time chatter" /
 * "first-time follower" fires once per user, EVER — the seen sets persist
 * across streams and never reset. Persistence is a hook: the ELM persists
 * through the platform (first pass: in-memory per process, durable storage
 * noted in the spec's implementation notes).
 */
class FirstEverTracker(private val persist: (Set<String>, Set<String>) -> Unit = { _, _ -> }) {

    val chat = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val follow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Marks the user seen for the event kind, returning true — and ONLY true —
     * the first time. The record happens even when nothing matches, so "first"
     * means genuinely first.
     */
    fun markSeen(kind: String, userId: String?): Boolean {
        if (userId.isNullOrEmpty()) return false
        val seen = if (kind == "chat") chat else follow
        val first = seen.add(userId)
        if (first) persist(chat, follow)
        return first
    }

    fun isSeen(kind: String, userId: String?): Boolean {
        if (userId.isNullOrEmpty()) return false
        val seen = if (kind == "chat") chat else follow
        return seen.contains(userId)
    }
}
