package dev.slopbot.element.model

import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id
import dev.morphia.annotations.Indexed
import dev.morphia.annotations.IndexOptions
import org.bson.types.ObjectId

/**
 * A playable asset (sound sample, video) — from the one shared pool for now
 * (tags `["shared"]`) or owned by a specific slop (tags `["slop", "<slop-name>"]`).
 * The schema must not preclude per-tenant assets later. Lives in the `slopbot`
 * database (see Slop.kt's note).
 */
@Entity("slopAssets")
class SlopAsset {
    @Id
    var id: ObjectId = ObjectId()

    /** e.g. `shared/boing` or `<slop-name>/boing`. */
    @Indexed(options = IndexOptions(unique = true))
    var name: String = ""

    /** The bare sample name, e.g. `boing`. */
    var displayName: String = ""

    var timestamp: Long = 0

    var tags: MutableList<String> = mutableListOf()

    /** `AUDIO|VIDEO`. */
    var kind: String = "AUDIO"

    var mimeType: String = ""

    var metadata: Metadata = Metadata()

    var source: LargeObjectRef? = null

    var agentRemarks: MutableList<Remark> = mutableListOf()

    var contentRemarks: MutableList<Remark> = mutableListOf()

    companion object {
        const val KIND_AUDIO = "AUDIO"
        const val KIND_VIDEO = "VIDEO"
    }
}
