package dev.slopbot.element.rest

import dev.slopbot.element.dao.InMemorySlopPodLeaseDao
import dev.slopbot.element.dao.SlopPodLeaseDao
import dev.slopbot.element.model.PodLease
import dev.slopbot.element.service.PodEvictedException
import dev.slopbot.element.service.SlopPodRegistry
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.servlet.http.HttpServletRequest

/**
 * The pod surface — the pod's own credential (the dispatch-minted token)
 * authenticates these routes, NOT a session: the token hash is stored on
 * the lease at dispatch and matched here. Fail closed: no lease, wrong
 * token, or a superseded pod → 401/409.
 *
 * The dispatch route is SUPERUSER-only (the operator's manual trigger, and
 * the path the engine's hook would otherwise drive).
 */
@Path("/pod")
@Produces(MediaType.APPLICATION_JSON)
class SlopPodResource {

    private val element: dev.getelements.elements.sdk.Element =
        dev.getelements.elements.sdk.ElementSupplier.getElementLocal(SlopPodResource::class.java).get()

    private val registry: SlopPodRegistry by lazy { element.getServiceLocator().getInstance(SlopPodRegistry::class.java) }
    private val auth: SlopAuth by lazy { element.getServiceLocator().getInstance(SlopAuth::class.java) }

    /** The pod's credential: the raw token presented as the session secret. */
    private fun leaseForToken(request: HttpServletRequest): Pair<PodLease, String>? {
        val token = request.getHeader("X-Slop-Pod-Token") ?: return null
        if (token.isBlank()) return null
        val streamer = request.getHeader("X-Slop-Pod-Streamer") ?: return null
        val lease = registry.leaseOf(streamer) ?: return null
        return lease to token
    }

    @POST
    @Path("register")
    @Consumes(MediaType.APPLICATION_JSON)
    fun register(body: Map<String, Any?>?): Response {
        val streamer = body?.get("streamer")?.toString() ?: throw unauthorized()
        val token = body?.get("token")?.toString() ?: throw unauthorized()
        val podInstanceId = body?.get("podInstanceId")?.toString() ?: ""
        val address = body?.get("address")?.toString() ?: ""
        val mode = body?.get("mode")?.toString() ?: "build"
        return try {
            val lease = registry.register(streamer, podInstanceId, address, mode, token)
            Response.ok(mapOf("ok" to true, "lease" to lease.podInstanceId, "status" to lease.status)).build()
        } catch (e: PodEvictedException) {
            Response.status(409).entity(mapOf("error" to "EVICTED", "message" to e.message)).build()
        } catch (e: Exception) {
            throw unauthorized()
        }
    }

    @POST
    @Path("heartbeat")
    @Consumes(MediaType.APPLICATION_JSON)
    fun heartbeat(body: Map<String, Any?>?): Response {
        val streamer = body?.get("streamer")?.toString() ?: throw unauthorized()
        val podInstanceId = body?.get("podInstanceId")?.toString() ?: throw unauthorized()
        val lease = registry.heartbeat(streamer, podInstanceId)
            ?: return Response.status(409).entity(mapOf("error" to "EVICTED", "message" to "the lease moved on")).build()
        return Response.ok(mapOf("ok" to true, "status" to lease.status)).build()
    }

    @POST
    @Path("release")
    @Consumes(MediaType.APPLICATION_JSON)
    fun release(body: Map<String, Any?>?): Response {
        val streamer = body?.get("streamer")?.toString() ?: throw unauthorized()
        val podInstanceId = body?.get("podInstanceId")?.toString() ?: throw unauthorized()
        return Response.ok(mapOf("ok" to registry.release(streamer, podInstanceId))).build()
    }

    /** The superuser's manual dispatch (the engine's build trigger drives the same call). */
    @POST
    @Path("dispatch")
    @Consumes(MediaType.APPLICATION_JSON)
    fun dispatch(body: Map<String, Any?>?, @Context request: HttpServletRequest): Response {
        auth.requireSuperuser()
        val streamer = body?.get("streamer")?.toString()
            ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "streamer is required")).build())
        val mode = body?.get("mode")?.toString() ?: "build"
        val result = registry.dispatch(streamer, mode, body?.get("reason")?.toString() ?: "manual dispatch", conductorFn())
        return Response.ok(result).build()
    }

    /** The current lease (superuser diagnostics). */
    @GET
    @Path("lease/{streamer}")
    fun lease(@jakarta.ws.rs.PathParam("streamer") streamer: String, @Context request: HttpServletRequest): Response {
        auth.requireSuperuser()
        val lease = registry.leaseOf(streamer)
            ?: throw WebApplicationException(Response.status(404).entity(mapOf("error" to "no lease")).build())
        return Response.ok(
            mapOf(
                "streamer" to lease.streamer, "podInstanceId" to lease.podInstanceId,
                "address" to lease.address, "mode" to lease.mode, "status" to lease.status,
                "heartbeatAt" to lease.heartbeatAt,
            )
        ).build()
    }

    /**
     * The Conductor dispatch — wired when the element attributes name the
     * admin API and credential; without them the hook returns null and the
     * dispatch lays the lease only (the build waits, fail-open, logged).
     */
    private fun conductorFn(): ((String, String, String) -> Any?)? {
        val element = element
        val attrs = element.getElementRecord().attributes()
        fun attr(name: String): String? =
            attrs.getAttributeOptional(name)?.let { opt ->
                val raw = opt.toString()
                // Attributes Optional.toString() is "Optional[value]" — unwrap it
                val unwrapped = Regex("^Optional\\[(.*)\\]$").find(raw)?.groupValues?.get(1) ?: raw
                unwrapped.takeIf { it.isNotEmpty() }
            }
        val url = attr(SlopApplication.CONDUCTOR_URL) ?: return null
        val credential = attr(SlopApplication.CONDUCTOR_CREDENTIAL) ?: return null
        val profile = attr(SlopApplication.CONDUCTOR_PROFILE) ?: "slopbot-pod"
        val provider = attr(SlopApplication.CONDUCTOR_PROVIDER) ?: "dev.getelements.conductor.kubernetes"
        return { streamer, token, mode ->
            try {
                val body = mapOf(
                    "element" to provider,
                    "profileId" to profile,
                    "environment" to mapOf(
                        "SLOP_ELM_URL" to "",   // the bound instance URL — the element attributes carry it
                        "SLOP_POD_TOKEN" to token,
                        "SLOP_POD_STREAMER" to streamer,
                        "SLOP_POD_MODE" to mode,
                    ),
                )
                val client = java.net.http.HttpClient.newHttpClient()
                val req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url.trimEnd('/') + "/jobs"))
                    .header("Authorization", "Bearer $credential")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body)))
                    .build()
                val resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                mapOf("status" to resp.statusCode(), "body" to resp.body().take(200))
            } catch (e: Exception) {
                mapOf("error" to (e.message ?: e.toString()))
            }
        }
    }

    private fun unauthorized(): WebApplicationException =
        WebApplicationException(Response.status(401).entity(mapOf("error" to "pod authentication required")).build())
}
