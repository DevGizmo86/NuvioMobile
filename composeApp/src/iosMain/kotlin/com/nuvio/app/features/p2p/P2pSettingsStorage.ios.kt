package com.nuvio.app.features.p2p

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSUserDefaults

internal actual object P2pSettingsStorage {
    private const val p2pEnabledKey = "p2p_enabled"
    private const val enableUploadKey = "enable_upload"
    private const val hideTorrentStatsKey = "hide_torrent_stats"
    private const val torrentProfileKey = "torrent_profile"
    private const val cacheSizeKey = "cache_size"
    private val syncKeys = listOf(
        p2pEnabledKey,
        enableUploadKey,
        hideTorrentStatsKey,
        torrentProfileKey,
        cacheSizeKey,
    )

    actual fun loadP2pEnabled(): Boolean? =
        loadBoolean(p2pEnabledKey)

    actual fun saveP2pEnabled(enabled: Boolean) {
        saveBoolean(p2pEnabledKey, enabled)
    }

    actual fun loadEnableUpload(): Boolean? =
        loadBoolean(enableUploadKey)

    actual fun saveEnableUpload(enabled: Boolean) {
        saveBoolean(enableUploadKey, enabled)
    }

    actual fun loadHideTorrentStats(): Boolean? =
        loadBoolean(hideTorrentStatsKey)

    actual fun saveHideTorrentStats(enabled: Boolean) {
        saveBoolean(hideTorrentStatsKey, enabled)
    }

    actual fun loadTorrentProfile(): String? = loadString(torrentProfileKey)

    actual fun saveTorrentProfile(profile: String) {
        saveString(torrentProfileKey, profile)
    }

    actual fun loadCacheSize(): String? = loadString(cacheSizeKey)

    actual fun saveCacheSize(size: String) {
        saveString(cacheSizeKey, size)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadP2pEnabled()?.let { put(p2pEnabledKey, encodeSyncBoolean(it)) }
        loadEnableUpload()?.let { put(enableUploadKey, encodeSyncBoolean(it)) }
        loadHideTorrentStats()?.let { put(hideTorrentStatsKey, encodeSyncBoolean(it)) }
        loadTorrentProfile()?.let { put(torrentProfileKey, encodeSyncString(it)) }
        loadCacheSize()?.let { put(cacheSizeKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { key ->
            NSUserDefaults.standardUserDefaults.removeObjectForKey(ProfileScopedKey.of(key))
        }

        payload.decodeSyncBoolean(p2pEnabledKey)?.let(::saveP2pEnabled)
        payload.decodeSyncBoolean(enableUploadKey)?.let(::saveEnableUpload)
        payload.decodeSyncBoolean(hideTorrentStatsKey)?.let(::saveHideTorrentStats)
        payload.decodeSyncString(torrentProfileKey)?.let(::saveTorrentProfile)
        payload.decodeSyncString(cacheSizeKey)?.let(::saveCacheSize)
    }

    private fun loadBoolean(keyBase: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = ProfileScopedKey.of(keyBase)
        return if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else null
    }

    private fun saveBoolean(keyBase: String, value: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = ProfileScopedKey.of(keyBase))
    }

    private fun loadString(keyBase: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(keyBase))

    private fun saveString(keyBase: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = ProfileScopedKey.of(keyBase))
    }
}
