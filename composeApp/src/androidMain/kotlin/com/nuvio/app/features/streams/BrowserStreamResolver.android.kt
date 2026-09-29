package com.nuvio.app.features.streams

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import okhttp3.Headers
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream

private const val TOASTFLIX_ORIGIN = "https://toastflix.stremio-italia.eu"
private const val STREMIO_PROXY_HOST = "127.0.0.1"
private const val STREMIO_PROXY_PORT = 11470

@Composable
internal actual fun BrowserStreamResolver(
    url: String,
    onResolved: (ResolvedBrowserStream) -> Unit,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(BrowserProxyCookieJar())
            .build()
    }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    createResolverWebView(
                        context = context,
                        client = client,
                        onResolved = onResolved,
                        onError = onError,
                    ).also { created ->
                        webViewHolder[0] = created
                        created.loadUrl(url)
                    }
                },
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Text("Chiudi")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder[0]?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webViewHolder[0] = null
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createResolverWebView(
    context: android.content.Context,
    client: OkHttpClient,
    onResolved: (ResolvedBrowserStream) -> Unit,
    onError: (String) -> Unit,
): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.allowContentAccess = false
    settings.allowFileAccess = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    webViewClient = ToastflixResolverWebViewClient(client, onResolved, onError)
}

private class ToastflixResolverWebViewClient(
    private val client: OkHttpClient,
    private val onResolved: (ResolvedBrowserStream) -> Unit,
    private val onError: (String) -> Unit,
) : WebViewClient() {
    private var trustedPage = false
    private var delivered = false

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (handlePlayerDeepLink(url)) {
            view.stopLoading()
            return
        }
        trustedPage = url.startsWith("$TOASTFLIX_ORIGIN/extractor/") || url == "about:blank"
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handlePlayerDeepLink(request.url.toString())

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handlePlayerDeepLink(url)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!trustedPage || uri.host != STREMIO_PROXY_HOST || uri.port != STREMIO_PROXY_PORT) {
            return super.shouldInterceptRequest(view, request)
        }
        return runCatching { interceptStremioProxyRequest(request) }
            .getOrElse { proxyErrorResponse(502, "Proxy Error", it.message ?: "Richiesta non riuscita") }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame && !delivered) {
            onError(error.description?.toString() ?: "Errore durante la risoluzione Browser")
        }
        super.onReceivedError(view, request, error)
    }

    private fun handlePlayerDeepLink(url: String): Boolean {
        if (!url.startsWith("stremio:///player/", ignoreCase = true)) return false
        if (delivered) return true
        val resolved = decodeStremioPlayerDeepLink(url)
        if (resolved == null) {
            onError("Toastflix ha restituito un flusso non valido")
        } else {
            delivered = true
            onResolved(resolved)
        }
        return true
    }

    private fun interceptStremioProxyRequest(request: WebResourceRequest): WebResourceResponse {
        val corsHeaders = corsHeaders()
        if (request.method.equals("OPTIONS", ignoreCase = true)) {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                204,
                "No Content",
                corsHeaders,
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        if (request.url.path == "/" || request.url.path.isNullOrEmpty()) {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                200,
                "OK",
                corsHeaders,
                ByteArrayInputStream("Nuvio browser proxy online".encodeToByteArray()),
            )
        }
        if (!request.method.equals("GET", ignoreCase = true) &&
            !request.method.equals("HEAD", ignoreCase = true)
        ) {
            return proxyErrorResponse(405, "Method Not Allowed", "Metodo proxy non supportato")
        }

        val parsed = parseStremioProxyUrl(request.url)
            ?: return proxyErrorResponse(400, "Bad Request", "URL proxy non valido")
        if (!isPublicHttpTarget(parsed.url)) {
            return proxyErrorResponse(403, "Forbidden", "Destinazione proxy non consentita")
        }

        val upstreamRequest = Request.Builder()
            .url(parsed.url)
            .headers(Headers.Builder().apply {
                parsed.headers.forEach { (name, value) -> add(name, value) }
            }.build())
            .method(request.method.uppercase(), null)
            .build()
        val response = client.newCall(upstreamRequest).execute()
        return response.toWebResourceResponse(corsHeaders)
    }
}

