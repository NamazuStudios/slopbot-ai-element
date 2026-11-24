package dev.slopbot.element.service

import dev.slopbot.element.dao.SlopAssetDao
import dev.slopbot.element.dao.SlopAuditor
import dev.slopbot.element.dao.SlopDao
import dev.slopbot.element.dao.SlopPatch
import dev.slopbot.element.engine.SlopReferenceScanner
import dev.slopbot.element.model.Participant
import dev.slopbot.element.model.Slop
import dev.slopbot.element.model.SlopAsset
import jakarta.inject.Inject
import jakarta.inject.Singleton


/**
 * The asset-admin surface behind the superuser REST routes: upload (multipart
 * → Large Object → SlopAsset record), replace (new LargeObject revision with
 * existing slops pinned to the old), retire/unretire, guarded delete, the
 * referenced-by scan (the same extraction the bundler uses), and the slop
 * owner/creator reassignment. Every mutation writes an audit row.
 */
@Singleton
class SlopAssetAdmin @Inject constructor(
    private val assetDao: SlopAssetDao,
    private val slopDao: SlopDao,
    private val sourceStore: dev.slopbot.element.dao.SourceStore,
    private val auditor: SlopAuditor,
) {

    companion object {

        /** The upload cap (8 MiB — the spec's asset cap). */
        const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024

        /** The extension allow-list: audio `.mp3/.ogg/.wav`, video `.mp4/.webm`. */
        val MIME_BY_EXTENSION = mapOf(
            "mp3" to "audio/mpeg",
            "ogg" to "audio/ogg",
            "wav" to "audio/x-wav",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
        )

        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "wav")
    }

    /** The validated upload shape (name normalization + kind/mime derivation). */
    data class ValidatedUpload(
        val displayName: String,
        val kind: String,
        val mimeType: String,
    )

    // --- validation ----------------------------------------------------------------

    /**
     * Validates an upload: extension/mime allow-list, the 8 MiB cap, and name
     * normalization (lowercase, bare-name, no path shapes). The display name
     * is the bare filename without its extension; the mime type derives from
     * the extension (a supplied mime that contradicts the kind is rejected).
     */
    fun validateUpload(
        filename: String?,
        suppliedName: String?,
        suppliedMimeType: String?,
        bytes: ByteArray,
    ): ValidatedUpload {
        if (bytes.isEmpty()) throw IllegalArgumentException("no bytes uploaded")
        if (bytes.size > MAX_UPLOAD_BYTES) {
            throw IllegalArgumentException("asset exceeds the ${MAX_UPLOAD_BYTES / (1024 * 1024)} MiB cap")
        }

        val base = normalizeBareName(suppliedName ?: filename
            ?: throw IllegalArgumentException("no name supplied"))
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) {
            throw IllegalArgumentException("asset name carries no extension: $base")
        }
        val extension = base.substring(dot + 1)
        val mimeType = MIME_BY_EXTENSION[extension]
            ?: throw IllegalArgumentException(
                "unsupported asset extension: .$extension " +
                    "(allowed: .mp3, .ogg, .wav, .mp4, .webm)",
            )
        val kind = if (extension in AUDIO_EXTENSIONS) SlopAsset.KIND_AUDIO else SlopAsset.KIND_VIDEO

        if (!suppliedMimeType.isNullOrBlank()) {
            val suppliedType = suppliedMimeType.substringBefore('/').lowercase()
            val expectedType = mimeType.substringBefore('/')
            if (suppliedType != expectedType) {
                throw IllegalArgumentException(
                    "mime type $suppliedMimeType does not match a $expectedType extension",
                )
            }
        }

        return ValidatedUpload(
            displayName = base.substring(0, dot),
            kind = kind,
            mimeType = mimeType,
        )
    }

    /**
     * Name normalization: lowercase, bare-name (directories stripped), and a
     * hard refusal for path shapes (`..`, separators). Whitespace collapses
     * to `-`; the bundler matches the normalized name exactly, so this is the
     * canonical form everywhere.
     */
    fun normalizeBareName(raw: String): String {
        // Bare name: strip any leading path segments the client sent.
        var base = raw.trim().lowercase()
            .substringAfterLast('/')
            .substringAfterLast('\\')
        if (base.contains('/') || base.contains('\\') || base.contains("..")) {
            throw IllegalArgumentException("asset name must be a bare name, not a path: $raw")
        }
        base = base.replace(Regex("\\s+"), "-").trim('-')
        if (base.isEmpty()) throw IllegalArgumentException("asset name is empty")
        return base
    }

    // --- upload ------------------------------------------------------------------

    /**
     * The upload route's body: multipart → Large Object → SlopAsset record.
     * Shared-pool assets (default) are tagged `["shared"]` and named
     * `shared/<display>`; a `slopOwner` (a slop's file name) lands a per-slop
     * asset tagged `["slop", "<slop-name>"]` named `<slop-name>/<display>`.
     */
    fun upload(
        filename: String?,
        suppliedName: String?,
        slopOwner: String?,
        suppliedMimeType: String?,
        bytes: ByteArray,
        actor: String?,
    ): SlopAsset {
        val validated = validateUpload(filename, suppliedName, suppliedMimeType, bytes)
        val owner = slopOwner?.let { normalizeSlopOwner(it) }
        val name = if (owner == null) "shared/${validated.displayName}" else "$owner/${validated.displayName}"
        if (assetDao.findByName(name) != null) {
            throw IllegalStateException("an asset named $name already exists")
        }

        val asset = SlopAsset()
        asset.name = name
        asset.displayName = validated.displayName
        asset.kind = validated.kind
        asset.mimeType = validated.mimeType
        asset.metadata.originalFilename = (filename ?: suppliedName)?.substringAfterLast('/')?.substringAfterLast('\\')
        asset.tags = if (owner == null) mutableListOf("shared") else mutableListOf("slop", owner)
        assetDao.put(asset, bytes)
        auditor.record(
            actor = actor,
            actingAs = owner,
            action = "asset-upload",
            target = name,
            payload = mapOf("kind" to validated.kind, "bytes" to bytes.size),
        )
        return asset
    }

    private fun normalizeSlopOwner(raw: String): String {
        val owner = raw.trim().lowercase()
        if (owner.isEmpty() || owner.contains('/') || owner.contains('\\') || owner.contains("..")) {
            throw IllegalArgumentException("slop owner must be a bare slop name: $raw")
        }
        return owner
    }

    // --- replace -------------------------------------------------------------------

    /**
     * Replaces an asset's bytes: a NEW LargeObject revision, the previous
     * revision pinned (kept in the store and in the record's history). The
     * replacement must match the asset's kind.
     */
    fun replaceBytes(
        id: String,
        filename: String?,
        suppliedMimeType: String?,
        bytes: ByteArray,
        actor: String?,
    ): SlopAsset {
        val asset = assetDao.findById(id)
            ?: throw NoSuchElementException("no asset with id $id")
        val validated = validateUpload(filename, filename, suppliedMimeType, bytes)
        if (validated.kind != asset.kind) {
            throw IllegalArgumentException(
                "asset ${asset.name} is ${asset.kind}; the replacement is ${validated.kind}",
            )
        }
        val replaced = assetDao.replaceBytes(asset.name, bytes)
            ?: throw NoSuchElementException("no asset with id $id")
        auditor.record(
            actor = actor,
            actingAs = replaced.tags.lastOrNull(),
            action = "asset-replace",
            target = replaced.name,
            payload = mapOf("bytes" to bytes.size, "revision" to (replaced.revisions.size + 1)),
        )
        return replaced
    }

    // --- retire / unretire -----------------------------------------------------------

    /**
     * Retire: excluded from NEW bundles; existing bundles keep working; the
     * bundler's missing-asset behavior stays graceful (log + skip).
     */
    fun retire(id: String, actor: String?): SlopAsset = setState(id, SlopAsset.STATE_RETIRED, actor)

    /** Unretire: back in the pool, eligible for new bundles again. */
    fun unretire(id: String, actor: String?): SlopAsset = setState(id, SlopAsset.STATE_ACTIVE, actor)

    private fun setState(id: String, state: String, actor: String?): SlopAsset {
        val asset = assetDao.findById(id)
            ?: throw NoSuchElementException("no asset with id $id")
        val updated = assetDao.setState(asset.name, state)
            ?: throw NoSuchElementException("no asset with id $id")
        auditor.record(
            actor = actor,
            actingAs = updated.tags.lastOrNull(),
            action = if (state == SlopAsset.STATE_RETIRED) "asset-retire" else "asset-unretire",
            target = updated.name,
            payload = null,
        )
        return updated
    }

    // --- delete (guarded) -------------------------------------------------------------

    /**
     * Deletes an asset — guarded: only UNREFERENCED assets. A shared asset a
     * slop's source still samples (or a per-slop asset whose owning slop
     * still exists) refuses with the referencing slops in the error.
     */
    fun delete(id: String, actor: String?): SlopAsset {
        val asset = assetDao.findById(id)
            ?: throw NoSuchElementException("no asset with id $id")
        val referencing = referencedBy(asset)
        if (referencing.isNotEmpty()) {
            throw IllegalStateException(
                "asset ${asset.name} is referenced by ${referencing.size} slop(s): " +
                    referencing.joinToString(", "),
            )
        }
        assetDao.delete(asset.name)
        auditor.record(
            actor = actor,
            actingAs = asset.tags.lastOrNull(),
            action = "asset-delete",
            target = asset.name,
            payload = null,
        )
        return asset
    }

    // --- referenced-by -----------------------------------------------------------------

    /** Every asset record (the list route's base; the resource applies filters). */
    fun list(): List<SlopAsset> = assetDao.list()

    /** The stored size in bytes for a source reference (0 when absent). */
    fun sizeOf(ref: dev.slopbot.element.model.LargeObjectRef): Int = sourceStore.sizeOf(ref)

    /**
     * Which slops reference the asset — the same scan the bundler uses
     * (`sound.sample('name')` / `ctx.media.video('name')`). A shared asset is
     * referenced by every slop whose source names its display name; a per-slop
     * asset is referenced by its owning slop while that slop exists.
     */
    fun referencedBy(asset: SlopAsset): List<String> = referencedBy(asset, sharedReferenceIndex())

    /** The list route's index: one source scan per slop, keyed by referenced name. */
    fun sharedReferenceIndex(): Map<String, Set<String>> {
        val index = mutableMapOf<String, MutableSet<String>>()
        for (slop in slopDao.list()) {
            val source = slopDao.readSource(slop.name) ?: continue
            for (ref in SlopReferenceScanner.allRefs(source)) {
                index.getOrPut(ref) { mutableSetOf() }.add(slop.name)
            }
        }
        return index
    }

    /** The referencing slops against a prebuilt [sharedReferenceIndex]. */
    fun referencedBy(asset: SlopAsset, index: Map<String, Set<String>>): List<String> = when {
        asset.tags.contains("slop") ->
            listOfNotNull(asset.tags.lastOrNull()?.takeIf { owner -> slopDao.findByName(owner) != null })
        else -> (index[asset.displayName.lowercase()] ?: emptySet()).sorted()
    }

    // --- slop reassign (attribution repair) ---------------------------------------------

    /**
     * Reassigns a slop's owner (the tenant streamer) and/or creator (the chat
     * user who prompted) — the content-attribution repair. At least one field
     * must be present. Audited with the payload digest.
     */
    fun reassign(
        name: String,
        ownerLogin: String?,
        creator: Participant?,
        actor: String?,
        actingAs: String?,
        payload: Map<String, Any?>?,
    ): Slop {
        val slop = slopDao.findByName(name)
            ?: throw NoSuchElementException("no slop named $name")
        if (ownerLogin == null && creator == null) {
            throw IllegalArgumentException("nothing to reassign (owner?, creator?)")
        }
        val patch = SlopPatch(
            owner = ownerLogin?.let {
                Participant(id = null, login = it.trim().lowercase(), displayName = null)
            },
            creator = creator,
        )
        val updated = slopDao.update(name, patch)
            ?: throw NoSuchElementException("no slop named $name")
        auditor.record(
            actor = actor,
            actingAs = actingAs ?: ownerLogin?.trim()?.lowercase(),
            action = "reassign",
            target = name,
            payload = payload,
        )
        return updated
    }
}
