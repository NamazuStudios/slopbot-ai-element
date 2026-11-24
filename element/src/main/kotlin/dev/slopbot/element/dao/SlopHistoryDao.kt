package dev.slopbot.element.dao

import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id
import dev.slopbot.element.model.LargeObjectRef
import dev.slopbot.element.spi.SlopbotDatabase
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.bson.types.ObjectId

/**
 * One modification in the running history of 50 kept by `slops.update`
 * (binding decision): the previous source revision is kept in large-object
 * history — a new large object per revision, referenced here, never deleted
 * until the slop itself is. Lives in the `slopbot` database (see Slop.kt).
 */
@Entity("slop_mod_history")
class SlopModification {
    @Id
    var id: ObjectId = ObjectId()

    var name: String = ""

    /** 1-based modification index (per slop). */
    var index: Long = 0

    var timestamp: Long = 0

    /** Who made the change (the agent identity / acting user). */
    var actor: String? = null

    /** The PREVIOUS source revision, kept in large-object history. */
    var previousSource: LargeObjectRef? = null

    /** What changed (fields touched). */
    var changes: String? = null
}

/**
 * The modification-history DAO (append-only, capped at 50 per slop by the
 * service layer).
 */
interface SlopHistoryDao {
    fun record(modification: SlopModification): SlopModification
    fun list(name: String, limit: Int = 50): List<SlopModification>

    /** Enforces the running-history cap (the newest `cap` records survive). */
    fun trim(name: String, cap: Int)
}

@Singleton
class StandardSlopHistoryDao @Inject constructor(
    private val database: SlopbotDatabase,
) : SlopHistoryDao {

    private val datastore get() = database.datastore

    override fun record(modification: SlopModification): SlopModification {
        datastore.save(modification)
        return modification
    }

    override fun list(name: String, limit: Int): List<SlopModification> =
        datastore.find(SlopModification::class.java)
            .iterator()
            .asSequence()
            .filter { it.name == name }
            .sortedByDescending { it.index }
            .take(limit)
            .toList()

    override fun trim(name: String, cap: Int) {
        val all = datastore.find(SlopModification::class.java)
            .iterator()
            .asSequence()
            .filter { it.name == name }
            .toList()
        if (all.size <= cap) return
        all.sortedByDescending { it.index }
            .drop(cap)
            .forEach { datastore.delete(it) }
    }
}
