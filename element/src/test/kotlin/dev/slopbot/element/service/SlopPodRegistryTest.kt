package dev.slopbot.element.service

import dev.slopbot.element.dao.InMemorySlopPodLeaseDao
import dev.slopbot.element.model.PodLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one-pod-per-streamer matrix (Pod Boot Mechanics §lease): dispatch →
 * register → live; a second pod EVICTS; stale takeover; heartbeat; release.
 */
class SlopPodRegistryTest {

    private val registry = SlopPodRegistry(InMemorySlopPodLeaseDao())

    @Test
    fun `dispatch lays a DISPATCHED lease and returns the token once`() {
        val r = registry.dispatch("savorysailor", "build", "test", dispatchFn = null)
        assertEquals(true, r["dispatched"])
        assertNotNull(r["podToken"])
        val lease = registry.leaseOf("savorysailor")!!
        assertEquals(PodLease.STATUS_DISPATCHED, lease.status)
    }

    @Test
    fun `a fresh pending dispatch is idempotent — no double pod`() {
        registry.dispatch("savorysailor", "build", "first", dispatchFn = null)
        val second = registry.dispatch("savorysailor", "build", "second", dispatchFn = null)
        assertEquals(false, second["dispatched"])
    }

    @Test
    fun `register claims the DISPATCHED lease by presenting its token`() {
        val r = registry.dispatch("savorysailor", "build", "test", dispatchFn = null)
        val token = r["podToken"] as String
        val lease = registry.register("savorysailor", "pod-1", "10.0.0.1:8090", "build", token)
        assertEquals(PodLease.STATUS_ACTIVE, lease.status)
        assertEquals("pod-1", lease.podInstanceId)
    }
    @Test
    fun `a wrong token is refused`() {
        registry.dispatch("savorysailor", "build", "test", dispatchFn = null)
        val e = assertThrows<PodEvictedException> {
            registry.register("savorysailor", "pod-1", "10.0.0.1:8090", "build", "wrong-token")
        }
        assertTrue(e.message!!.contains("savorysailor"))
    }

    @Test
    fun `two live pods — the second EVICTS (the invariant holds)`() {
        val r1 = registry.dispatch("savorysailor", "stream", "first", dispatchFn = null)
        registry.register("savorysailor", "pod-1", "10.0.0.1:8090", "stream", r1["podToken"] as String)
        // the second dispatch is refused while pod-1 is live...
        val r2 = registry.dispatch("savorysailor", "stream", "second", dispatchFn = null)
        assertEquals(false, r2["dispatched"])
        // ...and even a forged registration from another pod instance evicts.
        val e = assertThrows<PodEvictedException> {
            registry.register("savorysailor", "pod-2", "10.0.0.2:8090", "stream", r1["podToken"] as String)
        }
        assertTrue(e.message!!.contains("already serves"))
    }

    @Test
    fun `heartbeat keeps the pod alive and a superseded pod is told to stand down`() {
        val r = registry.dispatch("savorysailor", "stream", "test", dispatchFn = null)
        registry.register("savorysailor", "pod-1", "10.0.0.1:8090", "stream", r["podToken"] as String)
        assertNotNull(registry.heartbeat("savorysailor", "pod-1"))
        // a different pod instance heartbeating the same streamer = superseded
        assertNull(registry.heartbeat("savorysailor", "pod-2"))
    }

    @Test
    fun `release clears the lease — the next dispatch starts fresh`() {
        val r = registry.dispatch("savorysailor", "build", "test", dispatchFn = null)
        registry.register("savorysailor", "pod-1", "10.0.0.1:8090", "build", r["podToken"] as String)
        assertTrue(registry.release("savorysailor", "pod-1"))
        assertFalse(registry.release("savorysailor", "pod-1")) // double-release is a no-op
        val again = registry.dispatch("savorysailor", "build", "fresh", dispatchFn = null)
        assertEquals(true, again["dispatched"])
    }

    @Test
    fun `the dispatcher function receives the token and the mode`() {
        var captured: Triple<String, String, String>? = null
        val r = registry.dispatch("savorysailor", "stream", "test", dispatchFn = { s, t, m ->
            captured = Triple(s, t, m); "conductor says ok"
        })
        assertEquals("conductor says ok", r["conductor"])
        assertEquals("savorysailor", captured?.first)
        assertEquals("stream", captured?.third)
    }
}
