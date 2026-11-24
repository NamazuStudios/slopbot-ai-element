package dev.slopbot.element.rest

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider

/**
 * CORS for the overlay surface — the OBS overlay page is cross-origin (the
 * feed lives on its own host), so the open routes answer preflights and carry
 * the origin header ("retry-tolerant" end reports come from that page too).
 */
@Provider
class SlopCorsFilter : ContainerResponseFilter {

    override fun filter(requestContext: ContainerRequestContext, responseContext: ContainerResponseContext) {
        responseContext.headers.add("Access-Control-Allow-Origin", "*")
        responseContext.headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        responseContext.headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization, Elements-SessionSecret, X-Slop-Acting-As")
    }
}
