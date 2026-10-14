package com.olaph.xdm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.olaph.xdm.adblock.PopupMode
import com.olaph.xdm.adblock.filters.FilterLists
import com.olaph.xdm.engine.SearchEngine
import com.olaph.xdm.youtube.YoutubeQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What tapping a finished video or audio file does. */
enum class MediaOpener(val label: String) { BUILT_IN("Built-in player"), OTHER_APP("Other app") }

/** How long the private folder stays unlocked after XDM goes to the background. */
enum class AutoLockDelay(val label: String, val millis: Long) {
    IMMEDIATELY("Immediately", 0),
    ONE_MINUTE("After 1 minute", 60_000),
    FIVE_MINUTES("After 5 minutes", 300_000),
}

data class AppSettings(
    val connections: Int = 8,
    val maxParallel: Int = 3,
    val wifiOnly: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val userAgent: String = DEFAULT_USER_AGENT,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    /** Route downloads started inside the built-in browser to XDM instead of the system downloader. */
    val interceptDownloads: Boolean = true,
    /** Master switch of the browser's ad blocker. */
    val blockAds: Boolean = true,
    /** Sites (normalized hosts) where ads are allowed; parent-domain match, so `example.com` covers `m.example.com`. */
    val adAllowedSites: Set<String> = emptySet(),
    /** Windows opened by pages: Off / Ads only / All (default: all, with a "Pop-up blocked — Open" offer). */
    val popupMode: PopupMode = PopupMode.ALL,
    /** Ids of filter lists the user switched off (stored as "off" so lists added later start on). */
    val disabledFilterLists: Set<String> = emptySet(),
    /** Turn finished HLS downloads that came out as MPEG-TS into a normal .mp4 (stream copy, no re-encoding). */
    val convertHlsToMp4: Boolean = true,
    /** "Default YouTube quality": [YoutubeQuality.ASK] shows the format sheet, anything else adds the download straight away. */
    val youtubeQuality: YoutubeQuality = YoutubeQuality.ASK,
    /** Put interrupted downloads back to work after a reboot or an app update. */
    val resumeAfterRestart: Boolean = true,
    val openMediaWith: MediaOpener = MediaOpener.BUILT_IN,
    /** Default of the "Save to private folder" box in the add sheets. */
    val savePrivateByDefault: Boolean = false,
    val autoLockDelay: AutoLockDelay = AutoLockDelay.IMMEDIATELY,
) {
    val enabledFilterLists: Set<String> get() = FilterLists.ALL.map { it.id }.filter { it !in disabledFilterLists }.toSet()

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

interface SettingsSource {
    val flow: Flow<AppSettings>
    suspend fun current(): AppSettings
}

class SettingsRepository(private val context: Context) : SettingsSource {
    private object Keys {
        val connections = intPreferencesKey("connections")
        val maxParallel = intPreferencesKey("max_parallel")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val theme = stringPreferencesKey("theme")
        val userAgent = stringPreferencesKey("user_agent")
        val searchEngine = stringPreferencesKey("search_engine")
        val interceptDownloads = booleanPreferencesKey("intercept_downloads")
        val blockAds = booleanPreferencesKey("block_ads")
        val adAllowedSites = stringSetPreferencesKey("ad_allowed_sites")
        val popupMode = stringPreferencesKey("popup_mode")
        val disabledFilterLists = stringSetPreferencesKey("disabled_filter_lists")
        val convertHlsToMp4 = booleanPreferencesKey("convert_hls_to_mp4")
        val youtubeQuality = stringPreferencesKey("youtube_quality")
        val resumeAfterRestart = booleanPreferencesKey("resume_after_restart")
        val openMediaWith = stringPreferencesKey("open_media_with")
        val savePrivateByDefault = booleanPreferencesKey("save_private_by_default")
        val autoLockDelay = stringPreferencesKey("auto_lock_delay")
    }

    override val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            connections = (p[Keys.connections] ?: d.connections).coerceIn(1, 16),
            maxParallel = (p[Keys.maxParallel] ?: d.maxParallel).coerceIn(1, 10),
            wifiOnly = p[Keys.wifiOnly] ?: d.wifiOnly,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            userAgent = p[Keys.userAgent]?.takeIf { it.isNotBlank() } ?: d.userAgent,
            searchEngine = p[Keys.searchEngine]?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() } ?: d.searchEngine,
            interceptDownloads = p[Keys.interceptDownloads] ?: d.interceptDownloads,
            blockAds = p[Keys.blockAds] ?: d.blockAds,
            adAllowedSites = p[Keys.adAllowedSites] ?: d.adAllowedSites,
            popupMode = p[Keys.popupMode]?.let { runCatching { PopupMode.valueOf(it) }.getOrNull() } ?: d.popupMode,
            disabledFilterLists = p[Keys.disabledFilterLists] ?: d.disabledFilterLists,
            convertHlsToMp4 = p[Keys.convertHlsToMp4] ?: d.convertHlsToMp4,
            youtubeQuality = p[Keys.youtubeQuality]?.let { runCatching { YoutubeQuality.valueOf(it) }.getOrNull() } ?: d.youtubeQuality,
            resumeAfterRestart = p[Keys.resumeAfterRestart] ?: d.resumeAfterRestart,
            openMediaWith = p[Keys.openMediaWith]?.let { runCatching { MediaOpener.valueOf(it) }.getOrNull() } ?: d.openMediaWith,
            savePrivateByDefault = p[Keys.savePrivateByDefault] ?: d.savePrivateByDefault,
            autoLockDelay = p[Keys.autoLockDelay]?.let { runCatching { AutoLockDelay.valueOf(it) }.getOrNull() } ?: d.autoLockDelay,
        )
    }

    override suspend fun current(): AppSettings = flow.first()

    suspend fun setConnections(v: Int) = context.dataStore.edit { it[Keys.connections] = v.coerceIn(1, 16) }
    suspend fun setMaxParallel(v: Int) = context.dataStore.edit { it[Keys.maxParallel] = v.coerceIn(1, 10) }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.wifiOnly] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[Keys.theme] = v.name }
    suspend fun setUserAgent(v: String) = context.dataStore.edit { it[Keys.userAgent] = v.trim() }
    suspend fun setSearchEngine(v: SearchEngine) = context.dataStore.edit { it[Keys.searchEngine] = v.name }
    suspend fun setInterceptDownloads(v: Boolean) = context.dataStore.edit { it[Keys.interceptDownloads] = v }
    suspend fun setBlockAds(v: Boolean) = context.dataStore.edit { it[Keys.blockAds] = v }
    suspend fun setConvertHlsToMp4(v: Boolean) = context.dataStore.edit { it[Keys.convertHlsToMp4] = v }
    suspend fun setPopupMode(v: PopupMode) = context.dataStore.edit { it[Keys.popupMode] = v.name }
    suspend fun setYoutubeQuality(v: YoutubeQuality) = context.dataStore.edit { it[Keys.youtubeQuality] = v.name }
    suspend fun setResumeAfterRestart(v: Boolean) = context.dataStore.edit { it[Keys.resumeAfterRestart] = v }
    suspend fun setOpenMediaWith(v: MediaOpener) = context.dataStore.edit { it[Keys.openMediaWith] = v.name }
    suspend fun setSavePrivateByDefault(v: Boolean) = context.dataStore.edit { it[Keys.savePrivateByDefault] = v }
    suspend fun setAutoLockDelay(v: AutoLockDelay) = context.dataStore.edit { it[Keys.autoLockDelay] = v.name }

    suspend fun setFilterListEnabled(id: String, enabled: Boolean) = context.dataStore.edit {
        val current = it[Keys.disabledFilterLists] ?: emptySet()
        it[Keys.disabledFilterLists] = if (enabled) current - id else current + id
    }

    /** Adds or removes one allowlist entry (read-modify-write inside one DataStore transaction). */
    suspend fun setAdSiteAllowed(site: String, allowed: Boolean) = context.dataStore.edit {
        val current = it[Keys.adAllowedSites] ?: emptySet()
        it[Keys.adAllowedSites] = if (allowed) current + site else current - site
    }
}
