package dev.slopbot.element.rest

/**
 * The queue management actions (`POST /slop/api/slop/queue/{id}/{action}`).
 */
enum class SlopEngineQueueActions {
    PRESENT,
    DISMISS,
    REJECT,
    APPROVE,
    RESTART,
    CANCEL,
    REMOVE;

    companion object {
        fun of(action: String): SlopEngineQueueActions? = entries.firstOrNull { it.name.equals(action, ignoreCase = true) }
    }
}
