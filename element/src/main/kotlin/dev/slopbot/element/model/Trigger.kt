package dev.slopbot.element.model

import dev.morphia.annotations.Embedded


/**
 * A per-slop trigger — the events that make the slop eligible to play. Ported
 * field-for-field from the reference implementation (`lib/slop.js` normalizeTrigger).
 *
 * Creator-sourced triggers are PROPOSALS: stored but held (disabled) until the
 * registry allow-list admits them or the streamer adopts them.
 */
@Embedded
class Trigger(
    var id: String = "",
    var type: String = "",
    var mode: String? = null,
    var patterns: MutableList<String> = mutableListOf(),
    var caseSensitive: Boolean = false,
    var who: String? = null,
    var minViewers: Int? = null,
    var minBits: Int? = null,
    var tier: Int? = null,
    var minMonths: Int? = null,
    var rewardId: String? = null,
    var enabled: Boolean = true,
    var bypassCooldown: Boolean = false,
    var source: String = "streamer",
    var dispatch: String = "queue",
    var held: Boolean? = null,
)
