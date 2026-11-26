package dev.slopbot.element.rest

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.getelements.elements.sdk.model.user.User
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * The session surface — `/slop/api/me` and `/slop/api/session/adopt`.
 *
 * Login runs entirely on the CORE (`POST /oidc/session` → authorizeUrl →
 * `GET /oidc/session/{id}` → COMPLETE with the Elements session). The
 * dashboard then ADOPTS that session into an HttpOnly cookie (JS-set
 * cookies can't be HttpOnly); every later `/slop/api` call authenticates
 * by cookie — the platform's session filter resolves it (verified live:
 * `GET /user/me` answers 200 with the cookie, no header needed).
 *
 * Same resolution pattern as SlopResource: Jersey instantiates the class,
 * services come through the element's own ServiceLocator.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
class SlopSessionResource {

    private val element: Element = ElementSupplier.getElementLocal(SlopSessionResource::class.java).get()

    private val auth: SlopAuth by lazy { element.getServiceLocator().getInstance(SlopAuth::class.java) }

    companion object {
        const val SESSION_COOKIE = "slop_session"
    }

    /** The current session user + their Twitch profile (401 when anonymous). */
    @GET
    @Path("me")
    fun me(@jakarta.ws.rs.core.Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val user: User = auth.currentUser(request) ?: throw SlopAuth.unauthorized()
        val twitch = user.linkedAccountProfiles?.get("twitch") ?: emptyMap()
        return Response.ok(
            mapOf(
                "name" to user.name,
                "displayName" to user.displayName,
                "superuser" to auth.isSuperuser(user),
                "twitch" to twitch,
            )
        ).build()
    }

    /** The adopt request — a concrete type: Jersey's generic `Map<String,?>`
     *  body binding is unreliable on the element surface (the "not resolvable
     *  to a concrete type" warnings), so the session secret rides a DTO. */
    class AdoptRequest {
        var sessionSecret: String? = null
    }

    /**
     * Adopts an Elements session secret into the HttpOnly session cookie.
     * Validates first — an unknown/expired secret is a 401 and sets
     * nothing (fail closed). Also refreshes the session expiry (the
     * validator's contract), which is exactly what a login should do.
     */
    @POST
    @Path("session/adopt")
    fun adopt(request: AdoptRequest): Response {
        val (user, secret) = auth.adoptSession(request.sessionSecret) ?: throw SlopAuth.unauthorized()
        // the raw header is the version-proof spelling of NewCookie+SameSite
        return Response.ok(
            mapOf("ok" to true, "user" to user.name)
        ).header(
            "Set-Cookie",
            "$SESSION_COOKIE=$secret; Path=/; Secure; HttpOnly; SameSite=Lax"
        ).build()
    }

    /** Clears the session cookie (logout). */
    @POST
    @Path("session/logout")
    fun logout(): Response = Response.ok(mapOf("ok" to true)).header(
        "Set-Cookie",
        "$SESSION_COOKIE=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0"
    ).build()
}
