package dev.amiibo.mcp.validation

import dev.amiibo.mcp.domain.AmiiboSearch
import dev.amiibo.mcp.domain.LoadFiguresBySeriesRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val hex = Regex("^[0-9a-fA-F]+$")
private val json = Json { ignoreUnknownKeys = true }

fun validateSearch(input: AmiiboSearch): AmiiboSearch {
    input.id?.let {
        require(it.length == 16 && hex.matches(it)) {
            "amiiboId must be a 16-character hexadecimal amiibo id."
        }
    }
    input.head?.let {
        require(it.length == 8 && hex.matches(it)) { "head must be an 8-character hexadecimal value." }
    }
    input.tail?.let {
        require(it.length == 8 && hex.matches(it)) { "tail must be an 8-character hexadecimal value." }
    }
    input.limit?.let {
        require(it >= 1) { "limit must be at least 1." }
    }
    val fromId = input.id
    return if (fromId != null) {
        input.copy(head = fromId.substring(0, 8), tail = fromId.substring(8, 16))
    } else {
        input
    }
}

fun validateAmiiboId(id: String): Pair<String, String> {
    require(id.length == 16 && hex.matches(id)) {
        "amiiboId must be a 16-character hexadecimal amiibo id."
    }
    return id.substring(0, 8) to id.substring(8, 16)
}

fun validateLoadFiguresBySeries(input: LoadFiguresBySeriesRequest): LoadFiguresBySeriesRequest {
    require(!input.key.isNullOrBlank() || !input.name.isNullOrBlank()) {
        "load_figures_by_series requires key or name."
    }
    input.limit?.let {
        require(it >= 1) { "limit must be at least 1." }
    }
    return input
}

fun normalizeLoadFiguresBySeries(input: LoadFiguresBySeriesRequest): LoadFiguresBySeriesRequest =
    input.copy(
        key = extractDictionaryField(input.key, "key") ?: input.key,
        name = extractDictionaryField(input.name, "name") ?: input.name,
    )

private fun extractDictionaryField(value: String?, field: String): String? {
    if (value.isNullOrBlank()) return null
    val root = try {
        json.parseToJsonElement(value)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    return extractDictionaryField(root, field)
}

private fun extractDictionaryField(element: JsonElement, field: String): String? =
    when (element) {
        is JsonObject -> {
            val obj = element.jsonObject
            obj[field]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull?.let { extractDictionaryField(it, field) }
                ?: obj["content"]?.let { extractDictionaryField(it, field) }
        }
        is JsonArray -> element.jsonArray.firstNotNullOfOrNull { extractDictionaryField(it, field) }
        else -> null
    }
