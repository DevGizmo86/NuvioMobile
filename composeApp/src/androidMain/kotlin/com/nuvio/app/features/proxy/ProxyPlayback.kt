package com.nuvio.app.features.proxy

import android.content.Context
import com.nuvio.app.features.plugins.PluginRepository
import kotlinx.coroutines.TimeoutCancellationException
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ProxyPreferences {
    fun preferences(context: Context) = context.applicationContext.getSharedPreferences("nuvio_local_proxy", Context.MODE_PRIVATE)
    fun mode(context: Context): String = preferences(context).getString("mode", "off") ?: "off"
}

internal data class ProxyPlayback(
    val videoUrl: String,
    val audioUrl: String?,
    val proxyActive: Boolean = false,
    val playerHeaders: Map<String, String> = emptyMap(),
    val streamType: String? = null,
)

/** Keeps the session across engine fallback and disposes it on source change or player exit. */
@Composable
internal fun rememberProxyPlayback(
    sourceUrl: String,
    audioUrl: String?,
    headers: Map<String, String>,
    streamType: String?,
    youtubeChunked: Boolean,
    onError: (String?) -> Unit,
): ProxyPlayback? {
    val context = LocalContext.current
    val mode = remember(sourceUrl) { ProxyPreferences.mode(context) }
    val needsResolver = PlaybackResolver.isRequest(sourceUrl)
    val resolverDisabledError = stringResource(Res.string.proxy_resolver_disabled)
    val resolverMissingError = stringResource(Res.string.proxy_resolver_missing)
    val resolverFailedError = stringResource(Res.string.proxy_resolver_failed)
    val proxyFailedError = stringResource(Res.string.proxy_start_failed)
    val enabled = mode != "off" && !youtubeChunked &&
        LocalStreamProxy.shouldProxy(sourceUrl, streamType, allHttp = mode == "all")
    if (!enabled && !needsResolver) return ProxyPlayback(sourceUrl, audioUrl, playerHeaders = headers, streamType = streamType)

    var playback by remember(sourceUrl, audioUrl, headers, streamType, mode) { mutableStateOf<ProxyPlayback?>(null) }
    val reportError by rememberUpdatedState(onError)
    LaunchedEffect(sourceUrl, audioUrl, headers, streamType, mode) {
        var session: LocalStreamProxy? = null
        try {
            if (needsResolver && (mode == "off" || youtubeChunked)) {
                reportError(resolverDisabledError)
                return@LaunchedEffect
            }
            val plugins = if (needsResolver) {
                PluginRepository.getEnabledScrapersForType("resolver").also {
                    if (it.isEmpty()) {
                        reportError(resolverMissingError)
                        return@LaunchedEffect
                    }
                }
            } else emptyList()
            suspend fun resolveSource(): ResolvedProxySource = if (needsResolver) {
                PlaybackResolver.resolve(sourceUrl, plugins.map { plugin ->
                    { request: String ->
                        PluginRepository.executeScraper(plugin, request, "resolver", null, null)
                            .getOrThrow().map { result ->
                                ResolvedProxySource(result.url, result.headers.orEmpty(), result.type)
                            }
                    }
                })
            } else ResolvedProxySource(sourceUrl, headers, streamType)

            val refreshRequests = Channel<Int>(Channel.CONFLATED)
            fun createSession(resolved: ResolvedProxySource): LocalStreamProxy = LocalStreamProxy(
                resolved.url.toHttpUrl(),
                resolved.headers,
                forceHls = LocalStreamProxy.shouldProxy(resolved.url, resolved.streamType, allHttp = false),
                onUpstreamAccessRejected = if (needsResolver) {
                    { status -> refreshRequests.trySend(status) }
                } else null,
            )
            fun publish(proxy: LocalStreamProxy, resolved: ResolvedProxySource) {
                playback = ProxyPlayback(
                    proxy.playbackUrl,
                    audioUrl?.let { url ->
                        if (LocalStreamProxy.shouldProxy(url, null, allHttp = true)) {
                            url.toHttpUrlOrNull()?.let(proxy::route) ?: url
                        } else url
                    },
                    proxyActive = true,
                    // Source headers are applied upstream by the proxy, never sent to loopback.
                    playerHeaders = emptyMap(),
                    streamType = resolved.streamType,
                )
            }

            var resolved = resolveSource()
            // Assignment inside the IO block makes cancellation during startup safe.
            session = withContext(Dispatchers.IO) { createSession(resolved) }
            publish(checkNotNull(session), resolved)

            if (!needsResolver) awaitCancellation()
            while (true) {
                refreshRequests.receive()
                val refreshed = resolveSource()
                val replacement = withContext(Dispatchers.IO) { createSession(refreshed) }
                val previous = session
                session = replacement
                resolved = refreshed
                publish(replacement, resolved)
                withContext(Dispatchers.IO) { previous?.close() }
            }
        } catch (_: TimeoutCancellationException) {
            reportError(resolverFailedError)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportError(if (needsResolver) resolverFailedError else proxyFailedError)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { session?.close() }
        }
    }
    return playback
}
