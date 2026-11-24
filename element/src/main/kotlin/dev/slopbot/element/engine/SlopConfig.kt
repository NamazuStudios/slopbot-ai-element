package dev.slopbot.element.engine

/**
 * The config port (reference `lib/slop.js` DEFAULTS/CONFIG_KEYS/configure).
 * `slop.json` keys, BOM-tolerant reads, and the merge-never-clobber semantics.
 */
class SlopConfig {

    /** The live config state — starts at the reference DEFAULTS. */
    private val state = linkedMapOf<String, Any?>(
        "redeemTitle" to "Make It Slop",
        "rewardId" to null,
        "channel" to "",
        "announce" to true,
        "maxSlopSeconds" to 60,
        "maxQueueDepth" to 10,
        "feedPort" to 3010,
        "moderationLevel" to "teen",
        "customRules" to "",
        "agentInstructions" to "",
        "model" to "",
        "triggerDispatchesPerMinute" to 6,
        "allowedTriggers" to TriggerEngine.DEFAULT_ALLOWED_TRIGGERS,
        "streamerCam" to null,
    )

    /** The reference MODERATION_LEVELS — the tier rules, verbatim. */
    val moderationLevels: Map<String, String> = linkedMapOf(
        "kid" to ("Content must be strictly child-safe: gentle, silly, all-ages humor. No crude humor, no " +
            "innuendo, no scary imagery, no substances, no adult themes. Be very conservative in judgment — " +
            "when in doubt, soften it."),
        "teen" to ("Content may include somewhat crude humor (fart jokes, poop jokes) and light innuendo, but " +
            "nothing explicit. No drug or alcohol references, no sexual content, no gore."),
        "adult" to ("Content may include lewd humor, sexual references, and drug or alcohol references. Keep it " +
            "playful — nothing graphic or pornographic."),
        "anything" to ("Content may be as objectionable as the model allows, with ONE hard exception demanded by " +
            "Twitch's Content Guidelines (this plays on a live Twitch stream): no genitals, no explicit nudity, " +
            "no sex acts or pornography — lewd jokes and innuendo are fine, exposed genitals are not. Also " +
            "never: hate speech targeting any protected group (race, religion, creed, etc.) or calls for " +
            "self-harm or harm to others."),
    )

    /** The visible config echo — the CONFIG_KEYS subset (feedPort stays internal). */
    fun snapshot(): Map<String, Any?> = LinkedHashMap(state)

    @Suppress("UNCHECKED_CAST")
    fun allowedTriggers(): List<String> {
        val v = state["allowedTriggers"]
        return if (v is List<*>) v.filterIsInstance<String>() else TriggerEngine.DEFAULT_ALLOWED_TRIGGERS
    }

    @Suppress("UNCHECKED_CAST")
    fun streamerCam(): Map<String, Double>? = state["streamerCam"] as? Map<String, Double>

    fun maxSlopSeconds(): Int = state["maxSlopSeconds"] as Int
    fun maxQueueDepth(): Int = state["maxQueueDepth"] as Int
    fun triggerDispatchesPerMinute(): Int = state["triggerDispatchesPerMinute"] as Int
    fun moderationLevel(): String = state["moderationLevel"] as String
    fun channel(): String = state["channel"] as String

    /** JS `currentTier()` — the tier in effect, `teen` when unknown. */
    fun currentTier(): String {
        val level = moderationLevel()
        return if (moderationLevels.containsKey(level)) level else "teen"
    }

