package dev.slopbot.element.rest

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.element.service.SlopAssetAdmin
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.HttpHeaders

/**
 * The asset-admin surface — SUPERUSER only, every route under the element's
 * REST root with `slop` in the path (`/slop/api/admin/slop-assets/...`).
 * Upload is multipart (the file part + a few text fields) through
 * [SlopMultipart]; the bytes land in the Large Object store and the record in
 * `slopAssets`. Every mutating route writes an audit row via [SlopAssetAdmin].
 */
@Path("/admin/slop-assets")
@Produces(MediaType.APPLICATION_JSON)
class SlopAssetAdminResource {

    // The examples' pattern: resources resolve services through the element's
    // own service locator (the Jersey runtime doesn't inject Guice directly).
    private val element: Element = ElementSupplier.getElementLocal(SlopAssetAdminResource::class.java).get()

    private val admin: SlopAssetAdmin by lazy { element.getServiceLocator().getInstance(SlopAssetAdmin::class.java) }
    private val auth: SlopAuth by lazy { element.getServiceLocator().getInstance(SlopAuth::class.java) }

    /**
     * The asset list — filters: pool tag (`tag=shared`), per-slop (`slop=<name>`),
     * tenant (`tenant=<login>`), kind (`kind=audio|video`), each asset carrying
     * its referenced-by count (the same scan the bundler uses).
     */
    @GET
    fun list(
        @QueryParam("tag") tag: String?,
        @QueryParam("kind") kind: String?,
        @QueryParam("slop") slop: String?,
        @QueryParam("tenant") tenant: String?,
    ): Response {
        auth.requireSuperuser()
        val index = admin.sharedReferenceIndex()
        val assets = adminAssets()
            .asSequence()
            .filter { tag == null || it.tags.contains(tag) }
            .filter { kind == null || it.kind.equals(kind, ignoreCase = true) }
            .filter { slop == null || it.tags.contains(slop.trim().lowercase()) }
            .filter { tenant == null || it.tags.contains(tenant.trim().lowercase()) }
            .map { asset ->
                summary(asset).plus("referencedBy" to admin.referencedBy(asset, index).size)
            }
            .toList()
            .sortedByDescending { it["timestamp"] as? Long ?: 0 }
        return Response.ok(mapOf("assets" to assets, "total" to assets.size)).build()
    }

    /**
     * Upload — multipart (`file` part; optional `name`, `slop` owner, `tags`
     * echoed back) or a raw octet-stream body with the name in the query.
     * Validation: extension/mime allow-list, 8 MiB cap, name normalization,
     * uniqueness.
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadMultipart(
        @QueryParam("slop") slopOwner: String?,
        @Context headers: HttpHeaders,
        body: ByteArray,
    ): Response = upload(
        contentType = headers.getHeaderString("Content-Type"),
        slopOwner = slopOwner,
        body = body,
    )

    /** The raw-bytes upload path (the name comes from the query or the part). */
    @POST
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    fun uploadRaw(
        @QueryParam("name") name: String?,
        @QueryParam("slop") slopOwner: String?,
        body: ByteArray,
    ): Response = upload(
        contentType = null,
        slopOwner = slopOwner,
        suppliedName = name,
        body = body,
    )

    /**
     * Replace — a NEW LargeObject revision with existing slops pinned to the
     * old one; the replacement must match the asset's kind.
     */
    @PUT
    @Path("/{id}/bytes")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun replaceMultipart(
        @PathParam("id") id: String,
        @Context headers: HttpHeaders,
        body: ByteArray,
    ): Response = replace(id, headers.getHeaderString("Content-Type"), body)

    /** The raw-bytes replace path. */
    @PUT
    @Path("/{id}/bytes")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    fun replaceRaw(@PathParam("id") id: String, body: ByteArray): Response =
        replace(id, null, body)

    /** Retire — excluded from NEW bundles; existing bundles keep working. */
    @POST
    @Path("/{id}/retire")
    fun retire(@PathParam("id") id: String): Response = guard { superuser ->
        val asset = admin.retire(id, superuser.name)
        Response.ok(summary(asset)).build()
    }

