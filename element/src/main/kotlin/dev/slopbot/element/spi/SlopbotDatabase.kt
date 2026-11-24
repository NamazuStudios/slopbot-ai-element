package dev.slopbot.element.spi

import com.mongodb.client.MongoClient
import dev.morphia.Datastore
import dev.morphia.Morphia
import dev.slopbot.element.dao.SlopModification
import dev.slopbot.element.model.Slop
import dev.slopbot.element.model.SlopAsset
import dev.slopbot.element.model.SlopAudit
import jakarta.inject.Inject
import jakarta.inject.Provider
import jakarta.inject.Singleton

/**
 * The element's own Morphia [Datastore] on the shared `slopbot` database.
 *
 * The spec's data model asked for `@Entity(useDatabase = "slopbot")` — Morphia
 * 2.4's `@Entity` annotation carries no such attribute, so the element builds
 * a private Datastore over the `slopbot` database from the injected platform
 * [MongoClient] (the sdk-mongo Element exports it) and pre-maps its own
 * entities. The collections (`slops`, `slopAssets`, `slop_audit`,
 * `slop_mod_history`) and the GridFS bucket (`largeObjects`) all live in the
 * one shared `slopbot` database — the spec's intent, on the real API.
 */
class SlopbotDatabase(val datastore: Datastore) {

    /** The database name every collection lives in. */
    companion object {
        const val DATABASE = "slopbot"
    }
}

@Singleton
class SlopbotDatabaseProvider @Inject constructor(
    private val mongoClient: MongoClient,
) : Provider<SlopbotDatabase> {

    @Volatile
    private var database: SlopbotDatabase? = null

    override fun get(): SlopbotDatabase {
        database?.let { return it }
        synchronized(this) {
            database?.let { return it }
            val datastore = Morphia.createDatastore(mongoClient, SlopbotDatabase.DATABASE)
            datastore.mapper.apply {
                // Pre-map the element's entities so the unique indexes and the
                // discriminators resolve against this element's classloader.
                map(Slop::class.java)
                map(SlopAsset::class.java)
                map(SlopAudit::class.java)
                map(SlopModification::class.java)
            }
            datastore.ensureIndexes()
            val created = SlopbotDatabase(datastore)
            database = created
            return created
        }
    }
}
