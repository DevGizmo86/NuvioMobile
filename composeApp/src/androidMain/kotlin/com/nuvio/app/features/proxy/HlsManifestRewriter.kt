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
            val resolved = base.resolve(value)?.inheritRelativeQueryParameters(base, value) ?: return value
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

    /**
     * Signed HLS endpoints commonly put an access token on the manifest URL and use
     * relative child URIs without repeating it. RFC URI resolution drops the base
     * query, so carry missing parameters to same-origin relative resources. Explicit
     * child values win and absolute/cross-origin resources never receive credentials.
     */
    private fun HttpUrl.inheritRelativeQueryParameters(base: HttpUrl, reference: String): HttpUrl {
        if (base.querySize == 0 || !isRelativeReference(reference) || !sameOrigin(base, this)) return this
        val existingNames = queryParameterNames
        val builder = newBuilder()
        for (index in 0 until base.querySize) {
            val name = base.queryParameterName(index)
            if (name !in existingNames) {
                builder.addQueryParameter(name, base.queryParameterValue(index))
            }
        }
        return builder.build()
    }

    private fun isRelativeReference(reference: String): Boolean {
        val trimmed = reference.trimStart()
        if (trimmed.startsWith("//")) return false
        val schemeSeparator = trimmed.indexOf(':')
        if (schemeSeparator <= 0) return true
        return !trimmed.substring(0, schemeSeparator).all { character ->
            character.isLetterOrDigit() || character == '+' || character == '-' || character == '.'
        }
    }

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port
}
