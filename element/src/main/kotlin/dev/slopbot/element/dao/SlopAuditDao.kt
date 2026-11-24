package dev.slopbot.element.dao

import dev.slopbot.element.spi.SlopbotDatabase
import dev.slopbot.element.model.SlopAudit
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * The append-only audit DAO — every mutating admin route records who (the
 * superuser actor), whom (the streamer acted as), what (the action), when,
 * and a payload digest. Rows are only ever written or read, never updated
 * or deleted through this element.
 */
interface SlopAuditDao {
    fun record(audit: SlopAudit): SlopAudit
    fun list(limit: Int = 200): List<SlopAudit>
}

@Singleton
class StandardSlopAuditDao @Inject constructor(
    private val database: SlopbotDatabase,
) : SlopAuditDao {

    private val datastore get() = database.datastore

    override fun record(audit: SlopAudit): SlopAudit {
        if (audit.timestamp <= 0) audit.timestamp = System.currentTimeMillis()
        datastore.save(audit)
        return audit
    }

    override fun list(limit: Int): List<SlopAudit> =
        datastore.find(SlopAudit::class.java)
            .iterator()
            .toList()
            .sortedByDescending { it.timestamp }
            .take(limit)
}
