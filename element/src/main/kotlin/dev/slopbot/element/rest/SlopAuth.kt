package dev.slopbot.element.rest

import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.auth.SessionService
import dev.getelements.elements.sdk.service.user.UserService
import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response

/**
 * The session-auth gate for the element's REST surface. Session auth = the
 * Elements session (Authorization bearer / session forms per the manual);
 * the platform applies the filter when `dev.getelements.elements.auth.enabled`
 * is set (it is — see [SlopApplication]).
 *
 * Roles:
 * - `streamer` — the tenant owner (the Elements user whose login IS the Twitch
 *   channel; login == channel);
 * - `viewer` — an authenticated USER who is not the tenant owner;
 * - `SUPERUSER` — an Elements user with SUPERUSER status.
 *
 * Management routes: 401 anonymous / 403 viewer / 200 streamer-or-superuser.
 * Overlay/viewer routes stay OPEN (no session at all).
 */
class SlopAuth @Inject constructor(
    private val userService: UserService,
    private val sessionService: SessionService,
) {

    /** The acting-identity header — impersonation rides here for superusers. */
    companion object {
        const val ACTING_IDENTITY_HEADER = "X-Slop-Acting-As"

        fun unauthorized(): WebApplicationException =
            WebApplicationException(Response.status(401).entity(mapOf("error" to "authentication required")).build())

        fun forbidden(): WebApplicationException =
            WebApplicationException(Response.status(403).entity(mapOf("error" to "forbidden")).build())
    }

    /** The current session user, or null when anonymous. */
    fun currentUser(): User? = try {
        val user = userService.getCurrentUser()
        if (user.level == User.Level.UNPRIVILEGED) null else user
    } catch (e: Exception) {
        null
    }

    /**
     * Cookie-aware variant: the header session first (the platform's own
     * mechanism), then the adopted `slop_session` cookie — the dashboard's
     * browser flow authenticates by cookie, and the servlet auth chain only
     * reads headers (HttpServletSessionIdAuthenticationFilter), so the
     * cookie is resolved here, validated through the platform's
     * [SessionService] (fail closed on anything unknown/expired).
     */
    fun currentUser(request: jakarta.servlet.http.HttpServletRequest?): User? {
        currentUser()?.let { return it }
        if (request == null) return null
        val cookie = request.cookies?.firstOrNull { it.name == SlopSessionResource.SESSION_COOKIE }?.value
            ?: return null
        return try {
            val session = sessionService.checkAndRefreshSessionIfNecessary(cookie)
            session.user
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validates a session secret for adoption (the cookie flow). Returns
     * the validated user, or null when the secret is blank/unknown/expired
     * (fail closed — the caller answers 401). The validation refreshes the
     * session expiry, which is exactly what a login should do.
     */
    fun adoptSession(secret: String?): Pair<User, String>? {
        if (secret.isNullOrBlank()) return null
        return try {
            val session = sessionService.checkAndRefreshSessionIfNecessary(secret)
            val user = session.user ?: return null
            user to secret
        } catch (e: Exception) {
            null
        }
    }

    fun isSuperuser(user: User?): Boolean = user != null && user.level == User.Level.SUPERUSER

    /** True when the user is the tenant owner for the given streamer login. */
    fun isStreamerFor(user: User?, streamerLogin: String?): Boolean =
        user != null && streamerLogin != null && user.name != null &&
            user.name.equals(streamerLogin, ignoreCase = true)

    /** 401 when anonymous. */
    fun requireAuthenticated(): User {
        val user = currentUser() ?: throw unauthorized()
        return user
    }

    /**
     * The management gate — 401 anonymous / 403 viewer / 200
     * streamer-or-superuser. `streamerLogin` scopes to a tenant when known
     * (e.g. the slop's own streamer); null accepts any tenant streamer.
     */
    fun requireStreamerOrSuperuser(streamerLogin: String? = null): User {
        val user = currentUser() ?: throw unauthorized()
        if (isSuperuser(user)) return user
        if (isStreamerFor(user, streamerLogin) || (streamerLogin == null && isStreamer(user))) return user
        throw forbidden()
    }

    /** The SUPERUSER gate. */
    fun requireSuperuser(): User {
        val user = currentUser() ?: throw unauthorized()
        if (!isSuperuser(user)) throw forbidden()
        return user
    }

    /**
     * A "streamer" for tenant-unscoped management routes: any authenticated
     * USER (the element's tenancy is self-serve — every Twitch user is a
     * tenant; their data access is scoped by the DAO, not by this gate).
     */
    fun isStreamer(user: User?): Boolean = user != null && user.level == User.Level.USER

    /**
     * The acting identity for a superuser request (the impersonated streamer),
     * validated against the session: only a SUPERUSER may act as another.
     */
    fun actingIdentity(headerValue: String?): String? {
        if (headerValue.isNullOrEmpty()) return null
        requireSuperuser()
        return headerValue.trim().lowercase()
    }
}
