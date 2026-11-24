package dev.slopbot.element.rest

import dev.getelements.elements.sdk.model.user.User
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
