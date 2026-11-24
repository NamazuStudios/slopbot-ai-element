package dev.slopbot.element.model

import dev.morphia.annotations.Embedded


/**
 * Reference to the slop's (or asset's) source bytes in the Large Object store
 * (GridFS). Mirrors the reference implementation's `source` ref
 * (slopbot-ai `lib/store.js` — the manual's Large Object API shape).
 */
@Embedded
class LargeObjectRef(
    var id: String? = null,
    var mimeType: String? = null,
    var originalFilename: String? = null,
    var state: String? = null,
)
