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

        // The thin forwarder's playback relay (the pod's webserver). Empty
        // disables the relay; see WorkerRelay.
        @JvmField
        @ElementDefaultAttribute("")
        val WORKER_RELAY_URL: String = "dev.slopbot.element.worker.relay.url"

        @JvmField
        @ElementDefaultAttribute("")
        val WORKER_RELAY_TOKEN: String = "dev.slopbot.element.worker.relay.token"

        const val OPENAPI_TAG: String = "SlopBot AI"
    }

    override fun getClasses(): Set<Class<*>> = setOf(
        // Endpoints
        SlopResource::class.java,
        AdminResource::class.java,
        McpResource::class.java,
        WorkerResource::class.java,

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
