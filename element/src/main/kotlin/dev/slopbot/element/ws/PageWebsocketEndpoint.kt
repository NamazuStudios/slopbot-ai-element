package dev.slopbot.element.ws

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.service.SlopEngine
import jakarta.websocket.OnClose
import jakarta.websocket.OnError
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.ServerEndpoint

/**
 * The pages' websocket SERVER endpoint — each page (the OBS overlay, the
 * deck) holds its own websocket connection to the ELM. The ELM relays
 * everything through its code: the events received from the pod (post
 * trigger evaluation, plus raw passthrough where the design requires) and
 * the playback/queue updates — out to every connected page. Mounted by the
 * platform under the element's WS root (`dev.slopbot.elements.element.ws.root`
 * = `/slop/ws`).
 *
 * The SSE events feed stays as a fallback/compat shim; websockets are the
 * primary page transport.
 */
@ServerEndpoint("/pages")
class PageWebsocketEndpoint {

    /** One connected page — the Jakarta Session wrapped in the relay's seam. */
    private class JakartaPageSession(private val session: Session) : PageRelay.PageSession {
        override fun send(text: String): Boolean = try {
            session.basicRemote.sendText(text)
            true
        } catch (e: Exception) {
            false
        }

        override fun close() = try {
            session.close()
        } catch (e: Exception) {
        }
    }

    @OnOpen
    fun onOpen(session: Session) {
        relay().register(JakartaPageSession(session))
        // The page's first frame: the current state snapshot (the overlay's boot).
        try {
            val element: Element = ElementSupplier.getElementLocal(PageWebsocketEndpoint::class.java).get()
            val engine = element.getServiceLocator().getInstance(SlopEngine::class.java)
            val events = element.getServiceLocator().getInstance(SlopEvents::class.java)
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            session.basicRemote.sendText(
                mapper.writeValueAsString(
                    linkedMapOf("type" to "state", "now" to System.currentTimeMillis()) + engine.snapshotMap(),
                ),
            )
            // The snapshot's queue/plays ride the broadcast bus afterwards —
            // subscribe-on-open would double-deliver; the page refetches on
            // state. (The events bus handles the live stream from here.)
            events.broadcast("pages", mapOf("count" to relay().sessionCount()))
        } catch (e: Exception) {
            // a failed snapshot never blocks the connection
        }
    }

    @OnClose
    fun onClose(session: Session) {
        relay().unregister(JakartaPageSession(session))
    }

    @OnError
    fun onError(session: Session?, error: Throwable?) {
        if (session != null) relay().unregister(JakartaPageSession(session))
    }

    private fun relay(): PageRelay {
        val element: Element = ElementSupplier.getElementLocal(PageWebsocketEndpoint::class.java).get()
        return element.getServiceLocator().getInstance(PageRelay::class.java)
    }
}
