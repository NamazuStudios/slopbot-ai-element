package dev.slopbot.element.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The OIDC bearer validation middleware (the MCP transport's auth): bearer
 * tokens minted by the bound Elements instance, validated per call —
 * signature (RS256 via cached JWKS), issuer, audience, expiry. The token is
 * **streamer-scoped**: that streamer, and only that streamer, runs that pod.
 *
 * 401 missing/expired/foreign; 403 wrong scope. The issuer URL is injected
 * via an element attribute (`dev.slopbot.element.oidc.issuer`); when unset
 * every token fails closed (401) — real OIDC wiring is a platform concern.
 */
class OidcValidator(
    private var issuer: String?,
    private val audience: String = DEFAULT_AUDIENCE,
    private val requiredScope: String = DEFAULT_SCOPE,
    private val jwksUriOverride: String? = null,
    private val fetcher: (String) -> String? = { url -> httpGet(url) },
) {

    data class Jwk(val kid: String, val n: BigInteger, val e: BigInteger)

    data class Verified(val subject: String, val scopes: Set<String>)

    private val keys = ConcurrentHashMap<String, Jwk>()
    @Volatile private var keysFetchedAt = 0L
    @Volatile private var resolvedJwksUri: String? = null

    private val mapper = ObjectMapper()

    sealed class Result {
        data class Ok(val verified: Verified) : Result()
        data class Fail(val status: Int, val reason: String) : Result()
    }

    fun withIssuer(issuer: String?): OidcValidator {
        this.issuer = issuer
        return this
    }

    /**
     * Validates one bearer token. Fails closed: any structural problem,
     * signature mismatch, expiry, foreign issuer, or missing scope rejects.
     */
    fun validate(token: String?): Result {
        if (token.isNullOrEmpty()) return Result.Fail(401, "authentication required")
        val parts = token.split(".")
        if (parts.size != 3) return Result.Fail(401, "malformed token")
        val header: Map<*, *> = try {
            mapper.readValue(b64urlDecodeToString(parts[0]), Map::class.java)
        } catch (e: Exception) {
            return Result.Fail(401, "malformed token")
        }
        val payload: Map<*, *> = try {
            mapper.readValue(b64urlDecodeToString(parts[1]), Map::class.java)
        } catch (e: Exception) {
            return Result.Fail(401, "malformed token")
        }
        if (header["alg"] != "RS256") return Result.Fail(401, "unsupported algorithm")
        val kid = header["kid"]?.toString()
        if (kid.isNullOrEmpty()) return Result.Fail(401, "missing key id")

        // Signature first — an unknown kid forces one JWKS refresh (a rotated key).
        var jwk = keys[kid]
        if (jwk == null || keysStale()) {
            refreshKeys()
            jwk = keys[kid]
        }
        if (jwk == null) return Result.Fail(401, "unknown signing key")
        if (!verifySignature(parts[0], parts[1], parts[2], jwk)) {
            return Result.Fail(401, "signature verification failed")
        }

        // Expiry.
        val exp = asLong(payload["exp"])
        if (exp != null && exp * 1000 < System.currentTimeMillis()) {
            return Result.Fail(401, "token expired")
        }
        val nbf = asLong(payload["nbf"])
        if (nbf != null && nbf * 1000 > System.currentTimeMillis() + 60_000) {
            return Result.Fail(401, "token not yet valid")
        }

        // Issuer (foreign issuer → 401) and audience.
        val iss = payload["iss"]?.toString()
        if (issuer != null && (iss == null || !iss.trimEnd('/').equals(issuer!!.trimEnd('/'), ignoreCase = true))) {
            return Result.Fail(401, "foreign issuer")
        }
        val aud = payload["aud"]
        val audiences: List<String> = when (aud) {
            is List<*> -> aud.map { it?.toString() ?: "" }
            is String -> listOf(aud)
            else -> emptyList()
        }
        if (audiences.isNotEmpty() && !audiences.contains(audience)) {
            return Result.Fail(401, "wrong audience")
        }

        // Scope (403 wrong scope).
        val scopes = when (val scope = payload["scope"] ?: payload["scp"]) {
            is List<*> -> scope.map { it?.toString() ?: "" }.toSet()
            is String -> scope.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
            else -> emptySet()
        }
        if (!scopes.contains(requiredScope)) {
            return Result.Fail(403, "missing scope: $requiredScope")
        }

        // The streamer identity: the `streamer` claim when present, else `sub`.
        val subject = (payload["streamer"] ?: payload["sub"])?.toString()
        if (subject.isNullOrEmpty()) return Result.Fail(401, "token carries no subject")
        return Result.Ok(Verified(subject = subject, scopes = scopes))
    }

    // --- internals ------------------------------------------------------------

    private fun keysStale(): Boolean = System.currentTimeMillis() - keysFetchedAt > JWKS_TTL_MS

    /** Fetches (and caches) the issuer's JWKS. Unknown kid forces a refresh upstream. */
    @Synchronized
    fun refreshKeys(): Boolean {
        val uri = jwksUriOverride ?: resolvedJwksUri ?: discoverJwksUri() ?: return false
        val body = fetcher(uri) ?: return false
        return try {
            val root = mapper.readValue(body, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val jwks = (root["keys"] as? List<Map<*, *>>) ?: return false
            var count = 0
            for (jwk in jwks) {
                val kty = jwk["kty"]?.toString() ?: continue
                if (kty != "RSA") continue
                val kid = jwk["kid"]?.toString() ?: continue
                val n = b64urlDecodeToBigInteger(jwk["n"]?.toString() ?: continue) ?: continue
                val e = b64urlDecodeToBigInteger(jwk["e"]?.toString() ?: "AQAB") ?: continue
                keys[kid] = Jwk(kid, n, e)
                count++
            }
            keysFetchedAt = System.currentTimeMillis()
            resolvedJwksUri = uri
            count > 0
        } catch (e: Exception) {
            false
        }
    }

    /** OIDC discovery (`<issuer>/.well-known/openid-configuration` → jwks_uri). */
    private fun discoverJwksUri(): String? {
        val iss = issuer ?: return null
        val base = iss.trimEnd('/')
        // Discovery first; the well-known JWKS path as the fallback.
        val discoveryBody = fetcher("$base/.well-known/openid-configuration")
        if (discoveryBody != null) {
            try {
                val root = mapper.readValue(discoveryBody, Map::class.java)
                val jwksUri = root["jwks_uri"]?.toString()
                if (!jwksUri.isNullOrEmpty()) return jwksUri
            } catch (e: Exception) {
                // fall through to the conventional path
            }
        }
        val conventional = "$base/.well-known/jwks.json"
        return if (fetcher(conventional) != null) conventional else null
    }

    private fun verifySignature(headerB64: String, payloadB64: String, sigB64: String, jwk: Jwk): Boolean =
        try {
            val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(jwk.n, jwk.e)) as RSAPublicKey
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            signature.update((headerB64 + "." + payloadB64).toByteArray(Charsets.US_ASCII))
            signature.verify(b64urlDecode(sigB64))
        } catch (e: Exception) {
            false
        }

    // --- static helpers ---------------------------------------------------------

    private fun asLong(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun b64urlDecode(input: String): ByteArray {
        val padded = input.replace('-', '+').replace('_', '/')
        val rem = padded.length % 4
        val withPadding = if (rem == 0) padded else padded + "=".repeat(4 - rem)
        return Base64.getDecoder().decode(withPadding)
    }

    private fun b64urlDecodeToString(input: String): String =
        String(b64urlDecode(input), Charsets.UTF_8)

    private fun b64urlDecodeToBigInteger(input: String): BigInteger? = try {
        BigInteger(1, b64urlDecode(input))
    } catch (e: Exception) {
        null
    }

    companion object {
        const val DEFAULT_AUDIENCE = "slopbot-ai"
        const val DEFAULT_SCOPE = "slop:mcp"
        const val JWKS_TTL_MS = 10 * 60 * 1000L

        private val client: HttpClient = HttpClient.newHttpClient()

        fun httpGet(url: String): String? = try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(10))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) response.body() else null
        } catch (e: Exception) {
            null
        }
    }
}
