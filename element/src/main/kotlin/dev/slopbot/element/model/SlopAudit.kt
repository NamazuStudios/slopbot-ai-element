package dev.slopbot.element.model

import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id
import org.bson.types.ObjectId

/**
 * Append-only audit record for superuser actions (impersonation, admin
 * moderation, field reassignment). Every mutating admin route writes one:
 * who (the superuser actor), whom (the streamer acted upon), what (the action),
 * when, and a digest of the payload. Records are never updated or deleted
 * through the element's API. Lives in the `slopbot` database (see Slop.kt).
 */
@Entity("slop_audit")
class SlopAudit {
    @Id
    var id: ObjectId = ObjectId()

    /** The acting superuser's login (the real actor). */
    var actor: String = ""

    /** The streamer identity acted as (the impersonated tenant). */
    var actingAs: String? = null

    /** The action, e.g. `impersonate`, `suspend`, `remove`. */
    var action: String = ""

    /** The target of the action (slop name / streamer login), when applicable. */
    var target: String? = null

    /** SHA-256 digest (hex) of the request payload, when present. */
    var payloadDigest: String? = null

    /** Epoch ms. */
    var timestamp: Long = 0

    /** Free-form details (kept small). */
    var details: String? = null
}
