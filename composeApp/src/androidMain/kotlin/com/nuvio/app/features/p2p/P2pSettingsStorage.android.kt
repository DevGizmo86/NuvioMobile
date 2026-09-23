package com.nuvio.app.features.p2p

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object P2pSettingsStorage {
    private const val preferencesName = "torrent_settings"
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

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

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
        preferences?.edit()?.apply {
            syncKeys.forEach { remove(ProfileScopedKey.of(it)) }
        }?.apply()

        payload.decodeSyncBoolean(p2pEnabledKey)?.let(::saveP2pEnabled)
        payload.decodeSyncBoolean(enableUploadKey)?.let(::saveEnableUpload)
        payload.decodeSyncBoolean(hideTorrentStatsKey)?.let(::saveHideTorrentStats)
        payload.decodeSyncString(torrentProfileKey)?.let(::saveTorrentProfile)
        payload.decodeSyncString(cacheSizeKey)?.let(::saveCacheSize)
    }

    private fun loadBoolean(keyBase: String): Boolean? =
        preferences?.let { sharedPreferences ->
            val key = ProfileScopedKey.of(keyBase)
            if (sharedPreferences.contains(key)) {
                sharedPreferences.getBoolean(key, false)
            } else {
                null
            }
        }

    private fun saveBoolean(keyBase: String, value: Boolean) {
        preferences
            ?.edit()
            ?.putBoolean(ProfileScopedKey.of(keyBase), value)
            ?.apply()
    }

    private fun loadString(keyBase: String): String? =
        preferences?.getString(ProfileScopedKey.of(keyBase), null)

    private fun saveString(keyBase: String, value: String) {
        preferences
            ?.edit()
            ?.putString(ProfileScopedKey.of(keyBase), value)
            ?.apply()
    }
}
