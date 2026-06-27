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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
            description = """
                Search amiibo by name or filters. Use this tool when the user does not provide an exact amiiboId string of 16 hex digits, or when the user asks for multiple matches.
                Send a JSON object with any useful subset of these fields: name, amiiboId, head, tail, type, gameSeries, amiiboSeries, character, showGames, showUsage, limit.
                If you have an exact full amiibo id string such as 0000000000000002, pass it as amiiboId. amiiboId is head plus tail: first 8 hex digits are head, last 8 hex digits are tail. If you only have head/tail values, pass head and tail separately. Dictionary filters accept either API keys or visible names.
                Returns a JSON array; zero, one, or many amiibo may match.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", nonBlankStringProperty("Amiibo name search term. Use for visible amiibo names, not ids.", "Mario"))
                    put("amiiboId", amiiboIdProperty("Exact amiibo id string: 16 hex digits matching ^[0-9A-Fa-f]{16}$. This is head + tail: first 8 digits are head, last 8 digits are tail. Prefer this field over legacy id."))
                    put("head", hexStringProperty("Amiibo head string: exactly 8 hex digits matching ^[0-9A-Fa-f]{8}$.", 8, "00000000"))
                    put("tail", hexStringProperty("Amiibo tail string: exactly 8 hex digits matching ^[0-9A-Fa-f]{8}$.", 8, "00000002"))
                    put("type", nonBlankStringProperty("Amiibo type key or name. Use list_amiibo_types first if the user asks for available type values.", "Figure"))
                    put("gameSeries", nonBlankStringProperty("Game series key or name. Use a value from list_amiibo_series when possible.", "The Legend of Zelda"))
                    put("amiiboSeries", nonBlankStringProperty("Amiibo series key or name. Use a value from list_amiibo_series when possible.", "Super Smash Bros."))
                    put("character", nonBlankStringProperty("Character key or name. Use a value from list_characters when possible.", "Mario"))
                    put("showGames", booleanProperty("Ask AmiiboAPI to include game compatibility details."))
                    put("showUsage", booleanProperty("Ask AmiiboAPI to include usage details."))
                    put("limit", intProperty("Maximum number of entries to return.", 1))
                },
            ),
        ) { request ->
            runTool {
                val input = json.decodeFromJsonElement<AmiiboSearch>(argumentsWithAmiiboIdAlias(request))
                val validated = validateSearch(input)
                textResult(json.encodeToString(api.search(validated)))
            }
        }

        server.addTool(
            name = "get_amiibo_by_id",
            description = """
                Fetch exactly one amiibo by full amiibo id.
                Send JSON like {"amiiboId":"0000000000000002"}. The amiiboId must be a string of exactly 16 hex digits matching ^[0-9A-Fa-f]{16}$. amiiboId is head + tail: first 8 digits are head, last 8 digits are tail.
                Do not send dictionary keys such as 0x010, names such as Mario, or separate head/tail fields to this tool. Use search_amiibo for those cases.
                Returns one JSON object or null when no amiibo matches.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("amiiboId", amiiboIdProperty())
                },
                required = listOf("amiiboId"),
            ),
        ) { request ->
            runTool {
                val id = amiiboIdArgument(request, "get_amiibo_by_id")
                validateAmiiboId(id)
                textResult(json.encodeToString(api.getById(id)))
            }
        }

        addLookupTool(
            server,
            "list_amiibo_types",
            "Return AmiiboAPI amiibo type dictionary entries.",
            keyExample = "0x00",
            nameExample = "Figure",
        ) {
            api.listTypes(it)
        }
        addLookupTool(
            server,
            "list_amiibo_series",
            "Return AmiiboAPI amiibo series dictionary entries.",
            keyExample = "0x00",
            nameExample = "Super Smash Bros.",
        ) {
            api.listAmiiboSeries(it)
        }
        addLookupTool(
            server,
            "list_characters",
            "Return AmiiboAPI character dictionary entries.",
            keyExample = "0x000",
            nameExample = "Mario",
        ) {
            api.listCharacters(it)
        }

        server.addTool(
            name = "random_series",
            description = "Return one random AmiiboAPI game series dictionary entry as a JSON object with key and name. This tool takes no arguments; send an empty JSON object {}.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            textResult(json.encodeToString(api.randomSeries()))
        }

        server.addTool(
            name = "random_amiibo",
            description = "Return one random Figure-type amiibo from AmiiboAPI as a JSON object. This tool takes no arguments; send an empty JSON object {}.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            textResult(json.encodeToString(api.randomAmiibo()))
        }

        server.addTool(
            name = "load_figures_by_series",
            description = """
                Load Figure-type amiibo for a game series.
                Send either key or name. Use key when you already have an AmiiboAPI game series key such as 0x010. Use name for natural language series names such as The Legend of Zelda.
                Optional showGames and showUsage request compatibility details. Optional limit caps the returned figure count.
                Returns one merged deduplicated JSON array of figure amiibo.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("key", dictionaryKeyProperty("AmiiboAPI game series key. Prefer this over name when known.", "0x010"))
                    put("name", nonBlankStringProperty("Game series name search term. Use when the user gives a series name instead of an API key.", "The Legend of Zelda"))
                    put("showGames", booleanProperty("Ask AmiiboAPI to include game compatibility details."))
                    put("showUsage", booleanProperty("Ask AmiiboAPI to include usage details."))
                    put("limit", intProperty("Maximum number of figures to return.", 1))
                },
            ),
        ) { request ->
            runTool {
                val input = json.decodeFromJsonElement<LoadFiguresBySeriesRequest>(requestArguments(request))
                val validated = validateLoadFiguresBySeries(normalizeLoadFiguresBySeries(input))
                textResult(json.encodeToString<List<Amiibo>>(api.loadFiguresBySeries(validated)))
            }
        }

        server.addTool(
            name = "game_info",
            description = """
                Return game compatibility information for one amiibo.
                Send exactly one of these JSON shapes: {"amiiboId":"0000000000000002"} or {"name":"Mario"}.
                amiiboId must be a string of exactly 16 hex digits matching ^[0-9A-Fa-f]{16}$. amiiboId is head + tail: first 8 digits are head, last 8 digits are tail.
                Use amiiboId for an exact lookup. Use name when the user only provided a visible amiibo name; name search returns the first matching amiibo with game compatibility details.
                Do not send dictionary keys such as 0x010 as amiiboId. Returns one JSON object or null when no amiibo matches.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("amiiboId", amiiboIdProperty())
                    put("name", nonBlankStringProperty("Visible amiibo name search term. Use this only when amiiboId is not known.", "Mario"))
                },
            ),
        ) { request ->
            runTool {
                val result = gameInfo(request)
                textResult(json.encodeToString(result))
            }
        }

        server.addTool(
            name = "get_last_updated",
            description = "Return the AmiiboAPI last updated timestamp as a JSON object with lastUpdated. This tool takes no arguments; send an empty JSON object {}.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            textResult(json.encodeToString(mapOf("lastUpdated" to api.lastUpdated())))
        }
    }

    private fun addLookupTool(
        server: Server,
        name: String,
        description: String,
        keyExample: String,
        nameExample: String,
        handler: suspend (LookupFilter) -> List<DictionaryEntry>,
    ) {
        server.addTool(
            name = name,
            description = """
                $description
                Send {} to list all entries, {"key":"$keyExample"} to fetch by exact API key, or {"name":"$nameExample"} to search by visible name.
                Use returned key/name values as inputs to search_amiibo or load_figures_by_series. Always returns a JSON array of { key, name } entries.
            """.trimIndent(),
            inputSchema = ToolSchema(properties = lookupProperties(keyExample, nameExample)),
        ) { request ->
            runTool {
                val filter = json.decodeFromJsonElement<LookupFilter>(requestArguments(request))
                textResult(json.encodeToString<List<DictionaryEntry>>(handler(filter)))
            }
        }
    }

    private fun textResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text)))

    private fun toolError(message: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(message)), isError = true)

    private suspend fun runTool(block: suspend () -> CallToolResult): CallToolResult =
        try {
            block()
        } catch (error: IllegalArgumentException) {
            toolError(error.message ?: "Invalid tool arguments.")
        } catch (error: SerializationException) {
            toolError("Invalid tool arguments: ${error.message}")
        }

    private fun requestArguments(request: CallToolRequest): JsonObject =
        request.arguments ?: buildJsonObject {}

    private fun argumentsWithAmiiboIdAlias(request: CallToolRequest): JsonObject {
        val arguments = requestArguments(request)
        validateAmiiboIdAliases(arguments)
        val amiiboId = arguments["amiiboId"] ?: return arguments
        if (arguments["id"] != null) return arguments
        return buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            put("id", amiiboId)
        }
    }

    private fun amiiboIdArgument(request: CallToolRequest, toolName: String): String {
        val arguments = requestArguments(request)
        validateAmiiboIdAliases(arguments)
        return arguments["amiiboId"]?.jsonPrimitive?.content
            ?: arguments["id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("$toolName requires amiiboId.")
    }

    private fun validateAmiiboIdAliases(arguments: JsonObject) {
        val amiiboId = arguments["amiiboId"]?.jsonPrimitive?.content
        val legacyId = arguments["id"]?.jsonPrimitive?.content
        require(amiiboId == null || legacyId == null || amiiboId == legacyId) {
            "Provide either amiiboId or legacy id, not both with different values."
        }
    }

    private suspend fun gameInfo(request: CallToolRequest) =
        gameInfo(requestArguments(request))

    private suspend fun gameInfo(arguments: JsonObject): dev.amiibo.mcp.domain.AmiiboGameInfo? {
        validateAmiiboIdAliases(arguments)
        val id = arguments["amiiboId"]?.jsonPrimitive?.contentOrNull
            ?: arguments["id"]?.jsonPrimitive?.contentOrNull
        val name = arguments["name"]?.jsonPrimitive?.contentOrNull

        require(!id.isNullOrBlank() || !name.isNullOrBlank()) {
            "game_info requires exactly one of amiiboId or name."
        }
        require(id.isNullOrBlank() || name.isNullOrBlank()) {
            "game_info accepts exactly one of amiiboId or name, not both."
        }

        return if (id != null) {
            validateAmiiboId(id)
            api.gameInfo(id)
        } else {
            api.gameInfoByName(name!!.trim())
        }
    }
}

private fun lookupProperties(keyExample: String, nameExample: String): JsonObject = buildJsonObject {
    put("key", dictionaryKeyProperty("Exact AmiiboAPI dictionary key to look up. Use this only when the key is known.", keyExample))
    put("name", nonBlankStringProperty("Dictionary entry visible name to search for. Use this for natural language names.", nameExample))
}

private fun amiiboIdProperty(description: String = "Exact amiibo id string: 16 hex digits matching ^[0-9A-Fa-f]{16}$, for example 0000000000000002. This is head + tail: first 8 digits are head, last 8 digits are tail. This is not a dictionary key such as 0x000."): JsonObject =
    hexStringProperty(description, 16, "0000000000000002")

private fun nonBlankStringProperty(description: String, example: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("minLength", 1)
    put("examples", buildJsonArray { add(example) })
}

private fun hexStringProperty(description: String, length: Int, example: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("minLength", length)
    put("maxLength", length)
    put("pattern", "^[0-9A-Fa-f]{$length}$")
    put("examples", buildJsonArray { add(example) })
}

private fun dictionaryKeyProperty(description: String, example: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("minLength", 1)
    put("pattern", "^0x[0-9A-Fa-f]+$")
    put("examples", buildJsonArray { add(example) })
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
