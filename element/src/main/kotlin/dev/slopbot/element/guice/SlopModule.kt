package dev.slopbot.element.guice

import com.google.inject.PrivateModule
import dev.slopbot.element.dao.GridFsSourceStore
import dev.slopbot.element.dao.SlopAssetDao
import dev.slopbot.element.dao.SlopAuditDao
import dev.slopbot.element.dao.SlopDao
import dev.slopbot.element.dao.SlopHistoryDao
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
import dev.slopbot.element.service.SlopEngine
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
        expose(SlopEngine::class.java)

        // The MCP surface.
        bind(OidcValidator::class.java).toProvider(SlopbotOidcConfig::class.java).asEagerSingleton()
        bind(McpTools::class.java).asEagerSingleton()
        bind(McpHandler::class.java).asEagerSingleton()

        // The websocket surfaces: the ELM's authenticated CLIENT to the pod's
        // agent-address websocket (events in, playback commands out) and the
        // pages' SERVER relay (the overlay + the deck).
        bind(PageRelay::class.java).asEagerSingleton()
        bind(PodWsProtocol::class.java).asEagerSingleton()
        bind(PodWebsocketClient::class.java).asEagerSingleton()

        // The REST auth gate.
        bind(SlopAuth::class.java).asEagerSingleton()
    }
}
