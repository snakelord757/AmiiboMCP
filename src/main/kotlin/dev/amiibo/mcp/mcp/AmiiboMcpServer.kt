package dev.amiibo.mcp.mcp

import dev.amiibo.mcp.api.AmiiboApiClient
import dev.amiibo.mcp.config.AppConfig
import dev.amiibo.mcp.domain.Amiibo
import dev.amiibo.mcp.domain.AmiiboSearch
import dev.amiibo.mcp.domain.DictionaryEntry
import dev.amiibo.mcp.domain.LoadFiguresBySeriesRequest
import dev.amiibo.mcp.domain.LookupFilter
import dev.amiibo.mcp.validation.normalizeLoadFiguresBySeries
import dev.amiibo.mcp.validation.validateAmiiboId
import dev.amiibo.mcp.validation.validateLoadFiguresBySeries
import dev.amiibo.mcp.validation.validateSearch
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AmiiboMcpServerFactory(
    private val api: AmiiboApiClient,
    private val config: AppConfig,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    },
) {
    fun create(): Server {
        val server = Server(
            serverInfo = Implementation(name = "amiibo-mcp", version = "0.1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )
        registerTools(server)
        return server
    }

    private fun registerTools(server: Server) {
        server.addTool(
            name = "search_amiibo",
            description = "Search AmiiboAPI amiibo entries with combinable filters. Returns a JSON array; zero, one, or many amiibo may match. Dictionary filters such as type, gameSeries, amiiboSeries, and character accept either AmiiboAPI keys or names.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", stringProperty("Amiibo name search term, for example Mario."))
                    put("id", stringProperty("16-character amiibo id. If provided, it is split into head and tail."))
                    put("head", stringProperty("8-character amiibo head hex value."))
                    put("tail", stringProperty("8-character amiibo tail hex value."))
                    put("type", stringProperty("Amiibo type key or name."))
                    put("gameSeries", stringProperty("Game series key or name."))
                    put("amiiboSeries", stringProperty("Amiibo series key or name."))
                    put("character", stringProperty("Character key or name."))
                    put("showGames", booleanProperty("Ask AmiiboAPI to include game compatibility details."))
                    put("showUsage", booleanProperty("Ask AmiiboAPI to include usage details."))
                    put("limit", intProperty("Maximum number of entries to return.", 1))
                },
            ),
        ) { request ->
            val input = json.decodeFromJsonElement<AmiiboSearch>(requestArguments(request))
            val validated = validateSearch(input)
            textResult(json.encodeToString(api.search(validated)))
        }

        server.addTool(
            name = "get_amiibo_by_id",
            description = "Fetch one amiibo by exact 16-character id. Returns one JSON object or null when no amiibo matches.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("id", stringProperty("16-character amiibo id.")) },
                required = listOf("id"),
            ),
        ) { request ->
            val id = requestArguments(request)["id"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("get_amiibo_by_id requires id.")
            validateAmiiboId(id)
            textResult(json.encodeToString(api.getById(id)))
        }

        addLookupTool(server, "list_amiibo_types", "Return AmiiboAPI amiibo type dictionary entries.") {
            api.listTypes(it)
        }
        addLookupTool(server, "list_amiibo_series", "Return AmiiboAPI amiibo series dictionary entries.") {
            api.listAmiiboSeries(it)
        }
        addLookupTool(server, "list_characters", "Return AmiiboAPI character dictionary entries.") {
            api.listCharacters(it)
        }

        server.addTool(
            name = "random_series",
            description = "Return one random AmiiboAPI game series dictionary entry as a JSON object with key and name.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            textResult(json.encodeToString(api.randomSeries()))
        }

        server.addTool(
            name = "random_amiibo",
            description = "Return one random Figure-type amiibo from AmiiboAPI as a JSON object.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            textResult(json.encodeToString(api.randomAmiibo()))
        }

        server.addTool(
            name = "load_figures_by_series",
            description = "Resolve AmiiboAPI game series entries by key or name, load Figure-type amiibo for each resolved series key, and return one merged deduplicated JSON array of figures.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("key", stringProperty("AmiiboAPI game series key, for example 0x010."))
                    put("name", stringProperty("Game series name search term, for example The Legend of Zelda."))
                    put("showGames", booleanProperty("Ask AmiiboAPI to include game compatibility details."))
                    put("showUsage", booleanProperty("Ask AmiiboAPI to include usage details."))
                    put("limit", intProperty("Maximum number of figures to return.", 1))
                },
            ),
        ) { request ->
            val input = json.decodeFromJsonElement<LoadFiguresBySeriesRequest>(requestArguments(request))
            val validated = validateLoadFiguresBySeries(normalizeLoadFiguresBySeries(input))
            textResult(json.encodeToString<List<Amiibo>>(api.loadFiguresBySeries(validated)))
        }

        server.addTool(
            name = "game_info",
            description = "Return game compatibility information for one amiibo by exact 16-character id. Returns one JSON object or null when no amiibo matches.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("id", stringProperty("16-character amiibo id.")) },
                required = listOf("id"),
            ),
        ) { request ->
            val id = requestArguments(request)["id"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("game_info requires id.")
            validateAmiiboId(id)
            textResult(json.encodeToString(api.gameInfo(id)))
        }

        server.addTool(
            name = "get_last_updated",
            description = "Return the AmiiboAPI last updated timestamp as a JSON object with lastUpdated.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            textResult(json.encodeToString(mapOf("lastUpdated" to api.lastUpdated())))
        }
    }

    private fun addLookupTool(
        server: Server,
        name: String,
        description: String,
        handler: suspend (LookupFilter) -> List<DictionaryEntry>,
    ) {
        server.addTool(
            name = name,
            description = "$description Always returns a JSON array of { key, name } entries. A key lookup normally returns zero or one entry; a name lookup may return zero, one, or many entries.",
            inputSchema = ToolSchema(properties = lookupProperties()),
        ) { request ->
            val filter = json.decodeFromJsonElement<LookupFilter>(requestArguments(request))
            textResult(json.encodeToString<List<DictionaryEntry>>(handler(filter)))
        }
    }

    private fun textResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text)))

    private fun requestArguments(request: CallToolRequest): JsonObject =
        request.arguments ?: buildJsonObject {}
}

private fun lookupProperties(): JsonObject = buildJsonObject {
    put("key", stringProperty("Dictionary key to look up."))
    put("name", stringProperty("Dictionary name to search for."))
}

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun intProperty(description: String, minimum: Int): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", minimum)
}
