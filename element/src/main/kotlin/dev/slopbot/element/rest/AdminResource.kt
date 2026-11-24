package dev.slopbot.element.rest

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.element.dao.SlopAuditDao
import dev.slopbot.element.dao.SlopAuditor
import dev.slopbot.element.dao.SlopDao
import dev.slopbot.element.model.Participant
import dev.slopbot.element.service.SlopAssetAdmin
import dev.slopbot.element.service.SlopEngine
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * The admin surface — SUPERUSER + acting-identity header. Every mutating
 * admin route records `actingAs` + `actor` in an append-only `slop_audit`
 * collection (who, whom, what, when, payload digest).
 */
@Path("/admin/slop")
@Produces(MediaType.APPLICATION_JSON)
class AdminResource {

    private val element: Element = ElementSupplier.getElementLocal(AdminResource::class.java).get()

    private val engine: SlopEngine by lazy { element.getServiceLocator().getInstance(SlopEngine::class.java) }
    private val slopDao: SlopDao by lazy { element.getServiceLocator().getInstance(SlopDao::class.java) }
    private val auditDao: SlopAuditDao by lazy { element.getServiceLocator().getInstance(SlopAuditDao::class.java) }
    private val auth: SlopAuth by lazy { element.getServiceLocator().getInstance(SlopAuth::class.java) }
    private val assetAdmin: SlopAssetAdmin by lazy { element.getServiceLocator().getInstance(SlopAssetAdmin::class.java) }
    private val auditor: SlopAuditor by lazy { element.getServiceLocator().getInstance(SlopAuditor::class.java) }

    @POST
    @Path("/impersonate")
    @Consumes(MediaType.APPLICATION_JSON)
    fun impersonate(@HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?, body: Map<String, Any?>?): Response {
        val user = auth.requireSuperuser()
        val streamer = (body?.get("streamer")?.toString() ?: actingAs ?: "")
            .trim()
            .lowercase()
        if (streamer.isEmpty()) {
            throw WebApplicationException(Response.status(400).entity(mapOf("error" to "streamer required")).build())
        }
        audit(
            actor = user.name,
            actingAs = streamer,
            action = "impersonate",
            target = streamer,
            payload = body,
        )
        return Response.ok(mapOf("ok" to true, "actingAs" to streamer)).build()
    }

    @POST
    @Path("/impersonate/exit")
    fun impersonateExit(@HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?): Response {
        val user = auth.requireSuperuser()
        audit(
            actor = user.name,
            actingAs = actingAs?.trim()?.lowercase(),
            action = "impersonate-exit",
            target = actingAs?.trim()?.lowercase(),
            payload = null,
        )
        return Response.ok(mapOf("ok" to true)).build()
    }

    @GET
    @Path("/queues")
    fun queues(@HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?): Response {
        auth.requireSuperuser()
        return Response.ok(engine.snapshotMap()["queue"] ?: emptyList<Any>()).build()
    }

    @GET
    @Path("/registry")
    fun registry(@HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?): Response {
        auth.requireSuperuser()
        return Response.ok(engine.snapshotMap()["registered"] ?: emptyList<Any>()).build()
    }

    @POST
    @Path("/{name}/suspend")
    fun suspend(@PathParam("name") name: String, @HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?): Response =
        moderationAction(name, "suspend", actingAs) { engine.suspend(it, true) }

    @POST
    @Path("/{name}/reinstate")
    fun reinstate(@PathParam("name") name: String, @HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?): Response =
        moderationAction(name, "reinstate", actingAs) { engine.suspend(it, false) }

    @POST
    @Path("/{name}/remove")
    fun remove(@PathParam("name") name: String, @HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?): Response =
        moderationAction(name, "remove", actingAs) { engine.deleteSlop(it) }

    @GET
    @Path("/moderation-queue")
    fun moderationQueue(@HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?): Response {
        auth.requireSuperuser()
        val pending = slopDao.list().filter { it.moderation == dev.slopbot.element.model.Slop.MODERATION_PENDING }
        return Response.ok(pending).build()
    }

    @GET
    @Path("/audit")
    fun audit(@HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?): Response {
        auth.requireSuperuser()
        return Response.ok(auditDao.list()).build()
    }

    /**
     * The content-attribution repair: reassigns a slop's owner (the tenant
     * streamer) and/or creator (the chat user who prompted) — body
     * `{owner?, creator?}` (creator: `{id?, login, displayName}`). Audited
     * with the payload digest.
     */
    @POST
    @Path("/{name}/reassign")
    @Consumes(MediaType.APPLICATION_JSON)
    fun reassign(
        @PathParam("name") name: String,
        @HeaderParam(SlopAuth.ACTING_IDENTITY_HEADER) actingAs: String?,
        body: Map<String, Any?>?,
    ): Response {
        val user = auth.requireSuperuser()
        val ownerLogin = body?.get("owner")?.toString()?.takeIf { it.isNotBlank() }
        @Suppress("UNCHECKED_CAST")
        val creator = (body?.get("creator") as? Map<String, Any?>)?.takeIf { it.isNotEmpty() }?.let {
            Participant(
                id = it["id"]?.toString(),
                login = it["login"]?.toString(),
                displayName = it["displayName"]?.toString(),
            )
        }
        if (ownerLogin == null && creator == null) {
            throw WebApplicationException(
                Response.status(400).entity(mapOf("error" to "nothing to reassign (owner?, creator?)")).build(),
            )
        }
        val updated = try {
            assetAdmin.reassign(name, ownerLogin, creator, user.name, actingAs?.trim()?.lowercase(), body)
        } catch (e: NoSuchElementException) {
            throw WebApplicationException(
                Response.status(404).entity(mapOf("error" to (e.message ?: "not found"))).build(),
            )
        }
        return Response.ok(
            mapOf(
                "ok" to true,
                "name" to updated.name,
                "owner" to updated.streamer,
                "creator" to updated.creator,
            ),
        ).build()
    }

    private fun moderationAction(
        name: String,
        action: String,
        actingAs: String?,
        actionFn: (String) -> Boolean,
    ): Response {
        val user = auth.requireSuperuser()
        val ok = actionFn(name)
        audit(
            actor = user.name,
            actingAs = actingAs?.trim()?.lowercase() ?: engine.registryEntry(name)?.streamer?.login?.lowercase(),
            action = action,
            target = name,
            payload = null,
        )
        return Response.ok(mapOf("ok" to ok)).build()
    }

    /** The append-only audit row (the shared [SlopAuditor]). */
    private fun audit(actor: String?, actingAs: String?, action: String, target: String?, payload: Map<String, Any?>?) {
        auditor.record(actor, actingAs, action, target, payload)
    }
}
