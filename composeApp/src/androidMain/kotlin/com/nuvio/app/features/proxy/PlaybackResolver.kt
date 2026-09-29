package com.nuvio.app.features.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ResolvedProxySource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
)

/** An addon URL marker, never a network endpoint. Provider code lives in installed plugins. */
internal object PlaybackResolver {
    const val BASE_URL = "https://nuvio-resolver.invalid"

    fun isRequest(url: String): Boolean =
        url.toHttpUrlOrNull()?.host == "nuvio-resolver.invalid"

    suspend fun resolve(
        url: String,
        resolvers: List<suspend (String) -> List<ResolvedProxySource>>,
    ): ResolvedProxySource = withTimeout(45_000) {
        require(isRequest(url))
        for (resolver in resolvers) {
            val results = try {
                resolver(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            results.firstOrNull { result ->
                val parsed = result.url.toHttpUrlOrNull()
                parsed != null && parsed.username.isEmpty() && parsed.password.isEmpty() &&
                    !isRequest(result.url) &&
                    LocalStreamProxy.shouldProxy(result.url, result.streamType, allHttp = true)
            }?.let { return@withTimeout it }
        }
        error("No resolver produced a playable HTTP stream")
    }
}
