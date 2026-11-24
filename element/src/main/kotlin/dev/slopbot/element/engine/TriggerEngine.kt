package dev.slopbot.element.engine

import dev.slopbot.api.slop.SlopTriggerEvent
import dev.slopbot.element.model.Trigger

/**
 * The trigger engine port (reference `lib/slop.js`): chat-pattern matching in
 * the seven modes, the event-type mapping, [normalizeTrigger], and the
 * registry allow-list gate.
 */
object TriggerEngine {

    const val TRIGGER_TYPE_CHAT = "chat"
    const val TRIGGER_TYPE_FIRSTCHAT = "firstchat"
    const val TRIGGER_TYPE_FOLLOW = "follow"
    const val TRIGGER_TYPE_FIRSTFOLLOW = "firstfollow"
    const val TRIGGER_TYPE_SUB = "sub"
    const val TRIGGER_TYPE_RESUB = "resub"
    const val TRIGGER_TYPE_SUBGIFT = "subgift"
    const val TRIGGER_TYPE_RAID = "raid"
    const val TRIGGER_TYPE_CHEER = "cheer"
    const val TRIGGER_TYPE_REDEMPTION = "redemption"

    val TRIGGER_TYPES = listOf(
        "chat", "firstchat", "follow", "firstfollow", "sub", "resub",
        "subgift", "raid", "cheer", "redemption",
    )
    val CHAT_MODES = listOf("exact", "contains", "starts", "ends", "word", "wildcard", "regex")
    val CHAT_WHO = listOf("anyone", "mods", "broadcaster")

    const val MAX_TRIGGERS_PER_SLOP = 12
    const val MAX_PATTERNS_PER_TRIGGER = 8
    const val MAX_PATTERN_LENGTH = 64

    /** The default registry allow-list — chat and firstchat only. */
    val DEFAULT_ALLOWED_TRIGGERS = listOf("chat", "firstchat")

    /** JS `escapeRe`: `String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')`. */
    fun escapeRe(text: String): String =
        Regex("""[\*\+\?\^\$\{\}\(\)\|\[\]\\]""").replace(text) { "\\" + it.value }

    /** JS `triggerTypesFor`: chat → [chat, firstchat]; follow → [follow, firstfollow]; else identity. */
    fun triggerTypesFor(eventType: String): List<String> = when (eventType) {
        "chat" -> listOf("chat", "firstchat")
        "follow" -> listOf("follow", "firstfollow")
        else -> listOf(eventType)
    }

    /**
     * JS `matchChatPattern` — one pattern against one (already folded) text in the
     * trigger's mode. The regex mode ignores the folded pattern and uses the cached
     * compilation of the trigger's original `patterns[0]`.
     */
    fun matchChatPattern(trigger: Trigger, pattern: String?, text: String?): Boolean {
        // JS: if (!pattern) return false — an empty (or absent) pattern never matches.
        if (pattern.isNullOrEmpty()) return false
        if (text == null) return false
        when (trigger.mode) {
            "exact" -> return text.trim() == pattern.trim()
            "contains" -> return text.contains(pattern)
            "starts" -> return text.trimStart().startsWith(pattern)
            "ends" -> return text.trimEnd().endsWith(pattern)
            "word" -> {
                val flags = if (trigger.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val re = Regex("""(^|[^a-z0-9_])${escapeRe(pattern)}([^a-z0-9_]|$)""", flags)
                return re.containsMatchIn(text)
            }
            "wildcard" -> {
                val flags = if (trigger.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val re = Regex("""^${escapeRe(pattern).replace("\\*", ".*")}$""", flags)
                return re.containsMatchIn(text)
            }
            "regex" -> {
                // A regex trigger keeps a single pattern (patterns[0]). An invalid
                // regex matches nothing.
                val re = compiledRegex(trigger)
                return re?.containsMatchIn(text) ?: false
            }
            else -> return false
        }
    }

    // JS: the compiled-regex cache keyed by trigger object. Java/Kotlin identities
    // are used for the cache key (IdentityHashMap semantics).
    private val regexCache = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Trigger, Regex?>(),
    )

    private fun compiledRegex(trigger: Trigger): Regex? =
        synchronized(regexCache) {
            if (regexCache.containsKey(trigger)) {
                regexCache[trigger]
            } else {
                val re = try {
                    val pattern = trigger.patterns.firstOrNull() ?: ""
                    if (trigger.caseSensitive) Regex(pattern)
                    else Regex(pattern, setOf(RegexOption.IGNORE_CASE))
                } catch (e: Exception) {
                    null // an invalid regex matches nothing
                }
                regexCache[trigger] = re
                re
            }
        }

    /**
     * JS `triggerMatches` — the full matcher: event-type mapping, chat criteria
     * (who-gating, firstchat semantics, folded pattern matching), and the
     * per-type criteria for the non-chat events.
     */
    fun triggerMatches(trigger: Trigger?, event: SlopTriggerEvent?): Boolean {
        if (trigger == null || event == null) return false
        if (!trigger.enabled || !triggerTypesFor(event.type).contains(trigger.type)) return false
        when (trigger.type) {
            "chat", "firstchat" -> {
                // JS: typeof event.text !== 'string' — an absent text fails.
                val text = event.text
                if (text == null) return false
                // A firstchat trigger only fires on a user's first-ever message; its
                // patterns are optional — empty means "any first line".
                if (trigger.type == "firstchat" && !event.first) return false
                val who = trigger.who ?: "anyone"
                if (who == "mods" && !event.mod) return false
                if (who == "broadcaster" && !event.broadcaster) return false
                val patterns = trigger.patterns
                if (patterns.isEmpty()) return true // presence is the match
                // Fold once here — every mode then compares like-for-like.
                fun fold(t: String): String = if (trigger.caseSensitive) t else t.lowercase()
                return patterns.any { pattern -> matchChatPattern(trigger, fold(pattern), fold(text)) }
            }
            "firstfollow" -> return event.first
            "raid" -> return (event.viewers ?: 0) >= (trigger.minViewers ?: 0)
            "cheer" -> return (event.bits ?: 0) >= (trigger.minBits ?: 0)
            "sub" -> {
                val tier = trigger.tier
                return tier == null || tier == 0 || (event.tier ?: 0) >= tier
            }
            "resub" -> return (event.months ?: 0) >= (trigger.minMonths ?: 0)
            "redemption" -> {
                val rewardId = trigger.rewardId
                return rewardId.isNullOrEmpty() ||
                    (event.rewardId ?: "") == (rewardId ?: "")
            }
            else -> return true // follow / subgift — presence of the event is the match
        }
    }

    /**
     * JS `truthy` — the reference coerces with `Boolean(raw.x)` / `!== false`. A
     * non-empty string is truthy (including the string `"false"`); null is falsy;
     * numbers are truthy when non-zero.
     */
    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is String -> v.isNotEmpty()
        is Number -> v.toDouble() != 0.0
        else -> true
    }