private data class ParsedStremioProxyRequest(
    val url: String,
    val headers: Map<String, String>,
)

private class BrowserProxyCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            this.cookies.add(cookie)
        }
        this.cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        while (this.cookies.size > 256) this.cookies.removeAt(0)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}

private fun parseStremioProxyUrl(uri: Uri): ParsedStremioProxyRequest? {
    val encodedPath = uri.encodedPath ?: return null
    val prefix = "/proxy/"
    if (!encodedPath.startsWith(prefix)) return null
    val remainder = encodedPath.removePrefix(prefix)
    val upstreamPathStart = remainder.indexOf('/')
    if (upstreamPathStart < 0) return null
    val arguments = Uri.decode(remainder.substring(0, upstreamPathStart))
    val upstreamPath = remainder.substring(upstreamPathStart) +
        uri.encodedQuery?.let { "?$it" }.orEmpty()

    var destination: String? = null
    val headers = linkedMapOf<String, String>()
    arguments.split("&").forEach { argument ->
        when {
            argument.startsWith("d=") -> destination = argument.removePrefix("d=")
            argument.startsWith("h=") -> {
                val header = argument.removePrefix("h=")
                val separator = header.indexOf(':')
                if (separator > 0) headers[header.substring(0, separator)] = header.substring(separator + 1)
            }
        }
    }
    val baseUrl = destination?.trimEnd('/') ?: return null
    return ParsedStremioProxyRequest(baseUrl + upstreamPath, headers)
}

private fun isPublicHttpTarget(url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    if (uri.scheme != "https" && uri.scheme != "http") return false
    val host = uri.host?.lowercase() ?: return false
    if (host == "localhost" || host == "0.0.0.0" || host == "::1") return false
    return runCatching {
        InetAddress.getAllByName(host).all { address ->
            !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.hostAddress.orEmpty().lowercase().startsWith("fc") &&
                !address.hostAddress.orEmpty().lowercase().startsWith("fd")
        }
    }.getOrDefault(false)
}

private fun Response.toWebResourceResponse(corsHeaders: Map<String, String>): WebResourceResponse {
    val contentType = body.contentType()
    val mimeType = contentType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
    val encoding = contentType?.charset()?.name() ?: "UTF-8"
    val responseHeaders = headers.toMultimap()
        .mapValues { (_, values) -> values.joinToString(", ") }
        .toMutableMap()
        .apply { putAll(corsHeaders) }
    val stream = body.byteStream().closeResponseWith(this)
    return WebResourceResponse(
        mimeType,
        encoding,
        code,
        message.ifBlank { "OK" },
        responseHeaders,
        stream,
    )
}

private fun InputStream.closeResponseWith(response: Response): InputStream =
    object : FilterInputStream(this) {
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

private fun corsHeaders(): Map<String, String> = mapOf(
    "Access-Control-Allow-Origin" to TOASTFLIX_ORIGIN,
    "Access-Control-Allow-Credentials" to "true",
    "Access-Control-Allow-Methods" to "GET,HEAD,OPTIONS",
    "Access-Control-Allow-Headers" to "*",
    "Access-Control-Allow-Private-Network" to "true",
)

private fun proxyErrorResponse(status: Int, reason: String, message: String): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "UTF-8",
        status,
        reason,
        corsHeaders(),
        ByteArrayInputStream(message.encodeToByteArray()),
    )

internal fun decodeStremioPlayerDeepLink(url: String): ResolvedBrowserStream? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("stremio", ignoreCase = true)) return null
    val segments = uri.pathSegments
    if (segments.size < 2 || segments.firstOrNull() != "player") return null
    val compressed = runCatching { Base64.decode(segments[1], Base64.DEFAULT) }.getOrNull() ?: return null
    val json = runCatching {
        InflaterInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { it.readText() }
    }.getOrNull() ?: return null
    return BrowserStreamPayloadParser.parseStreamJson(json)
}
