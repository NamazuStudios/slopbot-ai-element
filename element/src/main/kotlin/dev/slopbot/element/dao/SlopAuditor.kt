package dev.slopbot.element.dao

import dev.slopbot.element.model.SlopAudit
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.security.MessageDigest

/**
 * The append-only audit writer shared by every mutating admin route: who (the
 * superuser actor), whom (the streamer acted as), what (the action), when, and
 * a payload digest. Rows are only ever written or read — never updated or
 * deleted through this element.
 */
@Singleton
class SlopAuditor @Inject constructor(
    private val auditDao: SlopAuditDao,
) {

    /** The append-only audit row: who, whom, what, when, payload digest. */
    fun record(
        actor: String?,
        actingAs: String?,
        action: String,
        target: String?,
        payload: Map<String, Any?>?,
        details: String? = null,
    ): SlopAudit {
        val row = SlopAudit()
        row.actor = actor ?: ""
        row.actingAs = actingAs
        row.action = action
        row.target = target
        row.payloadDigest = payload?.let { digest(it) }
        row.timestamp = System.currentTimeMillis()
        row.details = details
        return auditDao.record(row)
    }

    private fun digest(payload: Map<String, Any?>): String {
        val json = ObjectMapper().writeValueAsString(payload)
        val bytes = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
