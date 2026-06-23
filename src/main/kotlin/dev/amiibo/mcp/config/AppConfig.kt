package dev.amiibo.mcp.config

data class AppConfig(
    val apiBaseUrl: String = env("AMIIBO_API_BASE_URL", "https://www.amiiboapi.org").trimEnd('/'),
    val cacheTtlSeconds: Long = envLong("AMIIBO_CACHE_TTL_SECONDS", 3600),
    val transport: TransportMode = TransportMode.from(env("AMIIBO_TRANSPORT", "stdio")),
    val httpPort: Int = envInt("AMIIBO_HTTP_PORT", 8080),
    val httpHost: String = env("AMIIBO_HTTP_HOST", "0.0.0.0"),
    val allowedHosts: List<String> = envList("AMIIBO_ALLOWED_HOSTS", listOf("localhost", "127.0.0.1", "[::1]")),
    val requestTimeoutMs: Long = envLong("AMIIBO_REQUEST_TIMEOUT_MS", 10_000),
) {
    companion object {
        fun fromEnvironment(): AppConfig = AppConfig()
    }
}

enum class TransportMode {
    Stdio,
    Http,
    Sse,
    StreamableHttp;

    companion object {
        fun from(value: String): TransportMode = when (value.trim().lowercase()) {
            "stdio" -> Stdio
            "http", "streamable-http" -> StreamableHttp
            "sse" -> Sse
            else -> error("Unsupported AMIIBO_TRANSPORT '$value'. Use stdio, http, streamable-http, or sse.")
        }
    }
}

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

private fun envList(name: String, default: List<String>): List<String> =
    System.getenv(name)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?: default

private fun envInt(name: String, default: Int): Int = env(name, default.toString()).toIntOrNull() ?: default

private fun envLong(name: String, default: Long): Long = env(name, default.toString()).toLongOrNull() ?: default
