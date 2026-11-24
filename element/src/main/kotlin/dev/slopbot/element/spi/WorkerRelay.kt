package dev.slopbot.element.spi

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.element.engine.SlopEvents
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The playback relay — the ELM's half of the relay chain: when the engine
 * dispatches a play (or clears the stage), the command is forwarded to the
 * thin forwarder's webserver (the pod's relay endpoint). Best-effort,
 * fire-and-forget — a dead pod never blocks the ELM.
 *
 * Configuration (element attributes):
 * - `dev.slopbot.element.worker.relay.url` — the pod's relay endpoint
 *   (`https://<pod-relay>/...`); empty disables the relay;
 * - `dev.slopbot.element.worker.relay.token` — a static bearer the ELM
 *   presents to the pod's relay (the pod verifies it against the instance's
 *   OIDC — end-to-end token verification for the first pass).
 */
@Singleton
class WorkerRelay @Inject constructor(
    private val events: SlopEvents,
) : SlopEvents.Listener {

    companion object {
        const val RELAY_URL_ATTRIBUTE = "dev.slopbot.element.worker.relay.url"
        const val RELAY_TOKEN_ATTRIBUTE = "dev.slopbot.element.worker.relay.token"

        /** The event types the relay forwards to the pod. */
        val RELAYED_TYPES = setOf("play", "stageclear", "plays", "note", "stream")
    }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    @Volatile
    private var relayUrl: String? = null

    @Volatile
    private var relayToken: String? = null

    @Volatile
    private var wired = false

    /** Reads the element attributes once and wires the broadcast subscription. */
    @Synchronized
    fun wire(): WorkerRelay {
        if (wired) return this
        val element: Element = ElementSupplier.getElementLocal(WorkerRelay::class.java).get()
        val attributes = element.getElementRecord().attributes()
        relayUrl = attributes.getAttributeOptional(RELAY_URL_ATTRIBUTE)?.toString()?.takeIf { it.isNotEmpty() }
        relayToken = attributes.getAttributeOptional(RELAY_TOKEN_ATTRIBUTE)?.toString()?.takeIf { it.isNotEmpty() }
        if (relayUrl != null) {
            events.subscribe(this)
        }
        wired = true
        return this
    }

    override fun onEvent(type: String, payload: Map<String, Any?>) {
        val url = relayUrl ?: return
        if (!RELAYED_TYPES.contains(type)) return
        try {
            val body = mapper.writeValueAsString(mapOf("type" to type, "payload" to payload))
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            relayToken?.let { builder.header("Authorization", "Bearer $it") }
            // Fire-and-forget: a slow pod is the pod's problem.
            client.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) {
            // best-effort — the pod retries nothing, the stream moves on
        }
    }
}
