package com.nuvio.app.features.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackResolverTest {
    private val request = "${PlaybackResolver.BASE_URL}/proxy/hls/manifest.m3u8?d=opaque"

    @Test fun onlyExactMarkerHostIsIntercepted() {
        assertTrue(PlaybackResolver.isRequest(request))
        assertFalse(PlaybackResolver.isRequest("https://nuvio-resolver.invalid.example/"))
        assertFalse(PlaybackResolver.isRequest("https://example.org/video.m3u8"))
        assertFalse(PlaybackResolver.isRequest("not a URL"))
    }

    @Test fun passesOpaqueRequestAndKeepsResolvedHeadersAndType() = runBlocking {
        val result = PlaybackResolver.resolve(request, listOf({ input ->
            assertEquals(request, input)
            listOf(ResolvedProxySource("https://cdn.example/live", mapOf("Origin" to "https://provider.example"), "hls"))
        }))
        assertEquals("hls", result.streamType)
        assertEquals("https://provider.example", result.headers["Origin"])
    }

    @Test fun skipsDecliningAndFailedPlugins() = runBlocking {
        val result = PlaybackResolver.resolve(request, listOf(
            { emptyList() },
            { throw IllegalStateException("provider unavailable") },
            { listOf(ResolvedProxySource("https://cdn.example/live.m3u8")) },
        ))
        assertEquals("https://cdn.example/live.m3u8", result.url)
    }

    @Test fun rejectsRecursionLocalUrlsDashAndEmbeddedCredentials() = runBlocking {
        for (url in listOf(request, "file:///tmp/a", "http://127.0.0.1/a", "https://cdn.example/a.mpd", "https://user:pass@cdn.example/a")) {
            try {
                PlaybackResolver.resolve(request, listOf({ listOf(ResolvedProxySource(url)) }))
                fail("Accepted an unsupported resolver result")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun missingPluginsFailsWithoutFetchingMarker() = runBlocking {
        try {
            PlaybackResolver.resolve(request, emptyList())
            fail("Missing resolver must fail")
        } catch (_: IllegalStateException) { }
    }

    @Test fun cancellationDoesNotRunAnotherPlugin() = runBlocking {
        var called = false
        try {
            PlaybackResolver.resolve(request, listOf(
                { throw CancellationException("player closed") },
                { called = true; emptyList() },
            ))
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertFalse(called)
    }
}
