package dev.slopbot.element.dao

import com.mongodb.client.gridfs.GridFSBuckets
import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.model.Filters
import dev.slopbot.element.spi.SlopbotDatabase
import dev.slopbot.element.model.LargeObjectRef
import org.bson.Document
import org.bson.types.ObjectId
import java.io.ByteArrayOutputStream
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * GridFS-backed [SourceStore] — the ELM's port of the reference `lib/store.js`
 * large-object helpers (`putLargeObject`/`getLargeObject`/`rmLargeObject`):
 * bytes live in GridFS keyed by object id, with path/mimeType/state/
 * originalFilename in the file's metadata document. The bucket name matches
 * the reference (`largeObjects`), inside the shared `slopbot` database.
 */
@Singleton
class GridFsSourceStore @Inject constructor(
    database: SlopbotDatabase,
) : SourceStore {

    private val bucket: GridFSBucket = GridFSBuckets.create(database.datastore.getDatabase(), "largeObjects")

    override fun put(path: String, mimeType: String, originalFilename: String?, bytes: ByteArray): LargeObjectRef {
        val metadata = Document(
            mapOf(
                "path" to path,
                "mimeType" to mimeType,
                "state" to "UPLOADED",
                "originalFilename" to originalFilename,
            ),
        )
        val uploadStream = bucket.openUploadStream(path, com.mongodb.client.gridfs.model.GridFSUploadOptions().metadata(metadata))
        uploadStream.write(bytes)
        uploadStream.close()
        val id = uploadStream.getObjectId()
        return LargeObjectRef(
            id = id.toHexString(),
            mimeType = mimeType,
            originalFilename = originalFilename,
            state = "UPLOADED",
        )
    }

    override fun get(ref: LargeObjectRef?): ByteArray? {
        val id = ref?.id ?: return null
        return try {
            val objectId = ObjectId(id)
            val out = ByteArrayOutputStream()
            bucket.downloadToStream(objectId, out)
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    override fun delete(ref: LargeObjectRef?) {
        val id = ref?.id ?: return
        try {
            bucket.delete(ObjectId(id))
        } catch (e: Exception) {
            // already gone
        }
    }

    override fun sizeOf(ref: LargeObjectRef?): Int {
        val id = ref?.id ?: return 0
        return try {
            bucket.find(Filters.eq("_id", ObjectId(id)))
                .firstOrNull()?.length?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
