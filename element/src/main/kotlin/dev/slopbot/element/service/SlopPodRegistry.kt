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
    private val twitchAuthDao: dev.slopbot.element.dao.SlopTwitchAuthDao,
) {

    private fun now(): Long = System.currentTimeMillis()

    /**
     * The Conductor dispatch (designer ruling: NO service credential — the
     * provider runs Fabric8 with the pod's identity). The kubernetes
     * provider element is deployed alongside and exports
     * OrchestrationService; we resolve it through the element registry and
     * execute a JobRequest against the `slopbot-pod` profile directly.
     */
    private fun dispatchViaConductor(streamer: String, token: String, mode: String): Any? {
        // The streamer's bot authorization (if any) — the Twitch credentials the
        // worker needs for chat write + EventSub. Missing = anonymous chat
        // capture, EventSub dormant (the reference's graceful degradation).
        val twitchEnv = twitchAuthDao.findByStreamer(streamer)?.let { auth -> mapOf(
            "SLOP_TWITCH_USERNAME" to (auth.broadcasterLogin),
            "SLOP_TWITCH_OAUTH_TOKEN" to (auth.accessToken),
            "SLOP_TWITCH_BROADCASTER_ID" to (auth.broadcasterId),
            "SLOP_CHANNEL" to (auth.broadcasterLogin),
        )} ?: mapOf(
            "SLOP_CHANNEL" to streamer,
        )
        // The conductor provider is a SIBLING element. From this element's
        // own registry, find it by name and take its locator.
        val element = dev.getelements.elements.sdk.ElementSupplier
            .getElementLocal(SlopPodRegistry::class.java).get()
        val conductorElement = element.getElementRegistry()
            .find("dev.getelements.conductor.kubernetes")
            .findFirst()
            .orElseThrow { IllegalStateException("the conductor kubernetes provider is not deployed") }
        val orchestration = try {
            conductorElement.getServiceLocator()
                .getInstance(dev.getelements.conductor.service.OrchestrationService::class.java)
        } catch (e: Exception) {
            // surface the ROOT cause — Guice wraps the provider's failure and the
            // innermost error (the real fabric8 failure) hides in the cause chain
            var root: Throwable = e
            while (root.cause != null && root.cause != root) root = root.cause!!
            throw IllegalStateException("the conductor client failed to construct: ${root.javaClass.name}: ${root.message}", e)
        }
        // The profile ID is the SCOPED form — "$namespace:$templateName" — not the bare name.
        val profiles = orchestration.getAvailableProfiles()
        val profile = profiles.firstOrNull { it.id.endsWith(":slopbot-pod") || it.id == "slopbot-pod" }
            ?: return mapOf(
                "error" to "no such profile: slopbot-pod (is the PodTemplate labeled with the provider's job set?)",
                "available" to profiles.map { it.id },
            )
        val execution = orchestration.execute(
            dev.getelements.conductor.JobRequest(
                profile = profile,
                environment = twitchEnv + mapOf(
                    "SLOP_ELM_URL" to elmUrl(),
                    "SLOP_ELEMENTS_URL" to elmUrl(),
                    "SLOP_MCP_URL" to (elmUrl() + "/slop/api/mcp"),
                    "SLOP_PROXY_AUDIENCE" to "slopbot-test",
                    "SLOP_RELAY_PORT" to "8090",
                    "SLOP_POD_TOKEN" to token,
                    "SLOP_OIDC_TOKEN" to token,
                    "SLOP_STREAMER" to streamer,
                    "SLOP_POD_STREAMER" to streamer,
                    "SLOP_POD_MODE" to mode,
                ),
            )
        )
        return mapOf("executionId" to execution.id, "status" to execution.status.toString())
    }

    /** The ELM's own REST root, for the pod's lease calls. */
    private fun elmUrl(): String =
        dev.slopbot.element.AttrUnwrap.unwrap(
            dev.getelements.elements.sdk.ElementSupplier
                .getElementLocal(SlopPodRegistry::class.java).get()
                .getElementRecord().attributes()
                .getAttributeOptional("dev.slopbot.element.elm.url").orElse(null)
        ) ?: "http://localhost:8080"

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
        dispatchFn: ((streamer: String, token: String, mode: String) -> Any?)? = ::dispatchViaConductor,
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
