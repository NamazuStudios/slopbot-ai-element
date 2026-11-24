package dev.slopbot.element.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.slopbot.element.engine.SlopBouncer
import dev.slopbot.element.engine.SlopRejectedException
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * The MCP JSON-RPC dispatcher — the transport behind `POST /slop/api/mcp`
 * (Streamable-HTTP semantics for the first pass) and the companion SSE stream
 * (`GET /slop/api/mcp/events`). Speaks: `initialize`, `tools/list`,
 * `tools/call`. All calls require a validated streamer-scoped OIDC bearer
 * token (validated upstream by [OidcValidator]).
 */
@Singleton
class McpHandler @Inject constructor(
    private val tools: McpTools,
) {

    private val mapper = ObjectMapper()

    /** SSE listeners on the events endpoint (notifications relay). */
    val listeners = ConcurrentHashMap.newKeySet<(String) -> Unit>()

    /**
     * Handles one JSON-RPC request. Returns the response node as a Map, or
     * null for a notification (no id — no response).
     *
     * @param streamer the validated token's subject (the streamer scope)
     */
    fun handle(request: Map<*, *>?, streamer: String): Map<String, Any?>? {
        if (request == null) return error(null, -32600, "invalid request")
        val method = request["method"]?.toString()
            ?: return error(request["id"], -32600, "missing method")
        val id = request["id"]
        val params = request["params"] as? Map<*, *> ?: emptyMap<Any, Any>()

        return try {
            when (method) {
                "initialize" -> {
                    result(
                        id,
                        mapOf(
                            "protocolVersion" to "2024-11-05",
                            "capabilities" to mapOf("tools" to mapOf("listChanged" to true)),
                            "serverInfo" to mapOf("name" to "slopbot-ai-elm", "version" to "1.0.0"),
                        ),
                    )
                }
                "tools/list" -> result(id, mapOf("tools" to McpTools.TOOLS))
                "tools/call" -> {
                    val name = (params["name"] as? String)
                        ?: return error(id, -32602, "missing tool name")
                    @Suppress("UNCHECKED_CAST")
                    val arguments = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
                    val toolResult = tools.call(name, arguments, streamer)
                    result(id, toolResult)
                }
                "ping" -> result(id, emptyMap<String, Any?>())
                "notifications/initialized" -> null // a notification — no response
                else -> error(id, -32601, "method not found: $method")
            }
        } catch (e: McpToolError) {
            error(id, e.code, e.message ?: "tool error")
        } catch (e: SlopRejectedException) {
            // A bouncer rejection surfaces as the tool's result text — the agent
            // reads the reason, not a stack trace. (JSON-RPC error for protocol
            // failures; a rejected build is a normal tool outcome.)
            result(
                id,
                mapOf(
                    "content" to listOf(mapOf("type" to "text", "text" to (e.message ?: "rejected"))),
                    "isError" to true,
                ),
            )
        } catch (e: Exception) {
            error(id, -32603, e.message ?: "internal error")
        }
    }

    /** Relays a notification to the SSE listeners (listChanged and friends). */
    fun notify(message: String) {
        for (listener in listeners) {
            try {
                listener(message)
            } catch (e: Exception) {
                listeners.remove(listener)
            }
        }
    }

    private fun result(id: Any?, value: Any?, isError: Boolean = false): Map<String, Any?> =
        if (id == null) {
            emptyMap() // a notification — no response
        } else {
            val resultMap: MutableMap<String, Any?> = if (isError && value is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                (value as MutableMap<String, Any?>).also { it["isError"] = true }
            } else {
                val m = LinkedHashMap<String, Any?>()
                if (value is Map<*, *>) {
                    for (k in value.keys) m[k.toString()] = value[k]
                } else {
                    m["value"] = value
                }
                if (isError) m["isError"] = true
                m
            }
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to resultMap,
            )
        }

    private fun error(id: Any?, code: Int, message: String): Map<String, Any?> =
        mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "error" to mapOf("code" to code, "message" to message),
        )
}
