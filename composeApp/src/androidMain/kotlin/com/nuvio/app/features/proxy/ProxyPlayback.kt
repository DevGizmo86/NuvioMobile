package com.nuvio.app.features.proxy

import android.content.Context
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
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ProxyPreferences {
    fun preferences(context: Context) = context.applicationContext.getSharedPreferences("nuvio_local_proxy", Context.MODE_PRIVATE)
    fun mode(context: Context): String = preferences(context).getString("mode", "off") ?: "off"
}

internal data class ProxyPlayback(val videoUrl: String, val audioUrl: String?)

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
    val enabled = mode != "off" && !youtubeChunked &&
        LocalStreamProxy.shouldProxy(sourceUrl, streamType, allHttp = mode == "all")
    if (!enabled) return ProxyPlayback(sourceUrl, audioUrl)

    var playback by remember(sourceUrl, audioUrl, headers, streamType, mode) { mutableStateOf<ProxyPlayback?>(null) }
    val reportError by rememberUpdatedState(onError)
    LaunchedEffect(sourceUrl, audioUrl, headers, streamType, mode) {
        var session: LocalStreamProxy? = null
        try {
            // Assignment inside the IO block makes cancellation during startup safe.
            withContext(Dispatchers.IO) {
                session = LocalStreamProxy(
                    sourceUrl.toHttpUrl(), headers,
                    forceHls = LocalStreamProxy.shouldProxy(sourceUrl, streamType, allHttp = false),
                )
            }
            val proxy = checkNotNull(session)
            playback = ProxyPlayback(
                proxy.playbackUrl,
                audioUrl?.let { url ->
                    if (LocalStreamProxy.shouldProxy(url, null, allHttp = true)) {
                        url.toHttpUrlOrNull()?.let(proxy::route) ?: url
                    } else url
                },
            )
            awaitCancellation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            reportError("Unable to start the integrated proxy. Disable it in Playback settings and retry.")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { session?.close() }
        }
    }
    return playback
}
