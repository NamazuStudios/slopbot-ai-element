package dev.slopbot.element.ws

import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.service.SlopEngine
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * The pod websocket protocol — the message shapes for the ELM's websocket
 * CLIENT connection to the pod's agent-address websocket. The worker (the
 * thin Node forwarder) runs the websocket SERVER; the ELM is the
 * authenticated client:
 *
 * - **pod → ELM** — the forwarded events: chat lines, follows/subs/resubs/
 *   subgifts/raids/cheers/redemptions, and stream-start/stream-end notices.
 *   Each frame runs the ELM-side trigger loop (matching in all modes,
 *   cooldowns, flood cap, allow-list, first-ever tracking, immediate-vs-
 *   queued dispatch). A coalesced frame (`{"events": [...]}`) is accepted.
 * - **ELM → pod** — the playback commands the pod's overlay chain executes
 *   (play, stageclear, plays updates, notes, stream state), relayed from the
 *   engine's broadcast bus.
 *
 * The connection is OIDC-verified end to end: the ELM presents its bearer
 * token in the client handshake; the worker validates it against the bound
 * Elements instance.
 */
@Singleton
class PodWsProtocol @Inject constructor(
    private val engine: SlopEngine,
    private val events: SlopEvents,
) {

    companion object {
        /** The engine event types relayed to the pod as playback commands. */
        val RELAYED_TYPES = setOf("play", "stageclear", "plays", "note", "stream", "buildDone")

        /** The streamed event types passed through to pages raw (design passthrough). */
        val PASSTHROUGH_TYPES = setOf("chat", "follow", "sub", "resub", "subgift", "raid", "cheer", "redemption")
    }

    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    /** The sink for command frames — the websocket client's send (testable seam). */
    interface CommandSink {
        fun send(frame: String): Boolean
    }

    private var commandSink: CommandSink? = null

    /** Wires the command sink (the live websocket) and subscribes the bus. */
    @Synchronized
    fun wire(sink: CommandSink): PodWsProtocol {
        commandSink = sink
        events.subscribe { type, payload -> onEngineEvent(type, payload) }
        return this
    }

    /**
     * Handles one inbound pod frame: runs the trigger loop on the forwarded
     * events. Returns the per-event dispatch results.
     */
    fun handlePodFrame(text: String): List<Map<String, Any?>> {
        val frame: Map<*, *> = try {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(text, Map::class.java) as Map<*, *>
        } catch (e: Exception) {
            return listOf(mapOf("ok" to false, "dispatched" to false, "error" to "malformed frame"))
        }
        val batch: List<*> = (frame["events"] as? List<*>) ?: listOf(frame)
        return batch.map { raw -> handlePodEvent(raw) }
    }

    private fun handlePodEvent(raw: Any?): Map<String, Any?> {
        if (raw !is Map<*, *>) {
            return mapOf("ok" to false, "dispatched" to false, "error" to "each event must be an object")
        }
        val type = raw["type"]?.toString() ?: "chat"
        return when (type) {
            "stream-start" -> {
                engine.noteStreamStart()
                mapOf("ok" to true, "dispatched" to false, "type" to type)
            }
            "stream-end" -> {
                engine.noteStreamEnd()
                mapOf("ok" to true, "dispatched" to false, "type" to type)
            }
            else -> engine.ingest(
                dev.slopbot.api.slop.SlopTriggerEvent(
                    type = type,
                    userId = raw["userId"]?.toString(),
                    login = raw["login"]?.toString(),
                    displayName = raw["displayName"]?.toString(),
                    text = raw["text"]?.toString(),
                    mod = raw["mod"] == true,
                    broadcaster = raw["broadcaster"] == true,
                    first = raw["first"] == true,
                    viewers = (raw["viewers"] as? Number)?.toInt(),
                    bits = (raw["bits"] as? Number)?.toInt(),
                    tier = (raw["tier"] as? Number)?.toInt(),
                    months = (raw["months"] as? Number)?.toInt(),
                    count = (raw["count"] as? Number)?.toInt(),
                    rewardId = raw["rewardId"]?.toString(),
                ),
            ).let { result ->
                // Raw passthrough where the design requires: the streamed event
                // (post trigger evaluation) also fans out to the pages.
                if (PASSTHROUGH_TYPES.contains(type)) {
                    events.broadcast(
                        "event",
                        mapOf(
                            "type" to type,
                            "login" to raw["login"]?.toString(),
                            "displayName" to raw["displayName"]?.toString(),
                            "text" to raw["text"]?.toString(),
                            "first" to (result["dispatched"] == true),
                        ),
                    )
                }
                result
            }.let { it + mapOf("type" to type) }
        }
    }

    /** Relays an engine event to the pod as a playback command (a command frame). */
    fun onEngineEvent(type: String, payload: Map<String, Any?>) {
        val sink = commandSink ?: return
        if (!RELAYED_TYPES.contains(type)) return
        val frame = try {
            val body = LinkedHashMap<String, Any?>()
            body["command"] = type
            body["now"] = System.currentTimeMillis()
            body.putAll(payload)
            mapper.writeValueAsString(body)
        } catch (e: Exception) {
            return
        }
        sink.send(frame)
    }
}
