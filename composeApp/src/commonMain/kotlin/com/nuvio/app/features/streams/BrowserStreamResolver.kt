package com.nuvio.app.features.streams

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ResolvedBrowserStream(
    val url: String,
    val audioUrl: String? = null,
    val name: String? = null,
    val description: String? = null,
    val streamType: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val bingeGroup: String? = null,
    val subtitles: List<StreamSubtitle> = emptyList(),
)

internal fun isSupportedBrowserStreamUrl(url: String): Boolean {
    val normalized = url.trim().lowercase()
    return normalized.startsWith("https://toastflix.stremio-italia.eu/extractor/")
}

internal object BrowserStreamPayloadParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseStreamJson(payload: String): ResolvedBrowserStream? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val url = root.string("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        val hints = root.objectValue("behaviorHints")
        val proxyHeaders = hints?.objectValue("proxyHeaders")
        return ResolvedBrowserStream(
            url = url,
            audioUrl = root.string("audioUrl")?.takeIf(String::isNotBlank),
            name = root.string("name"),
            description = root.string("description") ?: root.string("title"),
            streamType = root.string("type"),
            requestHeaders = proxyHeaders?.objectValue("request").stringMap(),
            responseHeaders = proxyHeaders?.objectValue("response").stringMap(),
            bingeGroup = hints?.string("bingeGroup"),
            subtitles = (root["subtitles"] as? JsonArray)
                ?.mapNotNull { item ->
                    val subtitle = item as? JsonObject ?: return@mapNotNull null
                    val subtitleUrl = subtitle.string("url")?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    StreamSubtitle(
                        url = subtitleUrl,
                        language = subtitle.string("lang")
                            ?: subtitle.string("language")
                            ?: "Unknown",
                        name = subtitle.string("name") ?: subtitle.string("title"),
                        headers = subtitle.objectValue("headers").stringMap().takeIf { it.isNotEmpty() },
                    )
                }
                .orEmpty(),
        )
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.objectValue(name: String): JsonObject? =
        this[name] as? JsonObject

    private fun JsonObject?.stringMap(): Map<String, String> =
        this?.entries
            ?.mapNotNull { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { key to it }
            }
            ?.toMap()
            .orEmpty()
}

@Composable
internal expect fun BrowserStreamResolver(
    url: String,
    onResolved: (ResolvedBrowserStream) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
)
