package dev.slopbot.element.engine

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The SSE broadcast seam — the reference's `broadcast(type, payload)` (`lib/slop.js`).
 * The REST SSE endpoint subscribes; the engine publishes.
 */
class SlopEvents {

    fun interface Listener {
        fun onEvent(type: String, payload: Map<String, Any?>)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun subscribe(listener: Listener): Closeable {
        listeners.add(listener)
        return Closeable { listeners.remove(listener) }
    }

    fun broadcast(type: String, payload: Map<String, Any?> = emptyMap()) {
        for (listener in listeners) {
            try {
                listener.onEvent(type, payload)
            } catch (e: Exception) {
                listeners.remove(listener) // JS: a dead client is reaped on write failure
            }
        }
    }
}
