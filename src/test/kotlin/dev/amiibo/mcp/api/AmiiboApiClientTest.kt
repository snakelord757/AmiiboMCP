package dev.amiibo.mcp.api

import dev.amiibo.mcp.domain.AmiiboSearch
import dev.amiibo.mcp.domain.LookupFilter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmiiboApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AmiiboApiClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AmiiboApiClient(
            baseUrl = server.url("").toString().trimEnd('/'),
            timeoutMillis = 2_000,
            cacheTtlSeconds = 60,
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `builds AmiiboAPI search query`() = runTest {
        server.enqueue(MockResponse().setBody("""{"amiibo":[]}""").setHeader("Content-Type", "application/json"))

        client.search(AmiiboSearch(name = "Mario", gameSeries = "Super Mario", showGames = true))

        val request = server.takeRequest()
        assertEquals("/api/amiibo/", request.requestUrl?.encodedPath)
        assertEquals("Mario", request.requestUrl?.queryParameter("name"))
        assertEquals("Super Mario", request.requestUrl?.queryParameter("gameseries"))
        assertEquals("true", request.requestUrl?.queryParameter("showgames"))
    }

    @Test
    fun `parses amiibo list response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "amiibo": [
                    {
                      "amiiboSeries": "Super Smash Bros.",
                      "character": "Mario",
                      "gameSeries": "Super Mario",
                      "head": "00000000",
                      "tail": "00000002",
                      "name": "Mario",
                      "type": "Figure",
                      "image": "https://example.test/mario.png",
                      "release": {"na": "2014-11-21"}
                    }
                  ]
                }
                """.trimIndent(),
            ).setHeader("Content-Type", "application/json"),
        )

        val result = client.search(AmiiboSearch(name = "Mario"))

        assertEquals(1, result.size)
        assertEquals("0000000000000002", result.first().id)
        assertEquals("Mario", result.first().name)
    }

    @Test
    fun `normalizes object response as single entry`() {
        val root = Json.parseToJsonElement(
            """
            {
              "amiibo": {
                "key": "0x00",
                "name": "Figure"
              }
            }
            """.trimIndent(),
        )

        val entries = root.arrayOrObject("amiibo")

        assertEquals(1, entries.size)
        assertTrue(entries.first().toString().contains("Figure"))
    }

    @Test
    fun `parses dictionary response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"amiibo":[{"key":"0x00","name":"Figure"}]}"""))

        val result = client.listTypes(LookupFilter(name = "Figure"))

        assertEquals("0x00", result.single().key)
        assertEquals("Figure", result.single().name)
        assertEquals("/api/type/?name=Figure", server.takeRequest().path)
    }

    @Test
    fun `loads random series from game series endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"amiibo":[{"key":"0x010","name":"The Legend of Zelda"}]}"""))

        val result = client.randomSeries()

        assertEquals("0x010", result.key)
        assertEquals("The Legend of Zelda", result.name)
        assertEquals("/api/gameseries/", server.takeRequest().path)
    }

    @Test
    fun `random series fails clearly on empty response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"amiibo":[]}"""))

        val error = assertFailsWith<AmiiboApiException> {
            client.randomSeries()
        }

        assertEquals("AmiiboAPI returned no game series entries.", error.message)
    }

    @Test
    fun `loads random amiibo from figure search`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "amiibo": [
                    {
                      "amiiboSeries": "Super Smash Bros.",
                      "character": "Mario",
                      "gameSeries": "Super Mario",
                      "head": "00000000",
                      "tail": "00000002",
                      "name": "Mario",
                      "type": "Figure"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = client.randomAmiibo()
        val requestUrl = server.takeRequest().requestUrl

        assertEquals("Mario", result.name)
        assertEquals("0000000000000002", result.id)
        assertEquals("/api/amiibo/", requestUrl?.encodedPath)
        assertEquals("Figure", requestUrl?.queryParameter("type"))
    }

    @Test
    fun `omits false show games and usage flags`() = runTest {
        server.enqueue(MockResponse().setBody("""{"amiibo":[]}""").setHeader("Content-Type", "application/json"))

        client.search(AmiiboSearch(showGames = false, showUsage = false))

        val requestUrl = server.takeRequest().requestUrl
        assertEquals(null, requestUrl?.queryParameter("showgames"))
        assertEquals(null, requestUrl?.queryParameter("showusage"))
    }

    @Test
    fun `loads game info by id with game and usage flags`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "amiibo": [
                    {
                      "amiiboSeries": "Super Smash Bros.",
                      "character": "Mario",
                      "gameSeries": "Super Mario",
                      "head": "00000000",
                      "tail": "00000002",
                      "name": "Mario",
                      "type": "Figure",
                      "gamesSwitch": [{"gameName": "Super Smash Bros. Ultimate"}],
                      "gamesWiiU": [{"gameName": "Super Smash Bros. for Wii U"}],
                      "games3DS": [{"gameName": "Super Smash Bros. for Nintendo 3DS"}]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = client.gameInfo("0000000000000002")
        val requestUrl = server.takeRequest().requestUrl

        assertEquals("0000000000000002", result?.id)
        assertEquals("Mario", result?.name)
        assertEquals("Super Mario", result?.gameSeries)
        assertEquals("/api/amiibo/", requestUrl?.encodedPath)
        assertEquals("00000000", requestUrl?.queryParameter("head"))
        assertEquals("00000002", requestUrl?.queryParameter("tail"))
        assertEquals("true", requestUrl?.queryParameter("showgames"))
        assertEquals("true", requestUrl?.queryParameter("showusage"))
    }

    @Test
    fun `game info returns null when no exact amiibo matches`() = runTest {
        server.enqueue(MockResponse().setBody("""{"amiibo":[]}"""))

        val result = client.gameInfo("0000000000000002")

        assertNull(result)
    }

    @Test
    fun `loads figures from multiple game series keys as one list`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "amiibo": [
                    {"key": "0x010", "name": "The Legend of Zelda"},
                    {"key": "0x014", "name": "The Legend of Zelda"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "amiibo": [
                    {
                      "amiiboSeries": "The Legend of Zelda",
                      "character": "Link",
                      "gameSeries": "The Legend of Zelda",
                      "head": "01000000",
                      "tail": "00040002",
                      "name": "Link",
                      "type": "Figure"
                    },
                    {
                      "amiiboSeries": "The Legend of Zelda",
                      "character": "Zelda",
                      "gameSeries": "The Legend of Zelda",
                      "head": "01010000",
                      "tail": "00040002",
                      "name": "Zelda",
                      "type": "Figure"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "amiibo": [
                    {
                      "amiiboSeries": "The Legend of Zelda",
                      "character": "Link",
                      "gameSeries": "The Legend of Zelda",
                      "head": "01000000",
                      "tail": "00040002",
                      "name": "Link",
                      "type": "Figure"
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val result = client.loadFiguresBySeries(
            dev.amiibo.mcp.domain.LoadFiguresBySeriesRequest(name = "The Legend of Zelda"),
        )

        assertEquals(2, result.size)
        assertEquals("/api/gameseries/?name=The+Legend+of+Zelda", server.takeRequest().path)
        val firstSearch = server.takeRequest().requestUrl
        assertEquals("/api/amiibo/", firstSearch?.encodedPath)
        assertEquals("Figure", firstSearch?.queryParameter("type"))
        assertEquals("0x010", firstSearch?.queryParameter("gameseries"))
        val secondSearch = server.takeRequest().requestUrl
        assertEquals("0x014", secondSearch?.queryParameter("gameseries"))
    }
}
