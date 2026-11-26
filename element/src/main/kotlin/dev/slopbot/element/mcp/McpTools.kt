package dev.slopbot.element.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.slopbot.element.dao.SlopAssetDao
import dev.slopbot.element.dao.SlopDao
import dev.slopbot.element.dao.SourceStore
import dev.slopbot.element.engine.SlopBouncer
import dev.slopbot.element.model.Participant
import dev.slopbot.element.model.Remark
import dev.slopbot.element.model.Slop
import dev.slopbot.element.service.SlopEngine
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * The MCP tool surface — the agent's ONLY data access. JSON-RPC over HTTP
 * (`POST /slop/api/mcp`), method `tools/call` with the eight verbs:
 *
 * 1. `slops.search` — query owned slops by name/displayName/creator/tags →
 *    summary list (no source).
 * 2. `slops.source` — ONE merged source-reading tool with a scope flag:
 *    `scope=owned` → metadata + source of an owned slop (content reference;
 *    includes same-channel references); `scope=library` → raw source of ANY
 *    slop, read-only (technical/mechanical reference only).
 * 3. `slops.read` — read one owned slop: metadata + source.
 * 4. `slops.create` — the bouncer + `metaOf` pipeline; name assigned
 *    server-side; lands `moderation: PENDING`; returns the assigned name.
 * 5. `slops.update` — same pipeline; a running history of 50 modifications.
 * 6. `assets.list` — every asset available to the tenant (shared pool).
 * 7. `assets.read` — returns the large-object id; 8 MiB cap.
 * 8. `slops.submitModeration` — submit a created/updated slop for moderation.
 *
 * The caller is streamer-scoped (the validated OIDC token's subject): that
 * streamer — and only that streamer — owns the data these tools touch.
 */
@Singleton
class McpTools @Inject constructor(
    private val slopDao: SlopDao,
    private val assetDao: SlopAssetDao,
    private val sourceStore: SourceStore,
    private val engine: SlopEngine,
) {

    private val mapper = ObjectMapper()

    /** The 8 MiB cap on `assets.read`. */
    companion object {
        const val MAX_ASSET_READ_BYTES = 8 * 1024 * 1024

        val TOOL_NAMES = listOf(
            "slops.search", "slops.source", "slops.read", "slops.create",
            "slops.update", "assets.list", "assets.read", "slops.submitModeration",
        )

        /** The tool descriptors — names, descriptions, and argument schemas. */
        val TOOLS: List<Map<String, Any?>> = listOf(
            mapOf(
                "name" to "slops.search",
                "description" to ("Search the caller's own slops by name, displayName, creator, or tags. " +
                    "Returns summary records (no source)."),
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "Name/title/creator/tag search text"),
                        "tag" to mapOf("type" to "string", "description" to "Indexed-tag filter"),
                    ),
                ),
            ),
            mapOf(
                "name" to "slops.source",
                "description" to ("ONE merged source-reading tool with a scope flag: scope=owned → metadata " +
                    "+ source of an owned slop (content reference; includes same-channel references); " +
                    "scope=library → raw source of ANY slop, read-only (technical/mechanical reference only). " +
                    "Remarks are searchable when finding reference materials."),
                "inputSchema" to mapOf(
                    "type" to "object",
                    "required" to listOf("name", "scope"),
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string"),
                        "scope" to mapOf("type" to "string", "enum" to listOf("owned", "library")),
                    ),
                ),
            ),
            mapOf(
                "name" to "slops.read",
                "description" to "Read one owned slop: metadata + source.",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "required" to listOf("name"),
                    "properties" to mapOf("name" to mapOf("type" to "string")),
                ),
            ),
            mapOf(
                "name" to "slops.create",
                "description" to ("Create a slop for the caller's streamer. The source passes the bouncer and " +
                    "the meta pipeline; the name is assigned server-side (author-slug of the title, 39 chars, " +
                    "collision counters). Lands in moderation: PENDING. Returns the assigned name."),
                "inputSchema" to mapOf(
                    "type" to "object",
                    "required" to listOf("code"),
                    "properties" to mapOf(
                        "code" to mapOf("type" to "string", "description" to "The slop's JavaScript source"),
                        "creator" to mapOf(
                            "type" to "object",
                            "description" to "The chat user who prompted (id, login, displayName)",
                        ),
                        "agentRemarks" to mapOf(
                            "type" to "array",
                            "description" to "Process remarks (narration, CHAT SUMMARY, build notes)",
                            "items" to mapOf("type" to "object"),
                        ),
                        "contentRemarks" to mapOf(
                            "type" to "array",
                            "description" to "Remarks about the contents",
                            "items" to mapOf("type" to "object"),
                        ),
                    ),
                ),
            ),
            mapOf(
                "name" to "slops.update",
                "description" to ("Update an owned slop's source and/or metadata: the same bouncer + meta " +
                    "pipeline; a running history of 50 modifications is kept (previous source revisions kept " +
                    "in large-object history)."),
                "inputSchema" to mapOf(
                    "type" to "object",
                    "required" to listOf("name", "code"),
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string"),
                        "code" to mapOf("type" to "string"),
                        "agentRemarks" to mapOf("type" to "array", "items" to mapOf("type" to "object")),
                        "contentRemarks" to mapOf("type" to "array", "items" to mapOf("type" to "object")),
                    ),
                ),
            ),
            mapOf(
                "name" to "builds.next",
                "description" to "Take the next queued slop build. Returns the full brief: the prompt, the moderation tier in effect (its rules verbatim), the streamer's additional moderation instructions, the agent instructions, the model, and the per-build overrides. Creates nothing on its own — generate the slop code, then call slops.create (which completes the build).",
                "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any?>()),
            ),
            mapOf(
                "name" to "builds.fail",
                "description" to "Report that the taken build failed (the slot frees and the queue advances).",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("reason" to mapOf("type" to "string")),
                ),
            ),
            mapOf(
                "name" to "assets.list",
                "description" to ("Every asset available to the tenant (the shared pool tagged 'shared'), " +
                    "with kind/mime/size."),
                "inputSchema" to mapOf("type" to "object", "properties" to mapOf<String, Any>()),
            ),
            mapOf(
                "name" to "assets.read",
                "description" to ("Returns the asset's large-object id (fetch the bytes via the Large Object " +
                    "API). Capped at 8 MiB."),
                "inputSchema" to mapOf(
                    "type" to "object",
                    "required" to listOf("name"),
                    "properties" to mapOf("name" to mapOf("type" to "string")),
                ),
            ),
            mapOf(
                "name" to "slops.submitModeration",
                "description" to ("Submit a created/updated slop for moderation — not playable until approved."),
                "inputSchema" to mapOf(
                    "type" to "object",
                    "required" to listOf("name"),
                    "properties" to mapOf("name" to mapOf("type" to "string")),
                ),
            ),
        )
    }

    /**
     * Dispatches one `tools/call`. The [streamer] is the validated token's
     * subject. Returns the tool result payload (the `result` object of the
     * JSON-RPC response), or throws [McpToolError] (mapped to a JSON-RPC error).
     */
    fun call(name: String, arguments: Map<String, Any?>, streamer: String): Map<String, Any?> {
        val result: Any? = when (name) {
            "slops.search" -> search(arguments, streamer)
            "slops.source" -> source(arguments, streamer)
            "slops.read" -> read(arguments, streamer)
            "slops.create" -> create(arguments, streamer)
            "slops.update" -> update(arguments, streamer)
            "builds.next" -> engine.generationBrief()
                ?: mapOf("empty" to true, "message" to "no build is waiting — the queue advances when a prompt arrives")
            "builds.fail" -> {
                val ok = engine.failGeneration(arguments["reason"]?.toString())
                mapOf("ok" to ok)
            }
            "assets.list" -> assetsList(streamer)
            "assets.read" -> assetsRead(arguments)
            "slops.submitModeration" -> submitModeration(arguments, streamer)
            else -> throw McpToolError(-32602, "tool not found: $name")
        }
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to mapper.writeValueAsString(result))),
            "result" to result,
        )
    }

    // --- the verbs ---------------------------------------------------------------

    private fun search(arguments: Map<String, Any?>, streamer: String): List<Map<String, Any?>> {
        val query = arguments["query"]?.toString()?.lowercase()?.trim() ?: ""
        val tag = arguments["tag"]?.toString()
        return slopDao.findByStreamer(streamer)
            .filter { tag == null || it.tags.contains(tag) }
            .filter { matchesQuery(it, query) }
            .map { slop -> summary(slop) }
    }

    private fun matchesQuery(slop: Slop, query: String): Boolean {
        if (query.isEmpty()) return true
        val haystack = listOf(
            slop.name,
            slop.displayName,
            slop.metadata.author ?: "",
            slop.creator?.login ?: "",
            (slop.tags.joinToString(" ")),
            (slop.agentRemarks.joinToString(" ") { it.text }),
            (slop.contentRemarks.joinToString(" ") { it.text }),
        ).joinToString(" ").lowercase()
        return haystack.contains(query)
    }

    private fun source(arguments: Map<String, Any?>, streamer: String): Map<String, Any?> {
        val name = arguments["name"]?.toString() ?: throw McpToolError(-32602, "name is required")
        val scope = arguments["scope"]?.toString() ?: "owned"
        val slop = slopDao.findByName(name) ?: throw McpToolError(-32602, "no slop named $name")
        return when (scope) {
            "owned" -> {
                requireOwnership(slop, streamer)
                val code = slopDao.readSource(name)
                    ?: throw McpToolError(-32602, "no source in store for $name")
                mapOf(
                    "name" to slop.name,
                    "scope" to "owned",
                    "metadata" to metadataMap(slop),
                    "source" to code,
                    "triggers" to slop.triggers,
                    "agentRemarks" to slop.agentRemarks,
                    "contentRemarks" to slop.contentRemarks,
                )
            }
            "library" -> {
                // Raw source of ANY slop, read-only — technical/mechanical reference only.
                val code = slopDao.readSource(name)
                    ?: throw McpToolError(-32602, "no source in store for $name")
                mapOf(
                    "name" to slop.name,
                    "scope" to "library",
                    "source" to code,
                    "readOnly" to true,
                )
            }
            else -> throw McpToolError(-32602, "scope must be 'owned' or 'library'")
        }
    }

    private fun read(arguments: Map<String, Any?>, streamer: String): Map<String, Any?> {
        val name = arguments["name"]?.toString() ?: throw McpToolError(-32602, "name is required")
        val slop = slopDao.findByName(name) ?: throw McpToolError(-32602, "no slop named $name")
        requireOwnership(slop, streamer)
        val code = slopDao.readSource(name) ?: throw McpToolError(-32602, "no source in store for $name")
        return mapOf(
            "name" to slop.name,
            "metadata" to metadataMap(slop),
            "source" to code,
            "moderation" to slop.moderation,
            "triggers" to slop.triggers,
            "agentRemarks" to slop.agentRemarks,
            "contentRemarks" to slop.contentRemarks,
        )
    }

    private fun create(arguments: Map<String, Any?>, streamer: String): Map<String, Any?> {
        val code = arguments["code"]?.toString() ?: throw McpToolError(-32602, "code is required")
        @Suppress("UNCHECKED_CAST")
        val creatorMap = arguments["creator"] as? Map<String, Any?>
        val creator = creatorMap?.let {
            Participant(
                id = it["id"]?.toString(),
                login = it["login"]?.toString(),
                displayName = it["displayName"]?.toString(),
            )
        }
        val name = engine.createSlop(
            code = code,
            creator = creator,
            streamerLogin = streamer,
            agentRemarks = remarksFrom(arguments["agentRemarks"], "AGENT_BUILD"),
            contentRemarks = remarksFrom(arguments["contentRemarks"], "CONTENT"),
        )
        return mapOf("name" to name, "moderation" to "PENDING")
    }

    private fun update(arguments: Map<String, Any?>, streamer: String): Map<String, Any?> {
        val name = arguments["name"]?.toString() ?: throw McpToolError(-32602, "name is required")
        val code = arguments["code"]?.toString() ?: throw McpToolError(-32602, "code is required")
        val slop = slopDao.findByName(name) ?: throw McpToolError(-32602, "no slop named $name")
        requireOwnership(slop, streamer)
        val updated = engine.updateSlop(
            name = name,
            code = code,
            actor = streamer,
            agentRemarks = remarksFrom(arguments["agentRemarks"], "AGENT_BUILD"),
            contentRemarks = remarksFrom(arguments["contentRemarks"], "CONTENT"),
        ) ?: throw McpToolError(-32602, "no slop named $name")
        return mapOf("name" to updated, "moderation" to "PENDING")
    }

    private fun assetsList(streamer: String): List<Map<String, Any?>> =
        assetDao.list("shared").map { asset ->
            mapOf(
                "name" to asset.name,
                "displayName" to asset.displayName,
                "kind" to asset.kind,
                "mimeType" to asset.mimeType,
                "size" to (asset.source?.let { sourceSize(it) } ?: 0),
                "tags" to asset.tags,
            )
        }

    private fun assetsRead(arguments: Map<String, Any?>): Map<String, Any?> {
        val name = arguments["name"]?.toString() ?: throw McpToolError(-32602, "name is required")
        val asset = assetDao.findByName(name) ?: throw McpToolError(-32602, "no asset named $name")
        val size = asset.source?.let { sourceSize(it) } ?: 0
        if (size > MAX_ASSET_READ_BYTES) {
            throw McpToolError(-32602, "asset exceeds the ${MAX_ASSET_READ_BYTES / (1024 * 1024)} MiB cap")
        }
        val id = asset.source?.id ?: throw McpToolError(-32602, "asset has no large object")
        return mapOf(
            "name" to asset.name,
            "largeObjectId" to id,
            "mimeType" to asset.mimeType,
            "size" to size,
        )
    }

    private fun submitModeration(arguments: Map<String, Any?>, streamer: String): Map<String, Any?> {
        val name = arguments["name"]?.toString() ?: throw McpToolError(-32602, "name is required")
        val slop = slopDao.findByName(name) ?: throw McpToolError(-32602, "no slop named $name")
        requireOwnership(slop, streamer)
        slopDao.update(name, dev.slopbot.element.dao.SlopPatch(pending = true))
        return mapOf("name" to name, "moderation" to "PENDING")
    }

    // --- helpers ---------------------------------------------------------------

    private fun requireOwnership(slop: Slop, streamer: String) {
        val owner = slop.streamer?.login
        if (owner == null || !owner.equals(streamer, ignoreCase = true)) {
            throw McpToolError(-32602, "slop ${slop.name} is not owned by $streamer")
        }
    }

    private fun metadataMap(slop: Slop): Map<String, Any?> = mapOf(
        "short" to slop.metadata.short,
        "author" to slop.metadata.author,
        "summary" to slop.metadata.summary,
        "controls" to slop.metadata.controls,
        "moderationLevel" to slop.metadata.moderationLevel,
    )

    private fun summary(slop: Slop): Map<String, Any?> = mapOf(
        "name" to slop.name,
        "displayName" to slop.displayName,
        "author" to slop.metadata.author,
        "creator" to slop.creator,
        "moderation" to slop.moderation,
        "tags" to slop.tags,
        "playCount" to slop.playCount,
        "timestamp" to slop.timestamp,
        "triggers" to slop.triggers,
        "agentRemarks" to slop.agentRemarks.map { it.text },
        "contentRemarks" to slop.contentRemarks.map { it.text },
    )

    private fun remarksFrom(raw: Any?, kind: String): List<Remark> {
        if (raw !is List<*>) return emptyList()
        return raw.mapNotNull { entry ->
            when (entry) {
                is String -> Remark(text = entry, kind = kind, timestamp = System.currentTimeMillis())
                is Map<*, *> -> Remark(
                    text = entry["text"]?.toString() ?: "",
                    kind = (entry["kind"]?.toString() ?: kind),
                    timestamp = (entry["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    author = entry["author"]?.toString(),
                )
                else -> null
            }
        }
    }

    private fun sourceSize(ref: dev.slopbot.element.model.LargeObjectRef): Int = sourceStore.sizeOf(ref)
}

/** A tool failure — mapped to a JSON-RPC error response. */
class McpToolError(val code: Int, message: String) : Exception(message)
