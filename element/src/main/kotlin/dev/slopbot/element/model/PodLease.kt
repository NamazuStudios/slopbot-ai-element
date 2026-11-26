package dev.slopbot.element.model

import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id
import org.bson.types.ObjectId

/**
 * The one-pod-per-streamer arbiter (Pod Boot Mechanics §lease): one ACTIVE
 * lease per streamer login. Lived in the shared `slopbot` database —
 * the ELM is the single writer; the pods never touch it directly.
 */
@Entity("pod_lease")
class PodLease {
    @Id
    var id: ObjectId = ObjectId()

    /** The tenant login — the unique key (one pod per streamer). */
    var streamer: String = ""

    /** The pod instance id (Conductor's execution id, or the minted lease id in dev). */
    var podInstanceId: String = ""

    /** The agent address the ELM dials out to (pod DNS or ip:relay-port). */
    var address: String = ""

    /** stream | build — the dispatch mode the pod booted into. */
    var mode: String = "build"

    /** DISPATCHED (awaiting registration) | ACTIVE (registered, heartbeating) | RELEASED */
    var status: String = STATUS_DISPATCHED

    /** SHA-256 of the per-pod token — the pod presents the raw token; never stored raw. */
    var tokenHash: String = ""

    var claimedAt: Long = 0
    var heartbeatAt: Long = 0

    companion object {
        const val STATUS_DISPATCHED = "DISPATCHED"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_RELEASED = "RELEASED"
    }
}

/** The database names the element's own entities live in. */
object SlopbotDatabaseNames {
    const val SLOPBOT = "slopbot"
}
