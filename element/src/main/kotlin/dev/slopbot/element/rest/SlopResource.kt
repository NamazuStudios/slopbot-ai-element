package dev.slopbot.element.rest

import dev.slopbot.element.dao.SlopDao
import dev.slopbot.element.dao.SlopOverlayKeyDao
import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.model.OverlayKey
import dev.slopbot.element.model.Participant
import dev.slopbot.element.service.SlopEngine
import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.OPTIONS
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * The slop surface — every route under `/slop/api/slop`. Overlay/viewer
 * routes stay OPEN (no session): snapshot, SSE events, bundles, heartbeat,
 * end reports, cameo plays. Everything else is management: 401 anonymous /
 * 403 viewer / 200 streamer-or-superuser.
 */
@Path("/slop")
@Produces(MediaType.APPLICATION_JSON)
class SlopResource {

    // The examples' pattern: resources resolve services through the element's
    // own service locator (the Jersey runtime doesn't inject Guice directly).
    private val element: Element = ElementSupplier.getElementLocal(SlopResource::class.java).get()

    private val engine: SlopEngine by lazy { element.getServiceLocator().getInstance(SlopEngine::class.java) }
    private val events: SlopEvents by lazy { element.getServiceLocator().getInstance(SlopEvents::class.java) }
    private val slopDao: SlopDao by lazy { element.getServiceLocator().getInstance(SlopDao::class.java) }
    private val auth: SlopAuth by lazy { element.getServiceLocator().getInstance(SlopAuth::class.java) }
    private val overlayKeys: SlopOverlayKeyDao by lazy { element.getServiceLocator().getInstance(SlopOverlayKeyDao::class.java) }

    // --- open (overlay) routes -----------------------------------------------------

    @GET
    fun snapshot(@QueryParam("streamer") streamer: String?): Response =
        Response.ok(engine.snapshotMap()).build()

    /** The streamer's overlay URL — creates the key on first use. Session-authed. */
    @GET
    @Path("/overlay/url")
    fun overlayUrl(@Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val user = auth.requireAuthenticated(request)
        val login = user.name ?: throw SlopAuth.unauthorized()
        var stored = overlayKeys.findByStreamer(login)
        if (stored == null) {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            stored = OverlayKey().apply {
                streamer = login
                key = bytes.joinToString("") { "%02x".format(it) }
                createdAt = System.currentTimeMillis()
            }
            overlayKeys.put(stored)
        }
        return Response.ok(
            mapOf(
                "streamer" to login,
                "key" to stored.key,
                "url" to "/slop.html?stream=$login&k=${stored.key}",
            )
        ).build()
    }

    /** Rotate the overlay key — every existing browser source is invalidated. */
    @POST
    @Path("/overlay/rotate")
    fun rotateOverlay(@Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val user = auth.requireAuthenticated(request)
        val login = user.name ?: throw SlopAuth.unauthorized()
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val key = bytes.joinToString("") { "%02x".format(it) }
        val existing = overlayKeys.findByStreamer(login)
        val doc = existing ?: OverlayKey().apply { streamer = login; createdAt = System.currentTimeMillis() }
        doc.key = key
        overlayKeys.put(doc)
        return Response.ok(
            mapOf("streamer" to login, "key" to key, "url" to "/slop.html?stream=$login&k=$key")
        ).build()
    }

    @GET
    @Path("/events")
    @Produces("text/event-stream")
    fun events(
        @QueryParam("stream") stream: String?,
        @QueryParam("k") overlayKey: String?,
    ): Response {
        // The overlay's keyed path (Pod Boot Mechanics §overlay): the OBS
        // browser source carries ?stream=<login>&k=<overlay-key> — the key is
        // validated against the streamer's stored overlay key and a mismatch
        // is refused (the keyed overlay never sees the stream without it).
        // The unkeyed path stays the dashboard's own (session or open view).
        if (!stream.isNullOrBlank() && !overlayKey.isNullOrBlank()) {
            val stored = overlayKeys.findByStreamer(stream)?.key
            if (stored == null || stored != overlayKey) {
                // status-only: the class @Produces is text/event-stream — a JSON
                // entity has no writer there (the OBS source just sees the refusal)
                return Response.status(403).build()
            }
        }
        val streamOut = StreamingOutput { output -> streamEvents(output) }
        return Response.ok(streamOut)
            .header("Cache-Control", "no-cache, no-transform")
            .build()
    }

