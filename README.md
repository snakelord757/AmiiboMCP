# Amiibo MCP Server

A Kotlin/JVM MCP server that exposes the public [AmiiboAPI](https://www.amiiboapi.org/docs/) as read-only tools for AI clients. It uses the official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk-server`) and stays intentionally thin: tool calls are validated, cached, retried when transient network failures happen, and then forwarded to AmiiboAPI GET endpoints.

## What This Server Does

The server lets an MCP client search amiibo, fetch one amiibo by id, list AmiiboAPI dictionaries, load figures for a game series, and read the API last-updated timestamp. It does not add recommendations, ranking, private data, writes, authentication, or its own database.

Implemented transports:

- `stdio`: implemented. Use this for local MCP clients.
- `streamable-http` / `http`: implemented with the SDK Ktor `mcpStreamableHttp` helper at `/mcp`.
- `sse`: implemented with the SDK Ktor SSE helper at `/sse` when `AMIIBO_TRANSPORT=sse`.

## Requirements

- JDK 21 or newer. The Gradle toolchain is set to 21.
- Network access to `https://www.amiiboapi.org`
- Gradle wrapper from this repository

## Build, Test, Run

```bash
./gradlew build
./gradlew test
```

Run stdio mode:

```bash
AMIIBO_TRANSPORT=stdio ./gradlew run
```

Run HTTP mode:

```bash
AMIIBO_TRANSPORT=streamable-http AMIIBO_HTTP_HOST=127.0.0.1 AMIIBO_HTTP_PORT=8080 ./gradlew run
```

HTTP endpoints:

- `GET /health`
- `/mcp` managed by the Kotlin MCP SDK Streamable HTTP transport
- `/sse` managed by the Kotlin MCP SDK SSE transport when `AMIIBO_TRANSPORT=sse`

## Environment Variables

| Variable | Default | Description |
| --- | --- | --- |
| `AMIIBO_API_BASE_URL` | `https://www.amiiboapi.org` | AmiiboAPI base URL. Useful for tests or proxies. |
| `AMIIBO_CACHE_TTL_SECONDS` | `3600` | In-memory TTL cache duration. Set `0` to disable caching. |
| `AMIIBO_TRANSPORT` | `stdio` | `stdio`, `http`, `streamable-http`, or `sse`. |
| `AMIIBO_HTTP_PORT` | `8080` | HTTP server port. |
| `AMIIBO_HTTP_HOST` | `0.0.0.0` | HTTP bind host. |
| `AMIIBO_REQUEST_TIMEOUT_MS` | `10000` | Ktor request, connect, and socket timeout. |
| `LOG_LEVEL` | `INFO` | Logback root log level. Logs go to stderr for stdio safety. |

## MCP Client Configuration

Build the jar first:

```bash
./gradlew build
```

Example stdio config:

```json
{
  "mcpServers": {
    "amiibo": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/amiibo-mcp/build/libs/amiibo-mcp-0.1.0.jar"
      ],
      "env": {
        "AMIIBO_TRANSPORT": "stdio",
        "AMIIBO_CACHE_TTL_SECONDS": "3600",
      }
    }
  }
}
```

The same example is available at `examples/mcp-client-config.json`.

If your MCP client supports `cwd`, you can point it at this project folder and keep the jar path relative:

```json
{
  "mcpServers": {
    "amiibo": {
      "command": "java",
      "args": [
        "-jar",
        "build\\libs\\amiibo-mcp-0.1.0.jar"
      ],
      "cwd": "D:\\AmiiboMCP",
      "env": {
        "AMIIBO_TRANSPORT": "stdio"
      }
    }
  }
}
```

## Available Tools

Tool results are returned as MCP `TextContent` containing JSON text. Collection-style tools return JSON arrays even when AmiiboAPI returns a single object for an exact `key` lookup.

### `search_amiibo`

Searches `/api/amiibo/` with combinable filters. The dictionary filters `type`, `gameSeries`, `amiiboSeries`, and `character` accept either AmiiboAPI keys or names.

Inputs:

- `name`, `id`, `head`, `tail`, `type`, `gameSeries`, `amiiboSeries`, `character`: optional strings
- `showGames`, `showUsage`: optional booleans
- `limit`: optional integer `1` or greater

Example:

```json
{
  "name": "Mario",
  "showGames": true,
  "limit": 5
}
```

Output is a JSON array of normalized amiibo objects. Searches may return zero, one, or many entries. Objects include fields such as `amiiboSeries`, `character`, `gameSeries`, `head`, `tail`, `id`, `name`, `type`, `image`, `imgwebp`, `release`, and optional game fields. Release dates may be `null`; `showGames` and `showUsage` can add platform game arrays such as `games3DS`, `gamesSwitch`, and `gamesWiiU`.

### `get_amiibo_by_id`

Fetches one amiibo by exact 16-character hexadecimal id. The id is split into AmiiboAPI `head` and `tail`.

Input:

```json
{ "id": "0000000000000002" }
```

Output is one amiibo object or `null` when no exact match exists.

### `list_amiibo_types`

Calls `/api/type/`.

Inputs:

```json
{ "key": "0x00" }
```

or:

```json
{ "name": "Figure" }
```

Output is an array of `{ "key": "...", "name": "..." }`. A `key` lookup normally returns zero or one entry; a `name` lookup may return zero, one, or many entries.

### `load_figures_by_series`

Resolves AmiiboAPI game series entries by `key` or `name`, loads Figure-type amiibo for each resolved game series key, and returns one merged deduplicated JSON array.

Inputs:

- `key`: optional AmiiboAPI game series key, for example `0x010`
- `name`: optional game series name search term, for example `The Legend of Zelda`
- `showGames`, `showUsage`: optional booleans passed to AmiiboAPI figure searches
- `limit`: optional integer `1` or greater

Either `key` or `name` is required. A `name` lookup may resolve to multiple game series keys; the tool loads figures for all resolved keys and returns one JSON array of figure amiibo.

### `list_amiibo_series`

Calls `/api/amiiboseries/`. Accepts optional `key` or `name`. Output is always an array of `{ "key": "...", "name": "..." }`. A `key` lookup normally returns zero or one entry; a `name` lookup may return zero, one, or many entries.

### `list_characters`

Calls `/api/character/`. Accepts optional `key` or `name`. Output is always an array of `{ "key": "...", "name": "..." }`. A `key` lookup normally returns zero or one entry; a `name` lookup may return zero, one, or many entries.

### `get_last_updated`

Calls `/api/lastupdated/`.

Input:

```json
{}
```

Output:

```json
{ "lastUpdated": "..." }
```

## HTTP MCP Example

Start the server:

```bash
AMIIBO_TRANSPORT=streamable-http AMIIBO_HTTP_HOST=127.0.0.1 ./gradlew run
```

List tools:

```bash
curl -s http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

Call a tool:

```bash
curl -s http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_amiibo","arguments":{"name":"Zelda","limit":3}}}'
```

## Caching and Retries

All successful AmiiboAPI JSON responses are cached in memory by endpoint and sorted query parameters. The cache is per process and has no persistence. Transient IO, timeout, and HTTP 5xx failures are retried three times with a small backoff. Validation errors are returned through the Kotlin MCP SDK tool error flow without calling AmiiboAPI.

## Troubleshooting

- `Unsupported AMIIBO_TRANSPORT`: use `stdio`, `http`, or `streamable-http`.
- SSE client cannot connect: start the server with `AMIIBO_TRANSPORT=sse` and connect to `/sse`.
- `id must be a 16-character hexadecimal amiibo id`: pass an exact amiibo id, or use `search_amiibo` with separate `head` and `tail`.
- Empty results for a known name: try a broader `name` value and remove dictionary filters.
- Stdio client hangs: make sure logs are on stderr and the client command points at the built jar.

## Testing

Tests cover query building, response parsing, object-or-array normalization, input validation, and TTL cache behavior:

```bash
./gradlew test
```

## Project Structure

```text
src/main/kotlin/dev/amiibo/mcp/api        AmiiboAPI client
src/main/kotlin/dev/amiibo/mcp/cache      In-memory TTL cache
src/main/kotlin/dev/amiibo/mcp/config     Environment configuration
src/main/kotlin/dev/amiibo/mcp/domain     Serializable domain models
src/main/kotlin/dev/amiibo/mcp/mcp        Kotlin MCP SDK server and tool registration
src/main/kotlin/dev/amiibo/mcp/transport  stdio and HTTP bootstraps
src/test/kotlin/dev/amiibo/mcp            Unit tests
examples/                                MCP client config example
```

## AmiiboAPI Attribution

This project is an independent wrapper around the public AmiiboAPI. See the official AmiiboAPI documentation at [amiiboapi.org/docs](https://www.amiiboapi.org/docs/).
