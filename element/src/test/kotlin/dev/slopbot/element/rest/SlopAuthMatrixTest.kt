package dev.slopbot.element.rest

import dev.getelements.elements.sdk.model.user.User
import dev.getelements.elements.sdk.service.auth.SessionService
import dev.getelements.elements.sdk.service.user.UserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import jakarta.ws.rs.WebApplicationException

/**
 * The auth matrix (unit level): every gate's 401/403/200 mapping against the
 * Elements user levels. The full route × role HTTP matrix exercises these
 * gates on every resource method; the overlay routes never touch [SlopAuth].
 */
class SlopAuthMatrixTest {

    private class FakeUserService(private val user: User?) : UserService {
        override fun getCurrentUser(): User = user ?: throw IllegalStateException("no session")
        override fun getUser(userId: String): User = throw UnsupportedOperationException()
        override fun getUsers(offset: Int, count: Int): dev.getelements.elements.sdk.model.Pagination<User> =
            throw UnsupportedOperationException()
        override fun getUsers(offset: Int, count: Int, search: String): dev.getelements.elements.sdk.model.Pagination<User> =
            throw UnsupportedOperationException()
        override fun createUser(userCreateRequest: dev.getelements.elements.sdk.model.user.UserCreateRequest): dev.getelements.elements.sdk.model.user.UserCreateResponse =
            throw UnsupportedOperationException()
        override fun updateUser(userId: String, userUpdateRequest: dev.getelements.elements.sdk.model.user.UserUpdateRequest): User =
            throw UnsupportedOperationException()
        override fun updateUserPassword(userId: String, userUpdatePasswordRequest: dev.getelements.elements.sdk.model.user.UserUpdatePasswordRequest): dev.getelements.elements.sdk.model.session.SessionCreation =
            throw UnsupportedOperationException()
        override fun deleteUser(userId: String) = throw UnsupportedOperationException()
    }

    private fun user(level: User.Level, name: String = "someone"): User {
        val u = User()
        u.level = level
        u.name = name
        return u
    }

    private fun auth(current: User?): SlopAuth = SlopAuth(FakeUserService(current), ThrowingSessionService())

    /** A SessionService stub that fails closed — the matrix tests never adopt cookies. */
    private class ThrowingSessionService : SessionService {
        override fun checkAndRefreshSessionIfNecessary(sessionSecret: String?) =
            throw UnsupportedOperationException("no cookie sessions in the auth matrix")
        override fun blacklistSession(sessionSecret: String?) =
            throw UnsupportedOperationException()
        // remaining methods are platform-only — unimplemented is fine for the matrix
    }

    @Test
    fun `anonymous is 401 on the management gates`() {
        val a = auth(null)
        val e401 = assertThrows<WebApplicationException> { a.requireAuthenticated() }
        assertEquals(401, e401.response.status)
        assertEquals(401, assertThrows<WebApplicationException> { a.requireStreamerOrSuperuser() }.response.status)
        assertEquals(401, assertThrows<WebApplicationException> { a.requireStreamerOrSuperuser(null, "savorysailor") }.response.status)
        assertEquals(401, assertThrows<WebApplicationException> { a.requireSuperuser() }.response.status)
    }

    @Test
    fun `a viewer (USER, not the tenant owner) is 403 on tenant-scoped management`() {
        val a = auth(user(User.Level.USER, name = "viewer"))
        // Tenant-scoped management: the viewer is not the streamer → 403.
        assertEquals(403, assertThrows<WebApplicationException> { a.requireStreamerOrSuperuser(null, "savorysailor") }.response.status)
        // Tenant-unscoped management: the element's tenancy is self-serve —
        // every Twitch login is a streamer identity operating their own
        // channel, so an authenticated USER passes (their data access is
        // scoped by the DAO, not this gate).
        assertEquals("viewer", a.requireStreamerOrSuperuser(null).name)
        assertEquals(403, assertThrows<WebApplicationException> { a.requireSuperuser() }.response.status)
        // But requireAuthenticated passes (a viewer IS authenticated).
        assertEquals("viewer", a.requireAuthenticated().name)
    }

    @Test
    fun `the tenant streamer passes the management gate`() {
        val a = auth(user(User.Level.USER, name = "savorysailor"))
        assertEquals("savorysailor", a.requireStreamerOrSuperuser(null, "savorysailor").name)
        // Case-insensitive login == channel matching.
        assertEquals("savorysailor", a.requireStreamerOrSuperuser(null, "SavorySailor").name)
    }

    @Test
    fun `a superuser passes every gate`() {
        val a = auth(user(User.Level.SUPERUSER, name = "root"))
        assertEquals("root", a.requireStreamerOrSuperuser(null, "savorysailor").name)
        assertEquals("root", a.requireSuperuser().name)
    }

    @Test
    fun `the acting-identity header only validates for superusers`() {
        // Presenting the header without a SUPERUSER session throws (401 anon / 403 viewer).
        assertEquals(401, assertThrows<WebApplicationException> { auth(null).actingIdentity("someone") }.response.status)
        assertEquals(403, assertThrows<WebApplicationException> { auth(user(User.Level.USER)).actingIdentity("someone") }.response.status)
        assertEquals("someone", auth(user(User.Level.SUPERUSER)).actingIdentity("Someone"))
        assertNull(auth(user(User.Level.SUPERUSER)).actingIdentity(null))
        assertNull(auth(user(User.Level.SUPERUSER)).actingIdentity(""))
    }

    @Test
    fun `isStreamerFor is a case-insensitive login match`() {
        val a = auth(user(User.Level.USER, name = "savorysailor"))
        assertTrue(a.isStreamerFor(a.currentUser(), "savorysailor"))
        assertTrue(a.isStreamerFor(a.currentUser(), "SavorySailor"))
        assertFalse(a.isStreamerFor(a.currentUser(), "someoneelse"))
        assertFalse(a.isStreamerFor(null, "savorysailor"))
    }

    @Test
    fun `an unprivileged session user counts as anonymous`() {
        val a = auth(user(User.Level.UNPRIVILEGED))
        assertNull(a.currentUser())
        assertEquals(401, assertThrows<WebApplicationException> { a.requireAuthenticated() }.response.status)
    }
}
