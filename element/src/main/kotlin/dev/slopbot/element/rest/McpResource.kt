package dev.slopbot.element.rest

import dev.getelements.elements.sdk.Element
import dev.getelements.elements.sdk.ElementSupplier
import dev.slopbot.element.mcp.McpHandler
import dev.slopbot.element.mcp.McpToolError
import dev.slopbot.element.mcp.OidcValidator
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import java.nio.charset.StandardCharsets

/**
 * The MCP transport — `POST /slop/api/mcp` (JSON-RPC; Streamable-HTTP
 * semantics for the first pass) + the SSE stream `GET /slop/api/mcp/events`.
 *
 * Auth middleware: a bearer OIDC token minted by the bound Elements instance,
 * validated per call — signature/issuer/audience/expiry. 401
 * missing/expired/foreign; 403 wrong scope. The token is streamer-scoped:
 * that streamer, and only that streamer, runs that pod.
 */
@Path("/mcp")
class McpResource {

    private val element: Element = ElementSupplier.getElementLocal(McpResource::class.java).get()

    private val handler: McpHandler by lazy { element.getServiceLocator().getInstance(McpHandler::class.java) }
    private val validator: OidcValidator by lazy { element.getServiceLocator().getInstance(OidcValidator::class.java) }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun post(@HeaderParam("Authorization") authorization: String?, body: Map<*, *>?): Response {
        // Auth middleware — every call, before any dispatch.
        val token = authorization?.removePrefix("Bearer ")?.trim()
        val verified = when (val result = validator.validate(token)) {
            is OidcValidator.Result.Ok -> result.verified
            is OidcValidator.Result.Fail -> return Response.status(result.status)
                .entity(mapOf("error" to result.reason))
                .build()
        }
        val response = handler.handle(body, verified.subject)
            ?: return Response.ok(mapOf("jsonrpc" to "2.0")).build() // a notification — empty ack
        return Response.ok(response).build()
    }

    @GET
    @Path("/events")
    @Produces("text/event-stream")
    fun events(): Response {
        val stream = StreamingOutput { output ->
            output.write("retry: 2000\n\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
            val listener: (String) -> Unit = { message ->
                output.write(message.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            handler.listeners.add(listener)
            try {
                while (true) {
                    Thread.sleep(15_000)
                    output.write(": hb\n\n".toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // connection closed
            } finally {
                handler.listeners.remove(listener)
                try {
                    output.close()
                } catch (e: Exception) {
                }
            }
        }
        return Response.ok(stream)
            .header("Cache-Control", "no-cache, no-transform")
            .build()
    }
}
