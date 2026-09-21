package com.nuvio.app.features.proxy

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LocalStreamProxyTest {
    private val client = OkHttpClient()

    private class Origin(private val reply: (String, Map<String, String>) -> String) : Closeable {
        private val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@use
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    requests.add(line to headers)
                    it.getOutputStream().write(reply(line, headers).toByteArray())
                }
            }
        }
        override fun close() { server.close(); worker.join(2000) }
    }

    private fun response(body: String, type: String = "application/octet-stream", extra: String = "", status: Int = 200) =
        "HTTP/1.1 $status OK\r\nContent-Type: $type\r\nConnection: close\r\n$extra\r\n$body"

    @Test fun rewritesMasterRenditionsKeysMapsAndRelativeSegments() {
        val manifest = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,URI="audio/list.m3u8"
#EXT-X-KEY:METHOD=AES-128,URI="../key?k=1"
#EXT-X-MAP:URI="init.mp4"
#EXT-X-STREAM-INF:BANDWIDTH=1000
video/list.m3u8?q=2
#EXT-X-KEY:METHOD=AES-128,URI="data:text/plain;base64,AA=="
"""
        val seen = mutableListOf<String>()
        val rewritten = HlsManifestRewriter.rewrite(manifest, "https://example.org/live/master.m3u8".toHttpUrl()) {
            seen.add(it.toString()); "http://127.0.0.1/r${seen.size}"
        }
        assertEquals(listOf("https://example.org/live/audio/list.m3u8", "https://example.org/key?k=1", "https://example.org/live/init.mp4", "https://example.org/live/video/list.m3u8?q=2"), seen)
        assertTrue(rewritten.contains("URI=\"http://127.0.0.1/r1\""))
        assertTrue(rewritten.contains("data:text/plain;base64,AA=="))
    }

    @Test fun followsRedirectAndUsesFinalManifestLocation() {
        Origin { line, _ ->
            when {
                line.contains(" /start ") -> response("", extra = "Location: /nested/live.m3u8\r\n", status = 302)
                line.contains(" /nested/live.m3u8 ") -> response("#EXTM3U\n#EXTINF:4,\nseg.ts\n", "application/vnd.apple.mpegurl")
                line.contains(" /nested/seg.ts ") -> response("segment-data")
                else -> response("", status = 404)
            }
        }.use { origin ->
            LocalStreamProxy((origin.url + "/start").toHttpUrl(), mapOf("Referer" to "https://example.org/"), true).use { proxy ->
                val manifest = client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use {
                    assertEquals(200, it.code)
                    val bytes = it.body!!.bytes()
                    assertEquals(bytes.size.toString(), it.header("Content-Length"))
                    String(bytes)
                }
                val segmentUrl = manifest.lines().first { it.startsWith("http") }
                assertTrue(segmentUrl.startsWith("http://127.0.0.1:"))
                client.newCall(Request.Builder().url(segmentUrl).build()).execute().use {
                    assertEquals("segment-data", it.body!!.string())
                }
                assertEquals("https://example.org/", origin.requests.last().second["referer"])
            }
        }
    }

    @Test fun appliesProviderOriginUpstreamWithoutSendingItToLoopback() {
        Origin { _, headers ->
            assertEquals("https://provider.example", headers["origin"])
            assertEquals("provider-agent", headers["user-agent"])
            response("segment")
        }.use { origin ->
            LocalStreamProxy((origin.url + "/stream").toHttpUrl(), mapOf(
                "Origin" to "https://provider.example", "User-Agent" to "provider-agent",
            )).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("segment", it.body!!.string())
                }
            }
        }
    }

    @Test fun reportsAccessRejectionOnceSoResolverCanRenew() {
        Origin { _, _ -> response("expired", status = 403) }.use { origin ->
            val statuses = CopyOnWriteArrayList<Int>()
            val signal = CountDownLatch(1)
            LocalStreamProxy(
                (origin.url + "/expired").toHttpUrl(),
                emptyMap(),
                onUpstreamAccessRejected = { status -> statuses += status; signal.countDown() },
            ).use { proxy ->
                repeat(2) {
                    client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().close()
                }
                assertTrue(signal.await(2, TimeUnit.SECONDS))
                assertEquals(listOf(403), statuses)
            }
        }
    }

    @Test fun forwardsRangesAndPreservesPartialResponse() {
        Origin { _, headers ->
            assertEquals("bytes=2-4", headers["range"])
            response("cde", extra = "Content-Range: bytes 2-4/6\r\nContent-Length: 3\r\n", status = 206)
        }.use { origin ->
            LocalStreamProxy((origin.url + "/video.mp4").toHttpUrl(), emptyMap()).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl).header("Range", "bytes=2-4").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("bytes 2-4/6", it.header("Content-Range"))
                    assertEquals("cde", it.body!!.string())
                }
            }
        }
    }

    @Test fun removesRangeForKnownManifestAndDoesNotCacheUpstreamValidators() {
        Origin { _, headers ->
            assertNull(headers["range"])
            response("#EXTM3U\nseg.ts\n", "application/x-mpegurl", "ETag: upstream-tag\r\n")
        }.use { origin ->
            LocalStreamProxy((origin.url + "/live.m3u8").toHttpUrl(), emptyMap()).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl).header("Range", "bytes=0-20").build()).execute().use {
                    assertEquals(200, it.code)
                    assertNull(it.header("ETag"))
                    assertEquals("no-store", it.header("Cache-Control"))
                    assertTrue(it.body!!.string().contains("127.0.0.1"))
                }
            }
        }
    }

    @Test fun handlesHeadWithoutBodyAndPropagatesUpstreamErrors() {
        Origin { line, _ ->
            if (line.startsWith("HEAD")) response("", extra = "Content-Length: 123\r\n")
            else response("gone", status = 410)
        }.use { origin ->
            LocalStreamProxy((origin.url + "/video.mp4").toHttpUrl(), emptyMap()).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl).head().build()).execute().use {
                    assertEquals("123", it.header("Content-Length"))
                    assertEquals(0, it.body!!.bytes().size)
                }
                client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use {
                    assertEquals(410, it.code)
                    assertEquals("gone", it.body!!.string())
                }
            }
        }
    }

    @Test fun keepsCookiesWithinSession() {
        Origin { line, headers ->
            if (line.contains("list.m3u8")) response("#EXTM3U\nseg.ts\n", "application/x-mpegurl", "Set-Cookie: session=abc; Path=/\r\n")
            else response(headers["cookie"].orEmpty())
        }.use { origin ->
            LocalStreamProxy((origin.url + "/list.m3u8").toHttpUrl(), emptyMap()).use { proxy ->
                val next = client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use {
                    it.body!!.string().lines().first { line -> line.startsWith("http") }
                }
                client.newCall(Request.Builder().url(next).build()).execute().use {
                    assertEquals("session=abc", it.body!!.string())
                }
            }
            LocalStreamProxy((origin.url + "/seg.ts").toHttpUrl(), emptyMap()).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use { assertEquals("", it.body!!.string()) }
            }
        }
    }

    @Test fun stripsCredentialsOnCrossOriginRedirect() {
        Origin { _, headers -> response("${headers["authorization"]}|${headers["cookie"]}") }.use { other ->
            Origin { _, _ -> response("", extra = "Location: ${other.url}/seg.ts\r\n", status = 302) }.use { origin ->
                LocalStreamProxy((origin.url + "/start").toHttpUrl(), mapOf("Authorization" to "Bearer secret", "Cookie" to "raw=secret")).use { proxy ->
                    client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use { assertEquals("null|null", it.body!!.string()) }
                }
            }
        }
    }

    @Test fun rejectsUnknownRoutesBrowserRequestsAndPost() {
        Origin { _, _ -> response("ok") }.use { origin ->
            LocalStreamProxy((origin.url + "/video").toHttpUrl(), emptyMap()).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl.substringBeforeLast('/') + "/unknown.bin").build()).execute().use { assertEquals(404, it.code) }
                client.newCall(Request.Builder().url(proxy.playbackUrl).header("Origin", "https://example.org").build()).execute().use { assertEquals(403, it.code) }
                Socket("127.0.0.1", proxy.playbackUrl.toHttpUrl().port).use { socket ->
                    socket.getOutputStream().write("POST /anything HTTP/1.1\r\n\r\n".toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().readLine().contains("405"))
                }
                assertTrue(origin.requests.isEmpty())
            }
        }
    }

    @Test fun closingSessionClosesListeningSocket() {
        val proxy = LocalStreamProxy("https://example.org/live.m3u8".toHttpUrl(), emptyMap())
        val port = proxy.playbackUrl.toHttpUrl().port
        proxy.close()
        proxy.close()
        try { Socket("127.0.0.1", port).close(); fail("Server is still listening") } catch (_: java.io.IOException) { }
    }

    @Test fun selectsHlsAndSkipsLocalDashAndNonHttp() {
        assertTrue(LocalStreamProxy.shouldProxy("https://example.org/list.m3u8?token=a", null, false))
        assertTrue(LocalStreamProxy.shouldProxy("https://example.org/opaque", "hls", false))
        assertFalse(LocalStreamProxy.shouldProxy("https://example.org/file.mp4", null, false))
        assertTrue(LocalStreamProxy.shouldProxy("https://example.org/file.mp4", null, true))
        assertFalse(LocalStreamProxy.shouldProxy("http://127.0.0.1/live.m3u8", null, true))
        assertFalse(LocalStreamProxy.shouldProxy("https://example.org/file.mpd", null, true))
        assertFalse(LocalStreamProxy.shouldProxy("content://video/file", null, true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnresolvedHlsVariables() {
        HlsManifestRewriter.rewrite("#EXTM3U\n{\$host}/seg.ts", "https://example.org/live".toHttpUrl()) { it.toString() }
    }
}
