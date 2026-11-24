package dev.slopbot.element.ws

import dev.slopbot.element.engine.SlopEvents
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * The page relay — the ELM's websocket SERVER surface for pages (the OBS
 * overlay, the deck). Each page holds its own websocket connection to the
 * ELM; the ELM **relays everything through its code**: the events received
 * from the pod (post trigger evaluation, plus raw passthrough where the
 * design requires) and the playback/queue updates go out to every connected
 * page. This is the page transport; the SSE events feed stays as a
 * fallback/compat shim.
 */
@Singleton
class PageRelay @Inject constructor(
    private val events: SlopEvents,
) : SlopEvents.Listener {

    /** One connected page — transport-agnostic send (the Jakarta adapter wraps a Session). */
    interface PageSession {
        /** Sends one text frame (a JSON payload). Returns false when the send failed. */
        fun send(text: String): Boolean

        /** Closes the session (a dead page is reaped). */
        fun close()
    }

    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<PageSession>()

    @Volatile
    private var wired = false

    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    /** Subscribes the relay to the engine's broadcast bus (once). */
    @Synchronized
    fun wire(): PageRelay {
        if (wired) return this
        events.subscribe(this)
        wired = true
        return this
    }

    /** The number of connected pages (the snapshot's connection liveness). */
    fun sessionCount(): Int = sessions.size

    /** Registers one page session (the endpoint's onOpen). */
    fun register(session: PageSession) {
        sessions.add(session)
    }

    /** Unregisters one page session (the endpoint's onClose/onError). */
    fun unregister(session: PageSession) {
        sessions.remove(session)
    }

    override fun onEvent(type: String, payload: Map<String, Any?>) {
        broadcast(type, payload)
    }

    /** Fans one engine event out to every connected page; a dead page is reaped. */
    fun broadcast(type: String, payload: Map<String, Any?> = emptyMap()) {
        if (sessions.isEmpty()) return
        val frame = try {
            val body = LinkedHashMap<String, Any?>()
            body["type"] = type
            body["now"] = System.currentTimeMillis()
            body.putAll(payload)
            mapper.writeValueAsString(body)
        } catch (e: Exception) {
            return
        }
        for (session in sessions) {
            if (!session.send(frame)) {
                session.close()
                sessions.remove(session)
            }
        }
    }
}