    /** JS `Number(raw.x) || 0` for the numeric criteria. */
    fun numberOrZero(v: Any?): Double = when (v) {
        null -> 0.0
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    /**
     * Validate + normalize one raw trigger (from the deck or the agent's
     * meta.triggers proposal). Ported from `normalizeTrigger`. Returns null when
     * the trigger is unusable (unknown type, bad mode, unusable patterns, bad
     * regex). `source` marks who authored it — 'creator' (agent proposal, gated
     * by the registry allow-list) or 'streamer' (deck save — always authoritative).
     *
     * @param raw the raw trigger as parsed JSON (Map) or any value (null → null)
     * @param index the trigger's index (drives the generated id)
     * @param source 'creator' or 'streamer'
     * @param nowMs the epoch ms (drives the generated id)
     */
    fun normalizeTrigger(raw: Any?, index: Int, source: String = "streamer", nowMs: Long = System.currentTimeMillis()): Trigger? {
        if (raw !is Map<*, *>) return null
        val type = raw["type"] as? String
        if (type == null || !TRIGGER_TYPES.contains(type)) return null

        val trigger = Trigger(
            id = (raw["id"] ?: "t$index-${nowMs.toString(36)}").toString(),
            type = type,
            enabled = raw["enabled"] != false, // JS: raw.enabled !== false
            source = if (source == "creator") "creator" else "streamer",
            // Dispatch behavior (issue #4, streamer-only — creator proposals are
            // forced back to the default queue behavior): queue | immediate.
            dispatch = if (source == "creator") {
                "queue"
            } else if (raw["dispatch"] == "immediate") {
                "immediate"
            } else {
                "queue"
            },
            // Reserved for the per-slop cooldown (#5): stored now, enforced later.
            bypassCooldown = if (source == "creator") false else truthy(raw["bypassCooldown"]),
        )

        when (type) {
            "chat", "firstchat" -> {
                val mode = raw["mode"] as? String
                trigger.mode = if (mode != null && CHAT_MODES.contains(mode)) mode else "contains"
                trigger.caseSensitive = truthy(raw["caseSensitive"])
                val who = raw["who"] as? String
                trigger.who = if (who != null && CHAT_WHO.contains(who)) who else "anyone"
                val rawPatterns = raw["patterns"]
                val patternList: List<Any?> = if (rawPatterns is List<*>) {
                    rawPatterns
                } else {
                    listOf(rawPatterns)
                }
                var patterns = patternList
                    .map { (it?.toString() ?: "").trim() }
                    .filter { it.isNotEmpty() }
                    .take(MAX_PATTERNS_PER_TRIGGER)
                if (trigger.mode == "regex") patterns = patterns.take(1) // one regex per trigger
                if (patterns.isNotEmpty()) {
                    if (patterns.any { it.length > MAX_PATTERN_LENGTH }) return null
                    if (trigger.mode == "regex") {
                        try {
                            Regex(patterns[0]) // must compile; length cap above blunts ReDoS
                        } catch (e: Exception) {
                            return null
                        }
                    }
                }
                // firstchat allows an empty pattern list ("any first line"); chat does not
                if (patterns.isEmpty() && type == "chat") return null
                trigger.patterns = patterns.toMutableList()
            }
            "raid" -> trigger.minViewers = Math.max(0, Math.round(numberOrZero(raw["minViewers"])).toInt())
            "cheer" -> trigger.minBits = Math.max(0, Math.round(numberOrZero(raw["minBits"])).toInt())
            "sub" -> {
                // JS: [1, 2, 3].includes(Number(raw.tier)) ? Number(raw.tier) : 0
                val t = numberOrZero(raw["tier"])
                trigger.tier = if (t == 1.0 || t == 2.0 || t == 3.0) t.toInt() else 0 // 0 = any
            }
            "resub" -> trigger.minMonths = Math.max(0, Math.round(numberOrZero(raw["minMonths"])).toInt())
            "redemption" -> trigger.rewardId = (raw["rewardId"]?.toString() ?: "").trim().ifEmpty { null } // null = any reward
        }
        // firstfollow needs no criteria — the event itself is the match
        return trigger
    }

    /**
     * JS `triggerAllowed` — the registry allow-list: may this trigger actually
     * fire? A trigger whose type is outside allowedTriggers only counts when the
     * streamer authored it — creator proposals are held back until allowed.
     */
    fun triggerAllowed(trigger: Trigger, allowedTriggers: List<String>): Boolean =
        allowedTriggers.contains(trigger.type) || trigger.source == "streamer"
}
