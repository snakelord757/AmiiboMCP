package dev.amiibo.mcp.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Amiibo(
    val amiiboSeries: String? = null,
    val character: String? = null,
    val gameSeries: String? = null,
    val head: String,
    val tail: String,
    val name: String,
    val type: String? = null,
    val image: String? = null,
    val imgwebp: String? = null,
    val release: ReleaseDates? = null,
    @SerialName("games3DS") val games3Ds: JsonElement? = null,
    val gamesSwitch: JsonElement? = null,
    @SerialName("gamesWiiU") val gamesWiiU: JsonElement? = null,
) {
    val id: String
        get() = head + tail
}

@Serializable
data class ReleaseDates(
    val au: String? = null,
    val eu: String? = null,
    val jp: String? = null,
    val na: String? = null,
)

@Serializable
data class DictionaryEntry(
    val key: String,
    val name: String,
)

@Serializable
data class AmiiboSearch(
    val name: String? = null,
    val id: String? = null,
    val head: String? = null,
    val tail: String? = null,
    val type: String? = null,
    val gameSeries: String? = null,
    val amiiboSeries: String? = null,
    val character: String? = null,
    val showGames: Boolean? = null,
    val showUsage: Boolean? = null,
    val limit: Int? = null,
)

@Serializable
data class LookupFilter(
    val key: String? = null,
    val name: String? = null,
)

@Serializable
data class LoadFiguresBySeriesRequest(
    val key: String? = null,
    val name: String? = null,
    val showGames: Boolean? = null,
    val showUsage: Boolean? = null,
    val limit: Int? = null,
)
