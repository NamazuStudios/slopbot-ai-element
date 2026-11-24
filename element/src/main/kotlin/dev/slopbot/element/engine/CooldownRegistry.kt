package dev.slopbot.element.engine

/**
 * Per-slop cooldowns (reference issue #5, `lib/slop.js`): while a slop is
 * cooling down, NO trigger can queue it (a trigger with `bypassCooldown` set
 * still can). Manual plays — the deck button, `!slop`, the approve flow — are
 * never blocked. The window arms when the slop plays via a trigger (each
 * trigger play resets it); the streamer can clear any slop's cooldown.
 */
class CooldownRegistry(private val now: () -> Long = System::currentTimeMillis) {

    data class Window(var duration: Int = 0, var until: Long = 0)

    private val windows = java.util.concurrent.ConcurrentHashMap<String, Window>()

    fun of(name: String): Window = windows[name] ?: Window(0, 0)

    fun onCooldown(name: String): Boolean = windows[name]?.let { it.until > now() } ?: false

    /** The window arms when the slop takes a trigger-initiated play; nothing to arm when no duration is configured. */
    fun arm(name: String): Long? {
        val c = windows[name] ?: return null
        if (c.duration <= 0) return null
        c.until = now() + c.duration * 1000L
        return c.until
    }

    /** Sets the duration (seconds) — a change never retro-actively cools a slop. 0 removes the window. */
    fun setDuration(name: String, seconds: Int): Long {
        if (seconds <= 0) {
            windows.remove(name)
            return 0
        }
        val existing = windows[name]
        val window = Window(seconds, existing?.until ?: 0)
        windows[name] = window
        return window.until
    }

    /** The streamer's reset: any slop, cleared immediately. */
    fun clear(name: String): Boolean {
        val c = windows[name]
        if (c?.until != null && c.until > 0) {
            c.until = 0
            return true
        }
        return false
    }

    fun remove(name: String) {
        windows.remove(name)
    }

    /** The live map of still-cooling windows (name → until), for the snapshot. */
    fun liveUntil(): Map<String, Long> =
        windows.entries.mapNotNull { (name, c) -> if (c.until > now()) name to c.until else null }.toMap()
}
