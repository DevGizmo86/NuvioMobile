package com.nuvio.app.features.proxy

import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** One loopback-only server per playback. No arbitrary destination URLs are accepted over HTTP. */
internal class LocalStreamProxy(
    private val source: HttpUrl,
    private val headers: Map<String, String>,
    private val forceHls: Boolean = false,
    private val onUpstreamAccessRejected: ((Int) -> Unit)? = null,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val routes = LinkedHashMap<String, HttpUrl>(16, 0.75f, true)
    private val reverseRoutes = HashMap<HttpUrl, String>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val lastAccessRejectionSignalMs = AtomicLong(0L)
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32)) { task ->
        Thread(task, "nuvio-proxy-request").apply { isDaemon = true }
    }.apply { corePoolSize = 4 }
    private val cookies = object : CookieJar {
        private val jar = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                jar.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                jar.add(cookie)
            }
            jar.removeAll { it.expiresAt <= System.currentTimeMillis() }
            while (jar.size > 256) jar.removeAt(0)
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            jar.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return jar.filter { it.matches(url) }
        }
    }
    // Use normal platform certificate validation, independently of the legacy player client.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookies)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val safeRequest = if (!sameOrigin(request.url, source)) {
                request.newBuilder().removeHeader("Authorization").removeHeader("Cookie").apply {
                    val scopedCookies = cookies.loadForRequest(request.url)
                    if (scopedCookies.isNotEmpty()) header("Cookie", scopedCookies.joinToString("; ") { "${it.name}=${it.value}" })
                }.build()
            } else request
            chain.proceed(safeRequest)
        }
        .build()
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val playbackUrl: String = route(source)

    init {
        thread(name = "nuvio-proxy-accept", isDaemon = true) {
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                sockets.add(socket)
                try {
                    workers.execute {
                        socket.use { handle(it) }
                        sockets.remove(socket)
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                }
            }
        }
    }

    @Synchronized fun route(url: HttpUrl): String {
        check(!closed.get()) { "Proxy session is closed" }
        val id = reverseRoutes[url] ?: UUID.randomUUID().toString().also {
            routes[it] = url
            reverseRoutes[url] = it
            if (routes.size > 8192) {
                val oldest = routes.entries.iterator().next()
                reverseRoutes.remove(oldest.value)
                routes.remove(oldest.key)
            }
        }
        // Preserve an HLS extension for player MIME detection, even for opaque upstream URLs.
        val suffix = if (url.encodedPath.endsWith(".m3u8", true) || (url == source && forceHls)) ".m3u8" else ".bin"
        return "http://127.0.0.1:${server.localPort}/$id$suffix"
    }

    private fun handle(socket: Socket) {
        var responseStarted = false
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input).split(' ')
            require(requestLine.size == 3 && requestLine[2].startsWith("HTTP/1."))
            val method = requestLine[0]
            val output = socket.getOutputStream()
            if (method != "GET" && method != "HEAD") {
                sendError(output, 405, "Method Not Allowed")
                return
            }
            val requestHeaders = mutableMapOf<String, String>()
            var bytes = 0
            while (true) {
                val line = readLine(input)
                bytes += line.length
                require(bytes <= 32768)
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                require(split > 0)
                requestHeaders[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
            }
            // Browser pages must not be able to use a local playback session as a fetch endpoint.
            if (requestHeaders.containsKey("origin")) {
                sendError(output, 403, "Forbidden")
                return
            }
            val target = requestLine[1]
            val id = target.removePrefix("/").substringBefore('.')
            val upstream = synchronized(this) { routes[id] }
            if (upstream == null || !target.startsWith('/') || target.contains('?')) {
                sendError(output, 404, "Not Found")
                return
            }
            val builder = Request.Builder().url(upstream).method(method, null)
                .header("User-Agent", "Nuvio-LocalProxy/1.0")
            headers.forEach { (name, value) ->
                if (name.lowercase() !in blockedHeaders &&
                    (sameOrigin(upstream, source) || name.lowercase() !in sensitiveHeaders)) {
                    builder.header(name, value)
                }
            }
            // Manifest ranges describe the upstream text, not the rewritten text.
            val knownManifest = upstream.encodedPath.endsWith(".m3u8", true) || (upstream == source && forceHls)
            if (!knownManifest) {
                requestHeaders["range"]?.let { builder.header("Range", it) }
                requestHeaders["if-range"]?.let { builder.header("If-Range", it) }
            }
            val call = client.newCall(builder.build())
            calls.add(call)
            try {
                if (closed.get()) throw IOException("Session closed")
                call.execute().use { response ->
                    if (response.code in accessRejectionCodes) {
                        signalAccessRejection(response.code)
                    }
                    val body = response.body
                    val mime = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                    val isManifest = knownManifest || mime in hlsMimeTypes ||
                        response.request.url.encodedPath.endsWith(".m3u8", true) ||
                        (method == "GET" && response.isSuccessful && body != null &&
                            body.source().peek().let { it.request(7) && it.readUtf8(7) == "#EXTM3U" })
                    val rewritten = if (method == "GET" && response.isSuccessful && isManifest && body != null) {
                        require(response.code != 206) { "Partial manifests cannot be rewritten" }
                        val buffer = java.io.ByteArrayOutputStream()
                        body.byteStream().use { stream ->
                            val chunk = ByteArray(8192)
                            while (true) {
                                val size = stream.read(chunk)
                                if (size < 0) break
                                require(buffer.size() + size <= 4 * 1024 * 1024) { "Manifest too large" }
                                buffer.write(chunk, 0, size)
                            }
                        }
                        HlsManifestRewriter.rewrite(buffer.toString("UTF-8"), response.request.url, ::route)
                            .toByteArray(Charsets.UTF_8)
                    } else null
                    val forwarded = linkedMapOf("Connection" to "close", "Cache-Control" to "no-store")
                    if (rewritten != null || (isManifest && response.isSuccessful)) {
                        forwarded["Content-Type"] = "application/vnd.apple.mpegurl"
                        rewritten?.let { forwarded["Content-Length"] = it.size.toString() }
                    } else {
                        listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Content-Encoding").forEach { name ->
                            response.header(name)?.let { forwarded[name] = it }
                        }
                    }
                    responseStarted = true
                    writeHeaders(output, response.code, "Upstream", forwarded)
                    if (method != "HEAD") {
                        if (rewritten != null) output.write(rewritten) else body?.byteStream()?.copyTo(output, 32 * 1024)
                    }
                    output.flush()
                }
            } finally {
                calls.remove(call)
            }
        } catch (_: Exception) {
            if (!responseStarted) runCatching { sendError(socket.getOutputStream(), 502, "Local proxy request failed") }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        calls.forEach { it.cancel() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        synchronized(this) { routes.clear(); reverseRoutes.clear() }
    }

    private fun signalAccessRejection(status: Int) {
        val callback = onUpstreamAccessRejected ?: return
        val now = System.currentTimeMillis()
        while (true) {
            val previous = lastAccessRejectionSignalMs.get()
            if (now - previous < ACCESS_REJECTION_COOLDOWN_MS) return
            if (lastAccessRejectionSignalMs.compareAndSet(previous, now)) {
                callback(status)
                return
            }
        }
    }

    companion object {
        private const val ACCESS_REJECTION_COOLDOWN_MS = 10_000L
        private val accessRejectionCodes = setOf(401, 403, 410)
        private val hlsMimeTypes = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
        private val sensitiveHeaders = setOf("authorization", "cookie")
        private val blockedHeaders = setOf("host", "connection", "content-length", "transfer-encoding", "accept-encoding", "range", "if-range", "proxy-authorization")
        private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
        private fun readLine(input: BufferedInputStream): String {
            val line = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch < 0) throw IOException("Unexpected end of headers")
                if (ch == 10) return line.toString().removeSuffix("\r")
                require(line.length < 8192)
                line.append(ch.toChar())
            }
        }
        private fun writeHeaders(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
            val text = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                headers.forEach { (name, value) ->
                    require(!value.contains('\r') && !value.contains('\n'))
                    append("$name: $value\r\n")
                }
                append("\r\n")
            }
            out.write(text.toByteArray(Charsets.ISO_8859_1))
        }
        private fun sendError(out: OutputStream, code: Int, reason: String) {
            writeHeaders(out, code, reason, mapOf("Connection" to "close", "Content-Length" to "0"))
            out.flush()
        }
        fun shouldProxy(url: String, streamType: String?, allHttp: Boolean): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.host in setOf("localhost", "127.0.0.1", "::1") || parsed.host.startsWith("127.")) return false
            val type = streamType.orEmpty().lowercase()
            if (parsed.encodedPath.endsWith(".mpd", true) || type.contains("dash") || type == "mpd") return false
            return allHttp || parsed.encodedPath.endsWith(".m3u8", true) || type.contains("mpegurl") || type in setOf("hls", "m3u8")
        }
    }
}
