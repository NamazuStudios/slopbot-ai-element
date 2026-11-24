package dev.slopbot.element.model

import dev.morphia.annotations.Embedded


/**
 * A remark — a record attached to a slop or asset. Two kinds, both records:
 * `agentRemarks` (process: narration, CHAT SUMMARY, build notes) and
 * `contentRemarks` (about the contents). Both are searchable via the
 * MetadataSpec; a remark may be a separately linked document.
 */
@Embedded
class Remark(
    var text: String = "",
    var kind: String = "CONTENT",
    var timestamp: Long = 0,
    var author: String? = null,
    var linkedDoc: LargeObjectRef? = null,
) {
    companion object {
        const val KIND_AGENT_BUILD = "AGENT_BUILD"
        const val KIND_CONTENT = "CONTENT"
    }
}
