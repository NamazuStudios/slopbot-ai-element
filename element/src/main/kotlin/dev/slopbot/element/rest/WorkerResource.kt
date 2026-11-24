package dev.slopbot.element.rest

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.api.slop.SlopTriggerEvent
import dev.slopbot.element.mcp.OidcValidator
import dev.slopbot.element.service.SlopEngine
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * The worker event-ingestion surface — the thin forwarder's feed. The pod's
 * main container is a thin Node forwarder: it watches the streamer's
 * chat/EventSub and forwards events here; the trigger loop runs ELM-side
 * (this element's engine: matching in all modes, cooldowns, flood cap,
 * allow-list, first-ever tracking, immediate-vs-queued dispatch), and the
 * resulting playback commands go back to the worker through the ELM's relay
 * chain (see [dev.slopbot.element.spi.WorkerRelay]).
 *
 * Auth middleware: the same instance-OIDC pod token as the MCP surface —
 * 401 missing/expired/foreign; 403 wrong scope. The token is streamer-scoped:
 * that streamer, and only that streamer, runs that pod.
 *
 * Batches are accepted (the forwarder may coalesce): a top-level event
 * object, a `{"events": [...]}` array, or a stream notice
 * (`{"type": "stream-start"}` / `{"type": "stream-end"}`).
 */
@Path("/worker")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class WorkerResource {

    private val element: Element = ElementSupplier.getElementLocal(WorkerResource::class.java).get()

    private val engine: SlopEngine by lazy { element.getServiceLocator().getInstance(SlopEngine::class.java) }
    private val validator: OidcValidator by lazy { element.getServiceLocator().getInstance(OidcValidator::class.java) }

    @POST
    @Path("/events")
    fun events(@HeaderParam("Authorization") authorization: String?, body: Map<*, *>?): Response {
        // Auth middleware — every call, before any dispatch.
        val token = authorization?.removePrefix("Bearer ")?.trim()
        val verified = when (val result = validator.validate(token)) {
            is OidcValidator.Result.Ok -> result.verified
            is OidcValidator.Result.Fail -> return Response.status(result.status)
                .entity(mapOf("error" to result.reason))
                .build()
        }

        if (body == null) {
            return Response.ok(mapOf("ok" to true, "results" to emptyList<Any>())).build()
        }

        // A batch: {"events": [...]} — or a single event object.
        val batch: List<*> = (body["events"] as? List<*>) ?: listOf(body)
        val results = batch.map { raw -> ingestOne(raw) }

        return Response.ok(
            mapOf(
                "ok" to results.all { (it["ok"] as? Boolean) == true },
                "results" to results,
                "stream" to mapOf("live" to engine.streamLive),
            ),
        ).build()
    }

    /** One ingested event (or a stream notice). */
    private fun ingestOne(raw: Any?): Map<String, Any?> {
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
            else -> engine.ingest(toEvent(raw, type))
        }
    }

    /** Maps one forwarded event's JSON to the engine's event shape. */
    private fun toEvent(raw: Map<*, *>, type: String): SlopTriggerEvent = SlopTriggerEvent(
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
    )
}
