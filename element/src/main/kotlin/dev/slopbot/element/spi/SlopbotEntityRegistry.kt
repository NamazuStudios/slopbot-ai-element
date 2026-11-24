package dev.slopbot.element.spi

import dev.getelements.elements.sdk.dao.EntityRegistry
import dev.getelements.elements.sdk.annotation.ElementServiceExport
import dev.getelements.elements.sdk.annotation.ElementServiceImplementation
import dev.slopbot.element.model.Slop
import dev.slopbot.element.model.SlopAsset
import dev.slopbot.element.model.SlopAudit
import dev.slopbot.element.dao.SlopModification

/**
 * Declares this element's MongoDB entity classes to the platform — the
 * pre-registration that makes the element-owned Morphia entities resolvable
 * (the reference's `slops` / `slopAssets` / `slop_audit` / `slop_mod_history`
 * collections, all in the shared `slopbot` database).
 */
@ElementServiceImplementation
@ElementServiceExport(EntityRegistry::class)
class SlopbotEntityRegistry : EntityRegistry {

    override fun entityClasses(): List<Class<*>> = listOf(
        Slop::class.java,
        SlopAsset::class.java,
        SlopAudit::class.java,
        SlopModification::class.java,
    )
}
