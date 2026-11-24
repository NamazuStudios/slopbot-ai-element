package dev.slopbot.element.spi

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.element.mcp.OidcValidator
import dev.slopbot.element.rest.SlopApplication
import jakarta.inject.Provider

/**
 * Builds the MCP surface's [OidcValidator] from the element's own attributes:
 * the issuer URL is injected via `dev.slopbot.element.oidc.issuer` (with the
 * audience + scope alongside). Empty issuer → the validator fails closed
 * (every MCP call is 401 until the deployment wires a real issuer).
 */
class SlopbotOidcConfig : Provider<OidcValidator> {

    override fun get(): OidcValidator {
        val element: Element = ElementSupplier.getElementLocal(SlopbotOidcConfig::class.java).get()
        val attributes = element.getElementRecord().attributes()
        fun attribute(name: String): String? =
            attributes.getAttributeOptional(name)?.toString()?.takeIf { it.isNotEmpty() }
        return OidcValidator(
            issuer = attribute(SlopApplication.OIDC_ISSUER),
            audience = attribute(SlopApplication.OIDC_AUDIENCE) ?: OidcValidator.DEFAULT_AUDIENCE,
            requiredScope = attribute(SlopApplication.OIDC_SCOPE) ?: OidcValidator.DEFAULT_SCOPE,
        )
    }
}