    private fun streamEvents(output: OutputStream) {
        output.write("retry: 500\n\n".toByteArray(StandardCharsets.UTF_8))
        val state = "event: state\ndata: " + jacksonJson(engine.snapshotMap()) + "\n\n"
        output.write(state.toByteArray(StandardCharsets.UTF_8))
        output.flush()
        // The subscription writes events as they broadcast; the heartbeat keeps
        // the connection warm every 15s. (The servlet container owns the read
        // side — a closed connection reaps the listener through the failure path.)
        val closer = events.subscribe { type, payload ->
            val data = "event: $type\ndata: " + jacksonJson(payload) + "\n\n"
            output.write(data.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
        try {
            while (true) {
                Thread.sleep(15_000)
                output.write(": hb\n\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // connection closed — reaped below
        } finally {
            closer.close()
            try {
                output.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun jacksonJson(payload: Any?): String =
        com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload)

    @GET
    @Path("/bundle/{name}")
    fun bundle(@PathParam("name") name: String, @QueryParam("chained") chained: String?, @QueryParam("playId") playId: String?): Response =
        try {
            Response.ok(engine.bundle(name, chained == "1", playId)).build()
        } catch (e: Exception) {
            Response.status(400).entity(mapOf("error" to (e.message ?: "bundle failed"))).build()
        }

    @POST
    @Path("/play/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun play(@PathParam("name") name: String, body: Map<String, Any?>?): Response {
        // Cameo requests from a playing slop's sandbox — routed through the
        // same play() rules as chat (cooldowns, repeats, suspends). Short names
        // resolve like !slop.
        val candidates = matchSlopNames(name)
        if (candidates.size != 1) {
            return Response.status(404)
                .entity(mapOf("ok" to false, "error" to if (candidates.size > 1) "ambiguous" else "no such slop"))
                .build()
        }
        val via = body?.get("via")?.toString() ?: "deck"
        val chained = body?.get("chained") == true
        return Response.ok(mapOf("ok" to engine.play(candidates[0], via, chained))).build()
    }

    private fun matchSlopNames(asked: String): List<String> {
        val query = asked.lowercase().trim().replace(Regex("""\.js$"""), "")
        if (query.isEmpty()) return emptyList()
        val names = engine.registryNames().sorted()
        val exact = "$query.js"
        if (names.contains(exact)) return listOf(exact)
        val exactShort = names.filter { n ->
            val entry = engine.registryEntry(n)
            entry?.short?.lowercase() == query
        }
        if (exactShort.isNotEmpty()) return exactShort
        return names.filter { n -> query.split("-").all { token -> n.contains(token) } }
    }

    @POST
    @Path("/plays/heartbeat")
    fun heartbeat(): Response {
        engine.noteOverlayAlive()
        return Response.ok(mapOf("ok" to true)).build()
    }

    @POST
    @Path("/ended")
    @Consumes(MediaType.APPLICATION_JSON)
    fun ended(body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val ok = engine.playEnded(
            (body?.get("name") ?: "").toString(),
            body?.get("playId")?.toString(),
        )
        return Response.ok(mapOf("ok" to ok)).build()
    }

    @OPTIONS
    @Path("/ended")
    fun endedPreflight(): Response = Response.ok().build()

    @OPTIONS
    @Path("/play/{name}")
    fun playPreflight(): Response = Response.ok().build()

    // --- management routes -----------------------------------------------------------

    @POST
    @Path("/queue/{id}/{action}")
    fun queueAction(@PathParam("id") id: String, @PathParam("action") action: String, body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        val ok = when (SlopEngineQueueActions.of(action)) {
            SlopEngineQueueActions.PRESENT -> engine.present(id)
            SlopEngineQueueActions.DISMISS -> engine.dismiss(id)
            SlopEngineQueueActions.REJECT -> engine.reject(id)
            SlopEngineQueueActions.APPROVE -> engine.approve(id)
            SlopEngineQueueActions.RESTART -> engine.restartJob(id)
            SlopEngineQueueActions.CANCEL -> engine.cancelJob(id)
            SlopEngineQueueActions.REMOVE -> engine.remove(id)
            null -> throw WebApplicationException(Response.status(404).entity(mapOf("error" to "no such action")).build())
        }
        return Response.ok(mapOf("ok" to ok)).build()
    }

    @POST
    @Path("/suspend/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun suspend(@PathParam("name") name: String, body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        val suspended = body?.get("suspended")?.let { it == true || it == "true" } ?: true
        return Response.ok(mapOf("ok" to engine.suspend(name, suspended))).build()
    }

    /** The NSFW flag — glassed cards in the dashboard. Streamer-or-superuser. */
    @POST
    @Path("/nsfw/{name}")
    fun setNsfw(@PathParam("name") name: String, body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.setNsfw(name, body?.get("nsfw")?.let { it == true || it == "true" } ?: true))).build()
    }

    @GET
    @Path("/triggers/{name}")
    fun getTriggers(@PathParam("name") name: String, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        val entry = engine.registryEntry(name)
            ?: throw WebApplicationException(Response.status(404).entity(mapOf("error" to "no such slop")).build())
        return Response.ok(mapOf("triggers" to entry.triggers, "cooldownSeconds" to entry.cooldownSeconds, "slopCommand" to entry.slopCommand)).build()
    }

    @POST
    @Path("/triggers/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    fun saveTriggers(@PathParam("name") name: String, body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        val rawList = body?.get("triggers") as? List<*>
        return Response.ok(engine.setTriggers(name, rawList, body?.get("cooldownSeconds"), body?.get("slopCommand"))).build()
    }

    @POST
    @Path("/triggers/{name}/test")
    @Consumes(MediaType.APPLICATION_JSON)
    fun testTriggers(@PathParam("name") name: String, body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        if (engine.registryEntry(name) == null) {
            return Response.ok(mapOf("ok" to false, "error" to "no such slop")).build()
        }
        val matched = engine.testTriggers(name, body?.get("text")?.toString() ?: "")
        return Response.ok(mapOf("ok" to true, "matched" to matched)).build()
    }

    @POST
    @Path("/cooldown/{name}/clear")
    fun cooldownClear(@PathParam("name") name: String, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.cooldownClear(name))).build()
    }

    @POST
    @Path("/trigger/dispatch")
    @Consumes(MediaType.APPLICATION_JSON)
    fun dispatchTrigger(body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        val event = dev.slopbot.api.slop.SlopTriggerEvent(
            type = body?.get("type")?.toString() ?: "chat",
            userId = body?.get("userId")?.toString(),
            login = body?.get("login")?.toString(),
            text = body?.get("text")?.toString(),
            mod = body?.get("mod") == true,
            broadcaster = body?.get("broadcaster") == true,
            first = body?.get("first") == true,
            viewers = (body?.get("viewers") as? Number)?.toInt(),
            bits = (body?.get("bits") as? Number)?.toInt(),
            tier = (body?.get("tier") as? Number)?.toInt(),
            months = (body?.get("months") as? Number)?.toInt(),
            rewardId = body?.get("rewardId")?.toString(),
            synthetic = true,
        )
        return Response.ok(mapOf("ok" to engine.dispatchTrigger(event))).build()
    }

    @POST
    @Path("/config")
    @Consumes(MediaType.APPLICATION_JSON)
    fun config(body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        val result = engine.config.configure(body ?: emptyMap())
        return Response.ok(
            if (result.ok) {
                mapOf("ok" to true, "applied" to result.applied, "config" to engine.config.snapshot())
            } else {
                mapOf("ok" to false, "error" to result.error)
            },
        ).build()
    }

    @POST
    @Path("/plays/pause")
    fun pause(@Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.setPaused(true))).build()
    }