    /** Unretire — back in the pool, eligible for new bundles again. */
    @POST
    @Path("/{id}/unretire")
    fun unretire(@PathParam("id") id: String): Response = guard { superuser ->
        val asset = admin.unretire(id, superuser.name)
        Response.ok(summary(asset)).build()
    }

    /** Delete — guarded: only unreferenced assets. */
    @DELETE
    @Path("/{id}")
    fun delete(@PathParam("id") id: String): Response = guard { superuser ->
        val asset = admin.delete(id, superuser.name)
        Response.ok(mapOf("ok" to true, "deleted" to asset.name)).build()
    }

    // --- internals -----------------------------------------------------------------

    private fun upload(contentType: String?, slopOwner: String?, suppliedName: String? = null, body: ByteArray): Response =
        guard { superuser ->
            val parts = try {
                if (contentType?.startsWith("multipart/") == true) {
                    SlopMultipart.parse(contentType, body)
                } else {
                    // A raw body: the bytes are the file, the name from the query.
                    listOf(SlopMultipart.Part("file", suppliedName, null, body))
                }
            } catch (e: IllegalArgumentException) {
                throw badRequest(e.message ?: "malformed multipart body")
            }
            val file = parts.firstOrNull { it.name == "file" || it.filename != null }
                ?: parts.firstOrNull()
                ?: throw badRequest("no file part")
            val name = parts.firstOrNull { it.name == "name" }?.bytes?.toString(Charsets.UTF_8)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: suppliedName
            val slop = parts.firstOrNull { it.name == "slop" }?.bytes?.toString(Charsets.UTF_8)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: slopOwner
            val asset = admin.upload(
                filename = file.filename,
                suppliedName = name,
                slopOwner = slop,
                suppliedMimeType = file.mimeType,
                bytes = file.bytes,
                actor = superuser.name,
            )
            Response.status(201).entity(summary(asset)).build()
        }

    private fun replace(id: String, contentType: String?, body: ByteArray): Response = guard { superuser ->
        val (filename, mimeType, bytes) = try {
            if (contentType?.startsWith("multipart/") == true) {
                val file = SlopMultipart.parse(contentType, body)
                    .firstOrNull { it.name == "file" || it.filename != null }
                    ?: throw badRequest("no file part")
                Triple(file.filename, file.mimeType, file.bytes)
            } else {
                Triple(null as String?, null as String?, body)
            }
        } catch (e: IllegalArgumentException) {
            throw badRequest(e.message ?: "malformed multipart body")
        }
        val asset = admin.replaceBytes(id, filename, mimeType, bytes, superuser.name)
        Response.ok(summary(asset)).build()
    }

    /** The admin gate + the shared 401/403/400/404/409 mapping. */
    private fun guard(block: (dev.getelements.elements.sdk.model.user.User) -> Response): Response = try {
        val user = auth.requireSuperuser()
        block(user)
    } catch (e: WebApplicationException) {
        throw e
    } catch (e: NoSuchElementException) {
        throw notFound(e.message ?: "not found")
    } catch (e: IllegalArgumentException) {
        throw badRequest(e.message ?: "bad request")
    } catch (e: IllegalStateException) {
        throw conflict(e.message ?: "conflict")
    }

    private fun adminAssets() = admin.list()

    private fun summary(asset: dev.slopbot.element.model.SlopAsset): Map<String, Any?> = mapOf(
        "id" to asset.id.toHexString(),
        "name" to asset.name,
        "displayName" to asset.displayName,
        "kind" to asset.kind,
        "mimeType" to asset.mimeType,
        "size" to asset.source?.let { ref ->
            // Size through the record's own store ref (0 when absent).
            admin.sizeOf(ref)
        },
        "tags" to asset.tags,
        "state" to asset.state,
        "revision" to (asset.revisions.size + 1),
        "timestamp" to asset.timestamp,
        "originalFilename" to asset.metadata.originalFilename,
    )

    companion object {
        private fun badRequest(message: String): WebApplicationException =
            WebApplicationException(Response.status(400).entity(mapOf("error" to message)).build())

        private fun notFound(message: String): WebApplicationException =
            WebApplicationException(Response.status(404).entity(mapOf("error" to message)).build())

        private fun conflict(message: String): WebApplicationException =
            WebApplicationException(Response.status(409).entity(mapOf("error" to message)).build())
    }
}
