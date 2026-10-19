package com.olaph.xdm

import android.app.Application
import androidx.room.Room
import com.olaph.xdm.adblock.AdBlocker
import com.olaph.xdm.adblock.FilterListRepository
import com.olaph.xdm.browser.HeadlessRefresher
import com.olaph.xdm.core.AppliedLink
import com.olaph.xdm.core.AutoRefresh
import com.olaph.xdm.core.DownloadManager
import com.olaph.xdm.core.FileSaver
import com.olaph.xdm.core.Hold
import com.olaph.xdm.core.NetworkMonitor
import com.olaph.xdm.core.PowerMonitor
import com.olaph.xdm.core.RefreshOutcome
import com.olaph.xdm.core.RefreshPort
import com.olaph.xdm.crash.CrashReporter
import com.olaph.xdm.data.ALL_MIGRATIONS
import com.olaph.xdm.data.BrowserDao
import com.olaph.xdm.data.DownloadDao
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.SettingsRepository
import com.olaph.xdm.data.XdmDatabase
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.HlsEngine
import com.olaph.xdm.engine.SpeedLimiter
import com.olaph.xdm.service.DownloadService
import com.olaph.xdm.service.NotificationHelper
import com.olaph.xdm.service.ScheduleAlarm
import com.olaph.xdm.vault.VaultLock
import com.olaph.xdm.vault.VaultStore
import com.olaph.xdm.youtube.NewPipeResolver
import com.olaph.xdm.youtube.YoutubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    // Read fresh at every save, so a folder chosen a moment ago is used by the next download that finishes.
    val fileSaver = FileSaver(
        app,
        target = { runBlocking(Dispatchers.IO) { settings.current() }.saveTarget },
        onFolderUnavailable = { scope.launch { settings.setSaveFolderWarning(true) } },
    )
    val notifications = NotificationHelper(app, fileSaver)
    val network = NetworkMonitor(app)
    val power = PowerMonitor(app)

    /** The one speed cap, shared by every connection of every download. */
    val speedLimiter = SpeedLimiter()

    /** The private folder (app-internal storage) and its PIN lock. */
    val vault = VaultStore(File(app.filesDir, VaultStore.DIR_NAME))
    val vaultLock = VaultLock(app, scope, settings)

    // Partial downloads (private ones included) wait here until they are finished; keep the gallery out of it.
    private val tempDir = (app.getExternalFilesDir("downloads") ?: app.filesDir.resolve("downloads")).also { dir ->
        runCatching { dir.mkdirs(); File(dir, ".nomedia").createNewFile() }
    }

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

    // A retry after the network dropped waits for it to come back instead of using up its attempts.
    private suspend fun awaitNetwork() {
        network.online.first { it }
    }

    val manager = DownloadManager(
        scope = scope,
        dao = dao,
        settings = settings,
        engine = DownloadEngine(httpClient, limiter = speedLimiter, awaitNetwork = { awaitNetwork() }),
        fileSaver = fileSaver,
        tempDir = tempDir,
        network = network,
        notifier = notifications,
        hlsEngine = HlsEngine(httpClient, limiter = speedLimiter, awaitNetwork = { awaitNetwork() }),
        youtube = youtube,
        // The process may have been started by the system (reboot, app update); see releaseQueue().
        holdQueue = true,
        privateStore = vault,
        power = power,
        targetFreeSpace = fileSaver::targetFreeSpace,
        plannedFolder = fileSaver::plannedFolder,
    )

    /** Gets a new link for a download that failed with an expired one, from its page, in the background. */
    val autoRefresh = AutoRefresh(
        scope = scope,
        settings = settings,
        port = object : RefreshPort {
            override suspend fun get(id: Long) = dao.get(id)

            override suspend fun apply(id: Long, found: RefreshOutcome.Found) =
                manager.applyRefreshedLink(id, found.url, found.probe, found.headers, found.pageUrl) == AppliedLink.Replaced
        },
        finder = HeadlessRefresher(app, adBlocker) { url, headers -> manager.probe(url, headers) },
        notifier = notifications,
    )

    /** Downloads may run in this process: an activity is on screen or the foreground service is up. */
    fun releaseQueue() {
        manager.releaseQueue()
        notifications.cancelResumePrompt()
    }

    /** The user is looking at the app: queued downloads may run, and what Android's time limit paused carries on. */
    fun onAppVisible() {
        releaseQueue()
        notifications.cancelAttention()
        manager.resumeSystemPaused()
    }

    fun start() {
        vaultLock.start()
        manager.restore()
        manager.interceptFailure = { autoRefresh.onFailed(it) }
        // The schedule opened (or the charger came) while nothing ran: the service has to start, or the user is told.
        manager.onScheduleReady = {
            if (!DownloadService.start(app)) {
                scope.launch { notifications.showScheduledReady(dao.getByStatus(DownloadStatus.QUEUED).size) }
            }
        }
        scope.launch { settings.flow.map { it.speedLimitBytesPerSec }.distinctUntilChanged().collect { speedLimiter.bytesPerSecond = it } }
        scope.launch { settings.flow.map { it.schedule }.distinctUntilChanged().collect { ScheduleAlarm.reschedule(app, it) } }
        // Ad blocker: settings first (cheap), then the filter lists off the main thread (compiled cache when nothing
        // changed). Until both are in, nothing is blocked. Toggling a list in Settings reloads.
        scope.launch { settings.flow.collect { adBlocker.configure(it.blockAds, it.adAllowedSites, it.popupMode) } }
        scope.launch(Dispatchers.IO) {
            settings.flow.map { it.enabledFilterLists }.distinctUntilChanged().collectLatest { filterLists.load(it) }
        }
        // Keep a foreground service alive exactly while something runs or may start now (once the queue may run). Downloads
        // that wait for the schedule or the charger do not count: an idle service only uses up the system's time budget.
        scope.launch {
            combine(dao.observeOpen(), manager.queueReleased, manager.hold, autoRefresh.active) { open, released, hold, refreshing ->
                val clockHold = hold == Hold.SCHEDULE || hold == Hold.CHARGING
                // A background link refresh needs the process kept up as much as a download does.
                released && (refreshing || open.any {
                    it.status == DownloadStatus.RUNNING || (it.status == DownloadStatus.QUEUED && (it.forceStart || !clockHold))
                })
            }.distinctUntilChanged().filter { it }
                .collect {
                    if (!DownloadService.start(app)) {
                        val waiting = dao.getByStatus(DownloadStatus.QUEUED).size
                        if (waiting > 0) notifications.showResumePrompt(waiting, waitingForWifi = false)
                    }
                }
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
