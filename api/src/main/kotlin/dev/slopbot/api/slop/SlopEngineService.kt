package dev.slopbot.api.slop

import dev.getelements.elements.sdk.annotation.ElementServiceExport

/**
 * The engine-facing surface of the SlopBot AI element — the dispatch seam the worker
 * (and other Elements) use. The worker feeds Twitch events in; the element owns all
 * state, all matching, and all persistence.
 */
@ElementServiceExport
interface SlopEngineService {

    /**
     * Dispatches an incoming stream event (chat line, follow, sub, raid, cheer,
     * redemption, …) into the trigger engine. The engine records first-ever tracking,
     * applies the registry allow-list and the flood cap, then queues or immediately
     * plays the matched slop.
     *
     * @param event the event as delivered by the worker
     * @return true when an event was dispatched into a play
     */
    fun dispatchTrigger(event: SlopTriggerEvent): Boolean

    /**
     * A synthetic trigger test — the deck settings screen's matcher. Evaluates the
     * text as if a mod had typed it in the streamer's chat.
     *
     * @param name the slop's file name
     * @param text the synthetic chat text
     * @return the ids of the triggers that match
     */
    fun testTriggers(name: String, text: String): List<String>

    /**
     * A manual play — deck buttons and `!slop` resolve here. Manual plays never
     * arm cooldowns and never count against the trigger flood cap.
     *
     * @param name the slop's file name
     * @param via a human label for how the play was requested
     * @param chained true when this play is itself a cameo (chain depth 1 — a cameo
     *                cannot chain further)
     * @return true when the play started or queued
     */
    fun play(name: String, via: String = "slop", chained: Boolean = false): Boolean

    /**
     * The current status snapshot.
     */
    fun snapshot(): SlopSnapshot
}
