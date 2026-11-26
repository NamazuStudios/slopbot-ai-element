package dev.slopbot.element.guice

import com.google.inject.PrivateModule
import dev.slopbot.element.dao.GridFsSourceStore
import dev.slopbot.element.dao.SlopAssetDao
import dev.slopbot.element.dao.SlopAuditDao
import dev.slopbot.element.dao.SlopAuditor
import dev.slopbot.element.dao.SlopDao
import dev.slopbot.element.dao.SlopHistoryDao
import dev.slopbot.element.dao.SlopPodLeaseDao
import dev.slopbot.element.dao.StandardSlopPodLeaseDao
import dev.slopbot.element.dao.SourceStore
import dev.slopbot.element.dao.StandardSlopAssetDao
import dev.slopbot.element.dao.StandardSlopAuditDao
import dev.slopbot.element.dao.StandardSlopDao
import dev.slopbot.element.dao.StandardSlopHistoryDao
import dev.slopbot.element.engine.SlopConfig
import dev.slopbot.element.engine.SlopEvents
import dev.slopbot.element.mcp.McpHandler
import dev.slopbot.element.mcp.McpTools
import dev.slopbot.element.mcp.OidcValidator
import dev.slopbot.element.rest.SlopAuth
import dev.slopbot.element.service.SlopAssetAdmin
import dev.slopbot.element.service.SlopEngine
import dev.slopbot.element.service.SlopPodRegistry
import dev.slopbot.element.spi.SlopbotDatabase
import dev.slopbot.element.spi.SlopbotDatabaseProvider
import dev.slopbot.element.spi.SlopbotOidcConfig
import dev.slopbot.element.ws.PageRelay
import dev.slopbot.element.ws.PodWebsocketClient
import dev.slopbot.element.ws.PodWsProtocol

/**
 * The element's Guice wiring — a PrivateModule; the surface other Elements (or
 * the worker's runtime) consume is exposed, the rest stays private.
 */
class SlopModule : PrivateModule() {

    override fun configure() {
        // The element's own Datastore on the shared `slopbot` database.
        bind(SlopbotDatabase::class.java).toProvider(SlopbotDatabaseProvider::class.java).asEagerSingleton()

        // The store layer (the data authority).
        bind(SourceStore::class.java).to(GridFsSourceStore::class.java)
        bind(SlopDao::class.java).to(StandardSlopDao::class.java)
        bind(SlopAssetDao::class.java).to(StandardSlopAssetDao::class.java)
        bind(SlopAuditDao::class.java).to(StandardSlopAuditDao::class.java)
        bind(SlopHistoryDao::class.java).to(StandardSlopHistoryDao::class.java)

        // The engine + config.
        bind(SlopEvents::class.java).asEagerSingleton()
        bind(SlopConfig::class.java).asEagerSingleton()
        bind(SlopEngine::class.java).asEagerSingleton()

        // The asset-admin surface (upload/replace/retire/delete/reassign).
        bind(SlopAuditor::class.java).asEagerSingleton()
        bind(SlopAssetAdmin::class.java).asEagerSingleton()

        // The MCP surface.
        // NOT an eager singleton: the provider resolves the element-local
        // attributes via ElementSupplier, which is only available once the
        // element's scope is active (request time) — eager construction
        // during injector creation throws SdkElementNotFoundException.
        bind(OidcValidator::class.java).toProvider(SlopbotOidcConfig::class.java)
        bind(McpTools::class.java).asEagerSingleton()
        bind(McpHandler::class.java).asEagerSingleton()

        // The websocket surfaces: the ELM's authenticated CLIENT to the pod's
        // agent-address websocket (events in, playback commands out) and the
        // pages' SERVER relay (the overlay + the deck).
        bind(PageRelay::class.java).asEagerSingleton()
        bind(PodWsProtocol::class.java).asEagerSingleton()
        bind(PodWebsocketClient::class.java).asEagerSingleton()

        // The REST auth gate.
        //
        // NOT an eager singleton: SlopAuth injects the platform's
        // UserService, which is REQUEST-SCOPED (the platform resolves the
        // acting User against the current ElementScope). Constructing it at
        // injector-creation time is broken in BOTH directions:
        //
        //  - On a fresh boot (no request scope active during the deployment
        //    load) the eager UserService resolution throws
        //    IllegalStateException ("Not in scope.") and the whole element
        //    fails to load — seen live on 3.9.0-rc-9 after a `kill 1`
        //    restart: Guice ErrorInCustomProvider at ScopedServicesModule
        //    while locating UserService.
        //  - When the load DOES run inside a request (a deployment PUT —
        //    which is how every load via the REST API happens), the
        //    resolution succeeds against THAT request's auth scope and the
        //    frozen identity is baked into the singleton forever: an
        //    anonymous request then executed as the loading admin
        //    (requireSuperuser passed with actor=admin-…, confirmed via the
        //    element's audit rows).
        //
        // Resolved lazily through the ServiceLocator per request instead, so
        // each request constructs SlopAuth inside its own scope.
        bind(SlopPodLeaseDao::class.java).to(StandardSlopPodLeaseDao::class.java)
        bind(SlopPodRegistry::class.java).asEagerSingleton()
        bind(SlopAuth::class.java)

        // Expose every service the element's own classes resolve through the
        // SDK ServiceLocator (Element.getServiceLocator().getInstance(...)).
        // This is half of the requirement, paired with the @ElementService
        // declarations on package-info.java (see the comment there for the
        // full mechanism): the module's expose() surfaces the private binding
        // into GuiceSpiModule's namespace, and GuiceSpiModule lifts each
        // @ElementService-declared key to the element injector's top level —
        // the only place GuiceServiceLocator.getExistingBinding looks. A key
        // bound but NOT exposed here makes the element fail at LOAD time
        // (GuiceSpiModule's parent-fallback throws SdkException: no parent
        // element provides an element-local service); a key exposed here but
        // not declared on the package made every REST request fail with
        // SdkServiceNotFoundException (seen live on 3.9.0-rc-9).
        expose(SlopEngine::class.java)
        expose(SlopPodRegistry::class.java)
        expose(SlopEvents::class.java)
        expose(SlopDao::class.java)
        expose(SlopAuditDao::class.java)
        expose(SlopAuditor::class.java)
        expose(SlopAssetAdmin::class.java)
        expose(SlopAuth::class.java)
        expose(McpHandler::class.java)
        expose(OidcValidator::class.java)
        expose(PodWebsocketClient::class.java)
        expose(PageRelay::class.java)
    }
}
