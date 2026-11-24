package dev.slopbot.element.engine

import java.util.ArrayDeque

/**
 * The trigger flood cap (reference `lib/slop.js` `triggerDispatchLog`):
 * trigger-initiated plays are capped per minute; extra events drop — but say
 * so, with a visible overlay note throttled to one per ~10s ("a silent drop
 * is indistinguishable from a broken trigger").
 */
class FloodCap(
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val log = ArrayDeque<Long>()
    private var lastNoteAt = 0L

    /**
     * Takes one dispatch slot when the cap allows it. Returns true when the
     * dispatch may proceed. Emits the throttled note through [onNote] when the
     * cap is hit.
     */
    fun take(perMinute: Int, onNote: (String) -> Unit): Boolean {
        val nowMs = now()
        while (log.isNotEmpty() && nowMs - log.first() > 60_000) log.pollFirst()
        if (log.size >= Math.max(1, perMinute)) {
            if (nowMs - lastNoteAt > 10_000) {
                lastNoteAt = nowMs
                onNote(
                    "trigger flood cap hit ($perMinute/min) — extra trigger events are being dropped for a moment",
                )
            }
            return false
        }
        log.addLast(nowMs)
        return true
    }
}
