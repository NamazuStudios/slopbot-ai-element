package dev.slopbot.element.ws

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.element.rest.SlopApplication
import dev.slopbot.element.service.SlopEngine
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.websocket.ContainerProvider
import jakarta.websocket.Session
import jakarta.websocket.WebSocketContainer
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The ELM's websocket CLIENT — connects OUT to the pod's agent-address
 * `dev.slopbot.element.pod.ws.url` / `.token`). The worker runs the websocket
 * SERVER; the ELM is the authenticated client:
 *
 * - **inbound:** the worker's forwarded events (chat lines, follows/subs/…
 *   stream notices) — each frame runs the ELM-side trigger loop via
 *   [PodWsProtocol.handlePodFrame];
 * - **outbound:** the playback commands (play, stageclear, plays updates,
 *   notes) relayed from the engine's broadcast bus via
 *   [PodWsProtocol.onEngineEvent];
 * - **auth:** the ELM presents its OIDC bearer in the client handshake
 *   (`Authorization` header) — the worker validates it against the bound
 *   Elements instance (OIDC-verified end to end).
 *
 * The connection loop runs on a daemon thread with backoff; an empty URL
 * (no pod) idles — the element runs pod-less (the ELM is also the control
 * plane for MCP-only usage).
 */
@Singleton
class PodWebsocketClient @Inject constructor(
    private val protocol: PodWsProtocol,
    private val engine: SlopEngine,
) {

    companion object {
        const val POD_WS_URL = "dev.slopbot.element.pod.ws.url"
        const val POD_WS_TOKEN = "dev.slopbot.element.pod.ws.token"
        private const val BACKOFF_MS = 3000L
        private const val BACKOFF_MAX_MS = 30_000L
    }

    private val running = AtomicBoolean(false)
    private val podSession = AtomicReference<Session?>(null)

    /** The programmatic endpoint — instantiated by the container per connection. */
    class PodEndpoint : jakarta.websocket.Endpoint() {

        private val owner: PodWebsocketClient by lazy {
            val element: Element = ElementSupplier.getElementLocal(PodEndpoint::class.java).get()
            element.getServiceLocator().getInstance(PodWebsocketClient::class.java)
        }

        override fun onOpen(session: Session, config: jakarta.websocket.EndpointConfig?) {
            owner.podSession.set(session)
            owner.log.info("pod websocket connected: {}", session.requestURI)
            session.addMessageHandler(String::class.java) { text ->
                try {
                    owner.protocol.handlePodFrame(text)
                } catch (e: Exception) {
                    owner.log.warn("pod frame failed: {}", e.message)
                }
            }
        }

        override fun onClose(session: Session, closeReason: jakarta.websocket.CloseReason?) {
            owner.podSession.compareAndSet(session, null)
            owner.log.info("pod websocket closed: {}", closeReason)
        }

        override fun onError(session: Session?, error: Throwable?) {
            owner.log.warn("pod websocket error: {}", error?.message)
        }
    }

    private val log = org.slf4j.LoggerFactory.getLogger(PodWebsocketClient::class.java)

    @Volatile
    private var podWsUrl: String? = null

    @Volatile
    private var podWsToken: String? = null

    /** Reads the element attributes and starts the connection loop (once). */
    @Synchronized
    fun start(): PodWebsocketClient {
        if (running.get()) return this
        val element: Element = ElementSupplier.getElementLocal(PodWebsocketClient::class.java).get()
        val attributes = element.getElementRecord().attributes()
        podWsUrl = attributes.getAttributeOptional(POD_WS_URL)
            ?.toString()?.takeIf { it.isNotEmpty() }
        podWsToken = attributes.getAttributeOptional(POD_WS_TOKEN)
            ?.toString()?.takeIf { it.isNotEmpty() }
        // Wire the engine bus → the pod's command sink (the live session).
        protocol.wire(object : PodWsProtocol.CommandSink {
            override fun send(frame: String): Boolean {
                val session = podSession.get() ?: return false
                return try {
                    session.basicRemote.sendText(frame)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        })
        running.set(true)
        val thread = Thread({ loop() }, "slopbot-pod-ws")
        thread.isDaemon = true
        thread.start()
        return this
    }

    /** The connection loop: connect, serve, reconnect with backoff. */
    private fun loop() {
        var backoff = BACKOFF_MS
        while (running.get()) {
            val url = podWsUrl
            if (url == null) {
                // Pod-less configuration — idle; the pod (when dispatched) sets
                // the attribute and reloads the element.
                Thread.sleep(BACKOFF_MAX_MS)
                continue
            }
            try {
                val container: WebSocketContainer = ContainerProvider.getWebSocketContainer()
                val config = jakarta.websocket.ClientEndpointConfig.Builder.create()
                    .configurator(object : jakarta.websocket.ClientEndpointConfig.Configurator() {
                        override fun beforeRequest(headers: MutableMap<String, MutableList<String>>) {
                            // The ELM's OIDC bearer — the worker validates it
                            // against the bound instance (end-to-end verification).
                            val token = podWsToken
                            if (!token.isNullOrEmpty()) {
                                headers["Authorization"] = mutableListOf("Bearer $token")
                            }
                        }
                    })
                    .build()
                container.connectToServer(PodEndpoint::class.java, config, URI.create(url))
                backoff = BACKOFF_MS
                // Connected — the adapter owns the session; wait for the drop.
                while (running.get() && podSession.get()?.isOpen == true) {
                    Thread.sleep(1000)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                log.warn("pod websocket connect failed: {} — retrying", e.message)
            }
            if (running.get()) {
                Thread.sleep(backoff)
                backoff = minOf(backoff * 2, BACKOFF_MAX_MS)
            }
        }
    }

    fun stop() {
        running.set(false)
        podSession.getAndSet(null)?.let { session ->
            try {
                session.close()
            } catch (e: Exception) {
            }
        }
    }
}