    /**
     * JS `readJson` — BOM-tolerant parse: a file written by PowerShell (Set-Content
     * -Encoding UTF8) or some Windows editors carries a U+FEFF, and a strict parse
     * throws on it — which once silently reset the entire config to defaults.
     */
    fun parseJsonTolerant(text: String?): Map<String, Any?>? {
        if (text == null) return null
        var t = text
        if (t.isNotEmpty() && t[0] == '﻿') t = t.substring(1)
        return try {
            @Suppress("UNCHECKED_CAST")
            MAPPER.readValue(t, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            null
        }
    }

    /**
     * JS `configure(patch)` — the deck settings patch. Returns the applied keys,
     * or an error ("nothing to change") when the patch touches nothing.
     */
    fun configure(patch: Map<String, Any?>): ConfigureResult {
        val applied = linkedMapOf<String, Any?>()
        for (key in CONFIG_KEYS) {
            if (!patch.containsKey(key)) continue
            var value: Any? = patch[key]
            when (key) {
                "rewardId" -> if (value == "" || value == null) value = null
                "announce" -> value = TriggerEngine.truthy(value)
                "maxSlopSeconds", "maxQueueDepth" -> {
                    // JS: Math.max(1, Math.round(Number(value))) || DEFAULTS[key] — only
                    // NaN (an unparseable value) falls back to the default; 0 becomes 1.
                    val n = jsNumber(value)
                    value = if (n == null) DEFAULTS_FOR_KEYS[key] else Math.max(1, Math.round(n)).toInt()
                }
                "triggerDispatchesPerMinute" -> {
                    val n = jsNumber(value)
                    value = if (n == null) {
                        DEFAULTS_FOR_KEYS[key]
                    } else {
                        Math.min(30, Math.max(1, Math.round(n).toInt()))
                    }
                }
                "allowedTriggers" -> {
                    // The registry allow-list — only known trigger types count.
                    val list: List<String> = if (value is List<*>) {
                        value.map { it?.toString() ?: "" }
                    } else {
                        (value?.toString() ?: "").split(",")
                    }
                    val cleaned = list.map { it.trim() }.filter { TriggerEngine.TRIGGER_TYPES.contains(it) }
                        .distinct()
                    if (cleaned.isEmpty()) continue // an empty list would mute everything
                    value = cleaned
                }
                "superusers" -> {
                    val list: List<String> = if (value is List<*>) {
                        value.map { it?.toString() ?: "" }
                    } else {
                        (value?.toString() ?: "").split(",")
                    }
                    value = list.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
                }
                "streamerCam" -> {
                    // A null or missing value is a NO-OP (the saved placement stands) —
                    // this key was wiped repeatedly by stale pages sending null, so only
                    // a sane rect may ever change it.
                    if (value == null || value == "") continue
                    val rect: Map<*, *> = if (value is Map<*, *>) value else emptyMap<Any, Any>()
                    fun clamp(n: Any?): Double = Math.min(1.0, Math.max(0.0, TriggerEngine.numberOrZero(n)))
                    val x = clamp(rect["x"])
                    val y = clamp(rect["y"])
                    val w = Math.min(1 - x, if (clamp(rect["w"]) == 0.0) 0.25 else clamp(rect["w"]))
                    val h = Math.min(1 - y, if (clamp(rect["h"]) == 0.0) 0.4 else clamp(rect["h"]))
                    if (w <= 0.01 || h <= 0.01) continue // degenerate — keep the saved one
                    value = linkedMapOf("x" to x, "y" to y, "w" to w, "h" to h)
                }
                "moderationLevel" -> {
                    // JS: if (!MODERATION_LEVELS[value]) continue — an unknown (or
                    // absent) tier keeps the saved one.
                    val level = value?.toString()
                    if (level == null || !moderationLevels.containsKey(level)) continue
                }
            }
            if (key == "customRules" || key == "agentInstructions" || key == "model") {
                value = value?.toString() ?: "" // clearing it back to '' must work
            } else if (value is String && value.isBlank()) {
                continue
            }
            state[key] = value
            applied[key] = value
        }
        if (applied.isEmpty()) {
            return ConfigureResult(ok = false, error = "nothing to change", applied = emptyMap())
        }
        // Merge-never-clobber: the WRITE must carry keys an older running process
        // doesn't know about. (In the ELM the config persists through the platform;
        // the merge semantics stay for the direct-JSON path.)
        return ConfigureResult(ok = true, error = null, applied = applied)
    }

    data class ConfigureResult(val ok: Boolean, val error: String?, val applied: Map<String, Any?>)

    companion object {
        val CONFIG_KEYS = listOf(
            "redeemTitle", "rewardId", "channel", "announce",
            "maxSlopSeconds", "maxQueueDepth", "moderationLevel", "customRules",
            "agentInstructions", "model", "triggerDispatchesPerMinute", "allowedTriggers",
            "streamerCam", "superusers",
        )

        private val DEFAULTS_FOR_KEYS = mapOf(
            "maxSlopSeconds" to 60,
            "maxQueueDepth" to 10,
            "triggerDispatchesPerMinute" to 6,
        )

        private val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()

        /** JS `Number(value)`, null when NaN (the `|| default` signal). */
        private fun jsNumber(v: Any?): Double? = when (v) {
            null -> 0.0 // JS Number(null) === 0
            is Number -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            is String -> {
                val d = v.trim().toDoubleOrNull()
                d // null === NaN
            }
            else -> null
        }
    }
}
