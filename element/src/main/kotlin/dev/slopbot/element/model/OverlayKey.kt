package dev.slopbot.element.model

import dev.morphia.annotations.Entity
import dev.morphia.annotations.Id
import org.bson.types.ObjectId

/**
 * The streamer's overlay key (Pod Boot Mechanics §overlay): the long-lived,
 * revocable capability carried in the OBS browser-source URL
 * (`slop.html?stream=<login>&k=<key>`). One per streamer; rotating it
 * invalidates every existing browser source.
 */
@Entity("overlay_key")
class OverlayKey {
    @Id
    var id: ObjectId = ObjectId()

    /** The streamer login (the unique key). */
    var streamer: String = ""

    /** The raw key carried in the overlay URL. */
    var key: String = ""

    var createdAt: Long = 0
}
