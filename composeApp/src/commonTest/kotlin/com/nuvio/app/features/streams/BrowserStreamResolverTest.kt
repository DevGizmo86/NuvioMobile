package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserStreamResolverTest {
    @Test
    fun `recognizes only Toastflix extractor pages`() {
        assertTrue(isSupportedBrowserStreamUrl("https://toastflix.stremio-italia.eu/extractor/dual?x=1"))
        assertFalse(isSupportedBrowserStreamUrl("https://toastflix.stremio-italia.eu/manifest.json"))
        assertFalse(isSupportedBrowserStreamUrl("https://example.test/extractor/dual"))
    }

    @Test
    fun `parses Toastflix player stream payload`() {
        val parsed = BrowserStreamPayloadParser.parseStreamJson(
            """
            {
              "name": "Toastflix Dual",
              "url": "https://video.example.test/master.m3u8",
              "audioUrl": "https://audio.example.test/italian.m3u8",
              "behaviorHints": {
                "bingeGroup": "toast-dual",
                "proxyHeaders": {
                  "request": { "Referer": "https://provider.example/" },
                  "response": { "Access-Control-Allow-Origin": "*" }
                }
              },
              "subtitles": [
                { "url": "https://sub.example.test/it.vtt", "lang": "ita", "title": "Italiano" }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("https://video.example.test/master.m3u8", parsed?.url)
        assertEquals("https://audio.example.test/italian.m3u8", parsed?.audioUrl)
        assertEquals("https://provider.example/", parsed?.requestHeaders?.get("Referer"))
        assertEquals("toast-dual", parsed?.bingeGroup)
        assertEquals("ita", parsed?.subtitles?.single()?.language)
    }

    @Test
    fun `rejects missing or non-http playback url`() {
        assertNull(BrowserStreamPayloadParser.parseStreamJson("{\"name\":\"missing\"}"))
        assertNull(BrowserStreamPayloadParser.parseStreamJson("{\"url\":\"file:///tmp/video\"}"))
    }
}
