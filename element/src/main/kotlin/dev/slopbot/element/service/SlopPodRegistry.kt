package dev.slopbot.element.service

import dev.slopbot.element.dao.InMemorySlopPodLeaseDao
import dev.slopbot.element.dao.SlopPodLeaseDao
import dev.slopbot.element.model.PodLease
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Raised when a registration hits a LIVE lease held by another pod. */
class PodEvictedException(val streamer: String, val holder: PodLease) :
    Exception("a live pod already serves $streamer")

/**
 * The one-pod-per-streamer arbiter (Pod Boot Mechanics §lease + §boot).
 *
 * - `dispatch` — mints the per-pod token, lays a DISPATCHED lease, and
 *   calls the Conductor admin API when configured (fail-open: an
   * unconfigured Conductor logs and leaves the build waiting).
 * - `register` — the pod claims the DISPATCHED lease (presenting the
 *   token it was dispatched with) or takes over a STALE one; a LIVE lease
 *   held by another pod raises [PodEvictedException] — the caller answers
 *   409 and the worker self-evicts (exit 0).
 * - `heartbeat` — refreshes; superseded pods are told to stand down.
 * - `release` — the clean exit path.
 */
@Singleton
class SlopPodRegistry @Inject constructor(
    private val leaseDao: SlopPodLeaseDao,
) {

    private fun now(): Long = System.currentTimeMillis()

    /**
     * The Conductor dispatch call, built from the element's attributes
     * (conductor.url / credential / profile / provider). Returns null when
     * unconfigured — the dispatch lays the lease only and the build waits
     * (fail-open, logged). Every dispatch path uses this: the engine hooks
     * and any future caller carry the real POST /jobs automatically.
     */
    private fun conductorFn(): ((String, String, String) -> Any?)? {
        val element = dev.getelements.elements.sdk.ElementSupplier
            .getElementLocal(SlopPodRegistry::class.java).get()
        val attrs = element.getElementRecord().attributes()
        fun attr(name: String): String? =
            attrs.getAttributeOptional(name)?.let { opt ->
                val raw = opt.toString()
                val unwrapped = Regex("^Optional\\[(.*)\\]$").find(raw)?.groupValues?.get(1) ?: raw
                unwrapped.takeIf { it.isNotEmpty() }
            }
        val url = attr("dev.slopbot.element.conductor.url") ?: return null
        val credential = attr("dev.slopbot.element.conductor.credential") ?: return null
        val profile = attr("dev.slopbot.element.conductor.profile") ?: "slopbot-pod"
        val provider = attr("dev.slopbot.element.conductor.provider") ?: "dev.getelements.conductor.kubernetes"
        return { streamer, token, mode ->
            try {
                val body = mapOf(
                    "element" to provider,
                    "profileId" to profile,
                    "environment" to mapOf(
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

    /** The per-pod token's hash — the raw token is returned ONCE at dispatch. */
    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun token(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** A lease whose heartbeat is fresh enough to count as LIVE. */
    fun isLive(lease: PodLease, heartbeatSeconds: Long = HEARTBEAT_SECONDS_DEFAULT): Boolean =
        lease.status == PodLease.STATUS_ACTIVE && (now() - lease.heartbeatAt) < heartbeatSeconds * 1000 * 3

    /**
     * Dispatch a pod for the streamer (Phase C item 4 — the trigger).
     * Idempotent against the lease: a live pod or a fresh DISPATCHED lease
     * short-circuits. Returns the dispatch outcome; `podToken` is present
     * only on a FRESH dispatch (the caller delivers it to the pod via the
     * Conductor environment — or, superuser-only, reads it for testing).
     */
    fun dispatch(
        streamer: String,
        mode: String,
        reason: String,
        dispatchFn: ((streamer: String, token: String, mode: String) -> Any?)? = conductorFn(),
    ): Map<String, Any?> {
        val s = streamer.lowercase()
        leaseDao.liveLease(s)?.let { lease ->
            if (isLive(lease)) return mapOf("dispatched" to false, "reason" to "a live pod already serves $s")
            if (lease.status == PodLease.STATUS_DISPATCHED && (now() - lease.claimedAt) < REGISTRATION_WINDOW_MS)
                return mapOf("dispatched" to false, "reason" to "a dispatch is already pending for $s")
            // stale — fall through to a fresh dispatch (takeover on register)
        }
        val token = token()
        val lease = PodLease()
        lease.streamer = s
        lease.podInstanceId = "lease-" + now().toString(36)
        lease.mode = mode
        lease.status = PodLease.STATUS_DISPATCHED
        lease.tokenHash = hash(token)
        lease.claimedAt = now()
        lease.heartbeatAt = now()
        leaseDao.put(lease)
        val conductor = if (dispatchFn != null) dispatchFn(s, token, mode) else null
        return mapOf(
            "dispatched" to true,
            "streamer" to s,
            "mode" to mode,
            "reason" to reason,
            "podToken" to token,
            "conductor" to conductor,
        )
    }

    /**
     * The pod's registration (Pod Boot Mechanics §boot step 3-4): claim the
     * DISPATCHED lease by presenting its token, or take over a STALE one.
     * A live lease held by another pod → [PodEvictedException].
     */
    fun register(streamer: String, podInstanceId: String, address: String, mode: String, token: String): PodLease {
        val s = streamer.lowercase()
        val lease = leaseDao.liveLease(s)
            ?: throw PodEvictedException(s, PodLease().apply { this.streamer = s })
        if (lease.tokenHash != hash(token)) throw PodEvictedException(s, lease)
        if (isLive(lease) && lease.status == PodLease.STATUS_ACTIVE && lease.podInstanceId != podInstanceId)
            throw PodEvictedException(s, lease)
        lease.podInstanceId = podInstanceId
        lease.address = address
        lease.mode = mode
        lease.status = PodLease.STATUS_ACTIVE
        lease.heartbeatAt = now()
        return leaseDao.put(lease)
    }

    /** Heartbeat: the pod proves it lives. Returns null when superseded (evict). */
    fun heartbeat(streamer: String, podInstanceId: String): PodLease? {
        val lease = leaseDao.liveLease(streamer.lowercase()) ?: return null
        if (lease.podInstanceId != podInstanceId) return null
        lease.heartbeatAt = now()
        return leaseDao.put(lease)
    }

    /** The clean exit: release the lease (only the holder may). */
    fun release(streamer: String, podInstanceId: String): Boolean {
        val lease = leaseDao.liveLease(streamer.lowercase()) ?: return false
        if (lease.podInstanceId != podInstanceId) return false
        lease.status = PodLease.STATUS_RELEASED
        leaseDao.put(lease)
        return true
    }

    fun leaseOf(streamer: String): PodLease? = leaseDao.liveLease(streamer.lowercase())

    companion object {
        /** The registration window (a DISPATCHED lease blocks re-dispatch this long). */
        const val REGISTRATION_WINDOW_MS: Long = 90_000
        /** The default heartbeat freshness (3 missed heartbeats = stale). */
        const val HEARTBEAT_SECONDS_DEFAULT: Long = 30
    }
}
