package dev.amiibo.mcp.transport

import dev.amiibo.mcp.config.AppConfig
import dev.amiibo.mcp.mcp.AmiiboMcpServerFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import org.slf4j.LoggerFactory

class HttpTransport(
    private val config: AppConfig,
    private val serverFactory: AmiiboMcpServerFactory,
    private val enableSse: Boolean,
) {
    private val logger = LoggerFactory.getLogger(HttpTransport::class.java)

    fun run(wait: Boolean = true) {
        logger.info("Starting amiibo-mcp HTTP transport on {}:{}", config.httpHost, config.httpPort)
        embeddedServer(Netty, host = config.httpHost, port = config.httpPort) {
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
                allowNonSimpleContentTypes = true
                allowHeader(HttpHeaders.ContentType)
                allowHeader("Mcp-Session-Id")
                allowHeader("Mcp-Protocol-Version")
                exposeHeader("Mcp-Session-Id")
                exposeHeader("Mcp-Protocol-Version")
            }
            if (enableSse) {
                install(SSE)
            }
            mcpStreamableHttp(path = "/mcp", allowedHosts = config.allowedHosts) { serverFactory.create() }
            routing {
                get("/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                }
                if (enableSse) {
                    route("/sse") {
                        mcp(allowedHosts = config.allowedHosts) { serverFactory.create() }
                    }
                }
            }
        }.start(wait = wait)
    }
}