    @POST
    @Path("/plays/resume")
    fun resume(@Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.setPaused(false))).build()
    }

    @POST
    @Path("/plays/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    fun playsRemove(body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.playRemove((body?.get("name") ?: "").toString()))).build()
    }

    @POST
    @Path("/pending/{name}/approve")
    fun pendingApprove(@PathParam("name") name: String, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.approvePending(name))).build()
    }

    @POST
    @Path("/pending/{name}/reject")
    fun pendingReject(@PathParam("name") name: String, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.rejectPending(name))).build()
    }

    @POST
    @Path("/delete/{name}")
    fun delete(@PathParam("name") name: String, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.deleteSlop(name))).build()
    }

    @POST
    @Path("/build")
    @Consumes(MediaType.APPLICATION_JSON)
    fun build(body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.buildDirect(body?.get("text")?.toString()))).build()
    }

    /** The formal build entry: the session user is the creator, the workshop's
     *  per-build overrides ride along. */
    @POST
    @Path("/generate")
    @Consumes(MediaType.APPLICATION_JSON)
    fun generate(body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val user = auth.requireStreamerOrSuperuser(request)
        val overrides = linkedMapOf<String, Any?>()
        body?.get("moderationLevel")?.let { overrides["moderationLevel"] = it }
        body?.get("model")?.let { overrides["model"] = it }
        body?.get("agentNotes")?.let { overrides["agentNotes"] = it }
        body?.get("maxSeconds")?.let { overrides["maxSeconds"] = it }
        return Response.ok(
            mapOf("ok" to engine.generate(
                text = body?.get("text")?.toString(),
                user = Participant(login = user.name, displayName = user.displayName),
                overrides = overrides.ifEmpty { null },
            ))
        ).build()
    }

    /** The agent's build brief — the same payload the MCP builds.next verb
     *  returns, streamer-scoped so a human (or the dashboard) can drive the
     *  loop without the pod's OIDC chain. */
    @POST
    @Path("/builds/next")
    @Consumes(MediaType.APPLICATION_JSON)
    fun buildsNext(@Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        val brief = engine.generationBrief()
        return Response.ok(brief ?: mapOf("empty" to true, "message" to "no build is waiting")).build()
    }

    /** The agent reports a build failure (the manual path of MCP builds.fail). */
    @POST
    @Path("/builds/fail")
    @Consumes(MediaType.APPLICATION_JSON)
    fun buildsFail(body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        auth.requireStreamerOrSuperuser(request)
        return Response.ok(mapOf("ok" to engine.failGeneration(body?.get("reason")?.toString()))).build()
    }

    /** The manual build path: create a slop from code directly (the same
     *  bouncer + meta pipeline the MCP slops.create verb runs). The demo's
     *  agent stand-in; the pod's real path stays MCP. */
    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    fun create(body: Map<String, Any?>?, @Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val user = auth.requireStreamerOrSuperuser(request)
        val code = body?.get("code")?.toString()
            ?: throw WebApplicationException(Response.status(400).entity(mapOf("error" to "code is required")).build())
        val name = engine.createSlop(
            code = code,
            creator = Participant(login = user.name, displayName = user.displayName),
            streamerLogin = user.name ?: "",
        )
        return Response.ok(mapOf("name" to name, "moderation" to "PENDING")).build()
    }
}
