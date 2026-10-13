package com.olaph.xdm

import android.app.Application
import androidx.room.Room
import com.olaph.xdm.adblock.AdBlocker
import com.olaph.xdm.adblock.FilterListRepository
import com.olaph.xdm.core.DownloadManager
import com.olaph.xdm.core.FileSaver
import com.olaph.xdm.core.NetworkMonitor
import com.olaph.xdm.crash.CrashReporter
import com.olaph.xdm.data.ALL_MIGRATIONS
import com.olaph.xdm.data.BrowserDao
import com.olaph.xdm.data.DownloadDao
import com.olaph.xdm.data.SettingsRepository
import com.olaph.xdm.data.XdmDatabase
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.HlsEngine
import com.olaph.xdm.service.DownloadService
import com.olaph.xdm.service.NotificationHelper
import com.olaph.xdm.youtube.NewPipeResolver
import com.olaph.xdm.youtube.YoutubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/** Manual dependency container (no DI framework). */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Never destructive: existing downloads must survive upgrades, so a missing migration is a crash, not a wipe.
    private val db = Room.databaseBuilder(app, XdmDatabase::class.java, "xdm.db")
        .addMigrations(*ALL_MIGRATIONS)
        .build()
    val dao: DownloadDao = db.downloadDao()
    val browserDao: BrowserDao = db.browserDao()
    val settings = SettingsRepository(app)
    val fileSaver = FileSaver(app)
    val notifications = NotificationHelper(app, fileSaver)
    val network = NetworkMonitor(app)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        // HTTP/2 would multiplex all segments over one TCP connection and defeat multi-connection downloads.
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    val youtube: YoutubeResolver = NewPipeResolver(httpClient)

    val adBlocker = AdBlocker()
    val filterLists = FilterListRepository(
        dir = File(app.filesDir, "adblock"),
        openAsset = { app.assets.open(it) },
        client = httpClient,
        adBlocker = adBlocker,
        updateBlockedReason = {
            if (settings.current().wifiOnly && network.metered.value) {
                "Wi-Fi only is on and this network is metered. Connect to Wi-Fi or turn the setting off."
            } else {
                null
            }
        },
        appVersion = BuildConfig.VERSION_CODE,
    )

    val manager = DownloadManager(
        scope = scope,
        dao = dao,
        settings = settings,
        engine = DownloadEngine(httpClient),
        fileSaver = fileSaver,
        tempDir = (app.getExternalFilesDir("downloads") ?: app.filesDir.resolve("downloads")),
        network = network,
        notifier = notifications,
        hlsEngine = HlsEngine(httpClient),
        youtube = youtube,
    )

    fun start() {
        manager.restore()
        // Ad blocker: settings first (cheap), then the filter lists off the main thread (compiled cache when nothing
        // changed). Until both are in, nothing is blocked. Toggling a list in Settings reloads.
        scope.launch { settings.flow.collect { adBlocker.configure(it.blockAds, it.adAllowedSites, it.popupMode) } }
        scope.launch(Dispatchers.IO) {
            settings.flow.map { it.enabledFilterLists }.distinctUntilChanged().collectLatest { filterLists.load(it) }
        }
        // Keep a foreground service alive exactly while something is queued or running.
        scope.launch {
            dao.observeActiveCount().map { it > 0 }.distinctUntilChanged().filter { it }
                .collect { DownloadService.start(app) }
        }
    }
}

class XdmApp : Application() {
    lateinit var crashReporter: CrashReporter
        private set
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while building the container is also caught.
        crashReporter = CrashReporter.create(this)
        runCatching { crashReporter.install() }
        container = AppContainer(this)
        container.start()
        container.scope.launch { crashReporter.flushPending() }
    }
}
