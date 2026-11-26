package dev.slopbot.element.rest

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.element.dao.SlopTwitchAuthDao
import dev.slopbot.element.model.TwitchAuth
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The Twitch bot authorization (Pod Boot Mechanics §credentials): the
 * streamer authorizes the BOT's Twitch app once — chat:read, chat:edit,
 * EventSub channel scopes — and the ELM holds the token, injecting it at
 * dispatch (`SLOP_TWITCH_*`). No separate bot account: the bot posts as
 * the broadcaster via the Helix chat endpoint, per the reference design.
 *
 * Flow: `GET /slop/api/twitch/authorize` (session-authed) → the Twitch
 * consent screen → `GET /slop/api/twitch/callback?code=...` → token
 * exchange → stored per streamer → the dispatch reads it. A streamer
 * without one gets a pod with anonymous chat capture and dormant EventSub.
 */
@Path("/twitch")
@Produces(MediaType.APPLICATION_JSON)
class TwitchAuthorizeResource {

    private val element: Element = ElementSupplier.getElementLocal(TwitchAuthorizeResource::class.java).get()

    private val auth: SlopAuth by lazy { element.getServiceLocator().getInstance(SlopAuth::class.java) }
    private val twitchAuthDao: SlopTwitchAuthDao by lazy { element.getServiceLocator().getInstance(SlopTwitchAuthDao::class.java) }

    companion object {
        private val SCOPES = listOf(
            "channel:read:redemptions", "channel:manage:redemptions",
            "user:write:chat", "moderator:read:followers",
        )
        private const val AUTHORIZE_URL = "https://id.twitch.tv/oauth2/authorize"
        private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
        private const val ME_URL = "https://api.twitch.tv/helix/users"
    }

    /** The app credentials from the element attributes (the same Twitch app the login uses). */
    private fun providerCredentials(): AppCredentials? {
        val elementRecord = element.getElementRecord()
        val attrs = elementRecord.attributes()
        fun attr(name: String): String? = attrs.getAttributeOptional(name)?.let { raw ->
            // The attribute value deserializes as a Document({value: ...}) or an
            // Optional toString — unwrap both shapes to the plain string.
            val asString = raw.toString()
            Regex("^\\{\\s*\"value\"\\s*:\\s*\"(.*)\"\\s*\\}\\s*$").find(asString)?.groupValues?.get(1)
                ?: Regex("^Optional\\[(.*)\\]$").find(asString)?.groupValues?.get(1)
                ?: asString.takeIf { it != "Optional.empty" }
        }        
        val clientId = attr("dev.slopbot.element.twitch.app.client-id") ?: return null
        val clientSecret = attr("dev.slopbot.element.twitch.app.client-secret") ?: return null
        return AppCredentials(clientId, clientSecret)
    }

    /** The Twitch app credentials (the OIDC provider record's client id/secret pair). */
    private data class AppCredentials(val clientId: String, val clientSecret: String)

