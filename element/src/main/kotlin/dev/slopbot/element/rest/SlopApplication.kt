package dev.slopbot.element.rest

import dev.getelements.elements.sdk.annotation.ElementDefaultAttribute
import dev.getelements.elements.sdk.annotation.ElementServiceExport
import dev.getelements.elements.sdk.annotation.ElementServiceImplementation
import dev.getelements.elements.sdk.jakarta.rs.AuthSchemes
import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Contact
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityScheme
import io.swagger.v3.oas.annotations.security.SecuritySchemes
import jakarta.ws.rs.core.Application

/**
 * The SlopBot AI element's REST application. Every endpoint lives under the
 * element's REST root with `slop` in the path (`/slop/api/...`) — no
 * system-endpoint collisions.
 */
@ElementServiceImplementation
@ElementServiceExport(Application::class)
class SlopApplication : Application() {

    companion object {

        // Session auth ON — the platform's session filter guards the REST
        // surface (management routes enforce 401/403; overlay routes opt out
        // by never touching the session).
        @JvmField
        @ElementDefaultAttribute("true")
        val AUTH_ENABLED: String = "dev.getelements.elements.auth.enabled"

        // Every endpoint lives under the element's REST root with `slop` in the path.
        @JvmField
        @ElementDefaultAttribute("/slop/api")
        val RS_ROOT: String = "dev.getelements.elements.element.rs.root"

        @JvmField
        @ElementDefaultAttribute("/slop/ws")
        val WS_ROOT: String = "dev.getelements.elements.element.ws.root"

        // The static tree (the overlay, the deck, the landing page) serves at
        // the root — the OBS overlay is the product's face at /slop.html etc.
        @JvmField
        @ElementDefaultAttribute("/")
        val STATIC_CONTENT_URI: String = "dev.getelements.element.static.uri"

        // The OIDC issuer whose bearer tokens guard the MCP surface (the bound
        // Elements instance). Empty fails closed: every MCP call is 401 until
        // the deployment wires a real issuer.
        @JvmField
        @ElementDefaultAttribute("")
        val OIDC_ISSUER: String = "dev.slopbot.element.oidc.issuer"

        @JvmField
        @ElementDefaultAttribute("slopbot-ai")
        val OIDC_AUDIENCE: String = "dev.slopbot.element.oidc.audience"

        @JvmField
        @ElementDefaultAttribute("slop:mcp")
        val OIDC_SCOPE: String = "dev.slopbot.element.oidc.scope"

        // The thin forwarder's agent-address websocket (the ELM connects OUT to
        // it as the authenticated client). Empty = pod-less (MCP-only ELM).
        @JvmField
        @ElementDefaultAttribute("")
        val POD_WS_URL: String = "dev.slopbot.element.pod.ws.url"

        @JvmField
        @ElementDefaultAttribute("")
        val POD_WS_TOKEN: String = "dev.slopbot.element.pod.ws.token"

        // -- the pod dispatcher (Phase C item 4 / Pod Boot Mechanics) --
        @JvmField
        @ElementDefaultAttribute("")
        val CONDUCTOR_URL: String = "dev.slopbot.element.conductor.url"

        @JvmField
        @ElementDefaultAttribute("")
        val CONDUCTOR_CREDENTIAL: String = "dev.slopbot.element.conductor.credential"

        @JvmField
        @ElementDefaultAttribute("slopbot-pod")
        val CONDUCTOR_PROFILE: String = "dev.slopbot.element.conductor.profile"

        @JvmField
        @ElementDefaultAttribute("dev.getelements.conductor.kubernetes")
        val CONDUCTOR_PROVIDER: String = "dev.slopbot.element.conductor.provider"

        const val OPENAPI_TAG: String = "SlopBot AI"
    }

    override fun getClasses(): Set<Class<*>> = setOf(
        // Endpoints
        SlopResource::class.java,
        SlopSessionResource::class.java,
        SlopPodResource::class.java,
        AdminResource::class.java,
        SlopAssetAdminResource::class.java,
        McpResource::class.java,

        // Exposes the default security rules for the API (the builtin Elements
        // auth system via the attribute above).
        SlopSecurityConfig::class.java,

        // CORS for the overlay routes (the OBS page is cross-origin).
        SlopCorsFilter::class.java,
    )
}

/** The OpenAPI security scheme — the Elements session secret header. */
@OpenAPIDefinition(
    info = Info(
        title = "SlopBot AI Element",
        description = "The slop engine as a service — the ELM.",
        contact = Contact(
            url = "https://namazustudios.com",
            email = "info@namazustudios.com",
            name = "Namazu Studios",
        ),
    ),
    externalDocs = ExternalDocumentation(
        url = "https://namazustudios.com/docs",
        description = "Please see the Namazu Elements Manual for more information.",
    ),
    security = [SecurityRequirement(name = AuthSchemes.SESSION_SECRET)],
)
@SecuritySchemes(
    SecurityScheme(
        type = SecuritySchemeType.APIKEY,
        `in` = SecuritySchemeIn.HEADER,
        name = AuthSchemes.SESSION_SECRET,
        paramName = AuthSchemes.SESSION_SECRET,
        description = "Session secret required for authenticated endpoints",
    ),
)
class SlopSecurityConfig
