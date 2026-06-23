package dev.amiibo.mcp.validation

import dev.amiibo.mcp.domain.AmiiboSearch
import dev.amiibo.mcp.domain.LoadFiguresBySeriesRequest

private val hex = Regex("^[0-9a-fA-F]+$")

fun validateSearch(input: AmiiboSearch): AmiiboSearch {
    input.id?.let {
        require(it.length == 16 && hex.matches(it)) { "id must be a 16-character hexadecimal amiibo id." }
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
    require(id.length == 16 && hex.matches(id)) { "id must be a 16-character hexadecimal amiibo id." }
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
