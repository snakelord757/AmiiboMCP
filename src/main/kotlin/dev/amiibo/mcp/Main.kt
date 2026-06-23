package dev.amiibo.mcp

import dev.amiibo.mcp.api.AmiiboApiClient
import dev.amiibo.mcp.config.AppConfig
import dev.amiibo.mcp.config.TransportMode
import dev.amiibo.mcp.mcp.AmiiboMcpServerFactory
import dev.amiibo.mcp.transport.HttpTransport
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

fun main() = runBlocking {
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    val config = AppConfig.fromEnvironment()
    val api = AmiiboApiClient(
        baseUrl = config.apiBaseUrl,
        timeoutMillis = config.requestTimeoutMs,
        cacheTtlSeconds = config.cacheTtlSeconds,
    )
    val serverFactory = AmiiboMcpServerFactory(api, config)
    when (config.transport) {
        TransportMode.Stdio -> {
            val server = serverFactory.create()
            val transport = StdioServerTransport(
                input = System.`in`.asSource().buffered(),
                output = System.out.asSink().buffered(),
            )
            val session = server.createSession(transport)
            val closed = CompletableDeferred<Unit>()
            session.onClose { closed.complete(Unit) }
            closed.await()
        }
        TransportMode.Http, TransportMode.StreamableHttp -> HttpTransport(
            config = config,
            serverFactory = serverFactory,
            enableSse = false,
        ).run()
        TransportMode.Sse -> HttpTransport(
            config = config,
            serverFactory = serverFactory,
            enableSse = true,
        ).run()
    }
}
