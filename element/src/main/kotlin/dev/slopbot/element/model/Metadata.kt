package dev.slopbot.element.model

import dev.morphia.annotations.Embedded


/**
 * Metadata block carried by slops and assets. Searchable via the MetadataSpec.
 */
@Embedded
class Metadata(
    var short: String? = null,
    var author: String? = null,
    var summary: String? = null,
    var controls: String? = null,
    var moderationLevel: String? = null,
    var originalFilename: String? = null,
)