    /** Parse an application/x-www-form-urlencoded body. */
    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull { pair ->
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2) URLDecoder.decode(kv[0], Charsets.UTF_8) to URLDecoder.decode(kv[1], Charsets.UTF_8) else null
        }.toMap()

    /** Parse the Helix /users response's first entry. */
    private fun parseHelixUser(body: String): Map<String, String>? = try {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val root = mapper.readTree(body)
        val user = root.get("data").get(0)
        mapOf(
            "id" to (user.get("id")?.asText() ?: ""),
            "login" to (user.get("login")?.asText() ?: ""),
            "display_name" to (user.get("display_name")?.asText() ?: ""),
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Step 1: the session-authed authorize redirect. The `state` carries the
     * streamer login through the consent screen (CSRF-safe enough for the
     * one-time authorization; the callback re-authenticates the session).
     */
    @GET
    @Path("authorize")
    fun authorize(@jakarta.ws.rs.core.Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val user = auth.currentUser(request) ?: throw SlopAuth.unauthorized()
        val app = providerCredentials() ?: throw SlopAuth.unauthorized()
        val state = URLEncoder.encode(user.name, Charsets.UTF_8)
        val redirect = callbackUri()
        val target = "$AUTHORIZE_URL?client_id=${app.clientId}&redirect_uri=${URLEncoder.encode(redirect, Charsets.UTF_8)}" +
            "&response_type=code&scope=${URLEncoder.encode(SCOPES.joinToString(" "), Charsets.UTF_8)}&state=$state"
        return Response.temporaryRedirect(URI(target)).build()
    }

    /**
     * Step 2: the callback. Exchanges the code, resolves the broadcaster,
     * and stores the credentials per streamer. The browser lands back on
     * the dashboard with a success flag.
     */
    @GET
    @Path("callback")
    fun callback(@jakarta.ws.rs.core.Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val user = auth.currentUser(request) ?: throw SlopAuth.unauthorized()
        val code = request.getParameter("code") ?: return dashboardRedirect("failed")
        val app = providerCredentials() ?: throw SlopAuth.unauthorized()
        val form = mapOf(
            "client_id" to app.clientId,
            "client_secret" to app.clientSecret,
            "code" to code,
            "grant_type" to "authorization_code",
            "redirect_uri" to callbackUri(),
        )
        val client = HttpClient.newHttpClient()
        val tokenReq = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
            }))
            .build()
        val tokenResp = client.send(tokenReq, HttpResponse.BodyHandlers.ofString())
        if (tokenResp.statusCode() != 200) return dashboardRedirect("failed")
        val tokens = parseForm(tokenResp.body())
        val accessToken = tokens["access_token"] ?: return dashboardRedirect("failed")

        val meReq = HttpRequest.newBuilder()
            .uri(URI.create(ME_URL))
            .header("Authorization", "Bearer $accessToken")
            .header("Client-Id", app.clientId)
            .GET()
            .build()
        val meResp = client.send(meReq, HttpResponse.BodyHandlers.ofString())
        val me = parseHelixUser(meResp.body()) ?: return dashboardRedirect("failed")

        val authDoc = TwitchAuth()
        authDoc.streamer = user.name ?: ""
        authDoc.accessToken = accessToken
        authDoc.refreshToken = tokens["refresh_token"] ?: ""
        authDoc.scope = tokens["scope"] ?: SCOPES.joinToString(" ")
        authDoc.broadcasterId = me["id"] ?: ""
        authDoc.broadcasterLogin = me["login"] ?: ""
        authDoc.broadcasterDisplayName = me["display_name"] ?: ""
        authDoc.obtainedAt = System.currentTimeMillis()
        authDoc.expiresAt = System.currentTimeMillis() + (tokens["expires_in"]?.toLongOrNull() ?: 14400) * 1000
        twitchAuthDao.put(authDoc)

        return dashboardRedirect("authorized")
    }

    /** The stored credentials for the streamer (the dispatch reads these). */
    @GET
    @Path("status")
    fun status(@jakarta.ws.rs.core.Context request: jakarta.servlet.http.HttpServletRequest): Response {
        val user = auth.currentUser(request) ?: throw SlopAuth.unauthorized()
        val stored = twitchAuthDao.findByStreamer(user.name ?: "")
        val body = linkedMapOf<String, Any?>(
            "authorized" to (stored != null),
            "broadcasterLogin" to (stored?.broadcasterLogin ?: ""),
            "obtainedAt" to (stored?.obtainedAt ?: 0L),
        )
        return Response.ok(body).build()
    }

    private fun dashboardRedirect(flag: String): Response =
        Response.temporaryRedirect(URI("/dashboard.html?twitch=$flag")).build()

    /** The registered redirect: the ELM's public REST root + the callback path. */
    private fun callbackUri(): String {
        val elementRecord = element.getElementRecord()
        val raw = elementRecord.attributes().getAttributeOptional("dev.slopbot.element.elm.url")?.toString() ?: ""
        val base = Regex("^Optional\\[(.*)\\]$").find(raw)?.groupValues?.get(1) ?: raw
        val trimmed = (base.ifEmpty { "http://localhost:8080" }).replace("/+$", "")
        return "$trimmed/slop/api/twitch/callback"
    }
}
