package dev.amiibo.mcp.api

import dev.amiibo.mcp.cache.TtlCache
import dev.amiibo.mcp.domain.Amiibo
import dev.amiibo.mcp.domain.AmiiboGameInfo
import dev.amiibo.mcp.domain.AmiiboSearch
import dev.amiibo.mcp.domain.DictionaryEntry
import dev.amiibo.mcp.domain.LoadFiguresBySeriesRequest
import dev.amiibo.mcp.domain.LookupFilter
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class AmiiboApiClient(
    private val baseUrl: String,
    timeoutMillis: Long,
    cacheTtlSeconds: Long,
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }
    },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = false
    },
) {
    private val logger = LoggerFactory.getLogger(AmiiboApiClient::class.java)
    private val cache = TtlCache<String, JsonElement>(cacheTtlSeconds * 1000)

    suspend fun search(search: AmiiboSearch): List<Amiibo> {
        val params = linkedMapOf<String, String>()
        search.name?.let { params["name"] = it }
        search.head?.let { params["head"] = it }
        search.tail?.let { params["tail"] = it }
        search.type?.let { params["type"] = it }
        search.gameSeries?.let { params["gameseries"] = it }
        search.amiiboSeries?.let { params["amiiboSeries"] = it }
        search.character?.let { params["character"] = it }
        if (search.showGames == true) params["showgames"] = "true"
        if (search.showUsage == true) params["showusage"] = "true"

        val results = request("/api/amiibo/", params)
            .arrayOrObject("amiibo")
            .map { json.decodeFromJsonElement<Amiibo>(it) }
        return search.limit?.let { results.take(it) } ?: results
    }

    suspend fun getById(id: String): Amiibo? {
        val head = id.substring(0, 8)
        val tail = id.substring(8, 16)
        return search(AmiiboSearch(head = head, tail = tail, limit = 1)).firstOrNull()
    }

    suspend fun listTypes(filter: LookupFilter): List<DictionaryEntry> = dictionary("/api/type/", filter)

    suspend fun listAmiiboSeries(filter: LookupFilter): List<DictionaryEntry> = dictionary("/api/amiiboseries/", filter)

    suspend fun listCharacters(filter: LookupFilter): List<DictionaryEntry> = dictionary("/api/character/", filter)

    suspend fun listGameSeries(filter: LookupFilter = LookupFilter()): List<DictionaryEntry> =
        dictionary("/api/gameseries/", filter)

    suspend fun randomSeries(): DictionaryEntry =
        listGameSeries().randomOrNull()
            ?: throw AmiiboApiException("AmiiboAPI returned no game series entries.")

    suspend fun randomAmiibo(): Amiibo =
        search(AmiiboSearch(type = "Figure")).randomOrNull()
            ?: throw AmiiboApiException("AmiiboAPI returned no Figure amiibo entries.")

    suspend fun gameInfo(id: String): AmiiboGameInfo? {
        val head = id.substring(0, 8)
        val tail = id.substring(8, 16)
        return search(
            AmiiboSearch(
                head = head,
                tail = tail,
                showGames = true,
                showUsage = true,
                limit = 1,
            ),
        ).firstOrNull()?.toGameInfo()
    }

    suspend fun gameInfoByName(name: String): AmiiboGameInfo? =
        search(
            AmiiboSearch(
                name = name,
                showGames = true,
                showUsage = true,
                limit = 1,
            ),
        ).firstOrNull()?.toGameInfo()

    suspend fun loadFiguresBySeries(request: LoadFiguresBySeriesRequest): List<Amiibo> {
        val series = listGameSeries(LookupFilter(key = request.key, name = request.name))
        val figures = series.flatMap { entry ->
            search(
                AmiiboSearch(
                    type = "Figure",
                    gameSeries = entry.key,
                    showGames = request.showGames,
                    showUsage = request.showUsage,
                ),
            )
        }.distinctBy { it.id }
        return request.limit?.let { figures.take(it) } ?: figures
    }

    suspend fun lastUpdated(): String {
        val root = request("/api/lastupdated/")
        val obj = root.jsonObject
        return listOf("lastUpdated", "lastupdated", "timestamp", "date")
            .firstNotNullOfOrNull { key -> obj[key]?.jsonPrimitive?.content }
            ?: root.toString()
    }

    private suspend fun dictionary(path: String, filter: LookupFilter): List<DictionaryEntry> {
        val params = linkedMapOf<String, String>()
        filter.key?.let { params["key"] = it }
        filter.name?.let { params["name"] = it }
        return request(path, params)
            .arrayOrObject("amiibo")
            .map { json.decodeFromJsonElement<DictionaryEntry>(it) }
    }

    private suspend fun request(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): JsonElement {
        val cacheKey = buildString {
            append(path)
            params.toSortedMap().forEach { (key, value) -> append("|").append(key).append("=").append(value) }
        }
        val target = requestTarget(path, params)
        return cache.getOrPut(cacheKey) {
            retrying {
                val response = httpClient.get(baseUrl + path) {
                    params.forEach { (key, value) -> parameter(key, value) }
                }
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) {
                    throw AmiiboApiException("AmiiboAPI returned HTTP ${response.status.value} for $target: $body")
                }
                try {
                    json.parseToJsonElement(body)
                } catch (error: SerializationException) {
                    throw AmiiboApiException("AmiiboAPI returned invalid JSON for $target.", error)
                }
            }
        }
    }

    private fun requestTarget(path: String, params: Map<String, String>): String =
        buildString {
            append(baseUrl).append(path)
            if (params.isNotEmpty()) {
                append("?")
                append(params.toSortedMap().entries.joinToString("&") { (key, value) -> "$key=$value" })
            }
        }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (!error.isTransient()) throw error
                last = error
                val delayMs = 150L * (attempt + 1) * (attempt + 1)
                logger.warn("Transient AmiiboAPI failure on attempt {}: {}", attempt + 1, error.message)
                delay(delayMs)
            }
        }
        throw AmiiboApiException("AmiiboAPI request failed after retries.", last)
    }
}

class AmiiboApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun Amiibo.toGameInfo(): AmiiboGameInfo =
    AmiiboGameInfo(
        id = id,
        name = name,
        character = character,
        gameSeries = gameSeries,
        amiiboSeries = amiiboSeries,
        games3Ds = games3Ds,
        gamesSwitch = gamesSwitch,
        gamesWiiU = gamesWiiU,
    )

fun JsonElement.arrayOrObject(field: String): List<JsonElement> {
    val value = jsonObject[field] ?: return emptyList()
    return when (value) {
        is JsonArray -> value.jsonArray.toList()
        is JsonObject -> listOf(value)
        is JsonPrimitive -> emptyList()
    }
}

private fun Throwable.isTransient(): Boolean =
    this is HttpRequestTimeoutException ||
        this is java.io.IOException ||
        (this is AmiiboApiException && message?.contains("HTTP 5") == true)
