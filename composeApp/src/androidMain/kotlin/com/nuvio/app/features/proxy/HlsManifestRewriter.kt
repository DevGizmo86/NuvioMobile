package com.nuvio.app.features.proxy

import okhttp3.HttpUrl

/** Rewrites both playlist entries and quoted URI attributes (keys, maps, renditions). */
internal object HlsManifestRewriter {
    private val uriAttribute = Regex("URI=\"([^\"]*)\"")

    fun rewrite(text: String, base: HttpUrl, route: (HttpUrl) -> String): String {
        require(text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) {
            "Invalid HLS manifest"
        }
        fun rewriteUri(value: String): String {
            require(!value.contains("{\$")) { "HLS variables are not supported by the local proxy" }
            // Keep data URIs and player-specific key schemes untouched.
            val resolved = base.resolve(value) ?: return value
            return route(resolved)
        }
        return text.removePrefix("\uFEFF").lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> uriAttribute.replace(line) { match ->
                    "URI=\"${rewriteUri(match.groupValues[1])}\""
                }
                else -> rewriteUri(trimmed)
            }
        }
    }
}
