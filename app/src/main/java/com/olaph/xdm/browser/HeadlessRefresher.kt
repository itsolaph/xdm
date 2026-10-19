package com.olaph.xdm.browser

import android.annotation.SuppressLint
import android.app.Application
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.olaph.xdm.adblock.AdBlocker
import com.olaph.xdm.adblock.BlockedResponse
import com.olaph.xdm.adblock.RequestAction
import com.olaph.xdm.adblock.filters.PageContext
import com.olaph.xdm.adblock.filters.RequestTypeInference
import com.olaph.xdm.core.LinkFinder
import com.olaph.xdm.core.RefreshMatcher
import com.olaph.xdm.core.RefreshOutcome
import com.olaph.xdm.core.RefreshTarget
import com.olaph.xdm.engine.DownloadHeaders
import com.olaph.xdm.engine.ProbeResult
import com.olaph.xdm.engine.UrlUtils
import com.olaph.xdm.media.MediaClassifier
import com.olaph.xdm.media.MediaCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Looks for a new link without showing anything: loads the download's page in a WebView of its own (never attached to a
 * window), with the browser's cookies, starts its videos muted, and checks every file the page requests the way the visible
 * refresh does ([RefreshMatcher]: the size must be exactly the download's).
 *
 * The WebView lives only for one call. If none can be created (no WebView provider, a state where the system refuses),
 * [RefreshOutcome.Unavailable] is returned and nothing else happens.
 */
class HeadlessRefresher(
    private val app: Application,
    private val adBlocker: AdBlocker,
    private val probe: suspend (url: String, headers: Map<String, String>) -> ProbeResult,
) : LinkFinder {
    private val probeGate = Semaphore(3)

    override suspend fun find(page: String, target: RefreshTarget): RefreshOutcome = withContext(Dispatchers.Main) {
        val session = try {
            Session(page, target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return@withContext RefreshOutcome.Unavailable
        }
        try {
            session.run()
        } finally {
            session.destroy()
        }
    }

    /** One WebView, one page. Everything but the network callbacks runs on the main thread. */
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature") // the document-start script is guarded by documentStartSupported
    private inner class Session(private val startUrl: String, target: RefreshTarget) {
        private val handler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val result = CompletableDeferred<RefreshOutcome>()
        private val collector = MediaCollector().also { it.reset(startUrl) }
        private val matcher = RefreshMatcher(target) { url, headers -> probeGate.withPermit { probe(url, headers) } }

        @Volatile private var pageUrl: String = startUrl
        @Volatile private var context: PageContext? = null
        @Volatile private var contextKey: Pair<Any?, String>? = null
        private val webView: WebView = WebView(MutableContextWrapper(app))

        init {
            try {
                configure()
            } catch (e: Throwable) {
                runCatching { webView.destroy() }
                throw e
            }
        }

        private fun configure() {
            val wv = webView
            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // No windows of its own: a pop-up is not something to follow in the background.
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = Client()
            wv.setDownloadListener { url, ua, _, _, _ -> if (UrlUtils.isHttpUrl(url)) verify(url, referer = pageUrl, userAgent = ua) }
            wv.addJavascriptInterface(
                MediaBridge(
                    mediaSink = { url, _ -> if (!blocked(url)) offer(url, null) },
                    playSink = {},
                    touchSink = {},
                ),
                MediaScript.BRIDGE_NAME,
            )
            if (documentStartSupported) runCatching { WebViewCompat.addDocumentStartJavaScript(wv, MediaScript.SOURCE, setOf("*")) }
        }

        suspend fun run(): RefreshOutcome {
            webView.loadUrl(startUrl)
            return result.await()
        }

        fun destroy() {
            scope.cancel()
            handler.removeCallbacksAndMessages(null)
            runCatching {
                webView.stopLoading()
                webView.removeJavascriptInterface(MediaScript.BRIDGE_NAME)
                webView.webViewClient = WebViewClient()
                webView.destroy()
            }
        }

        private fun blocked(url: String): Boolean = adBlocker.shouldBlockMediaUrl(url, contextFor())

        private fun contextFor(): PageContext {
            val key = adBlocker.currentEngine to pageUrl
            context?.let { if (contextKey == key) return it }
            return adBlocker.page(pageUrl).also { context = it; contextKey = key }
        }

        /** A request or DOM URL that might be the video: classified like the browser does, then checked. */
        private fun offer(url: String, referer: String?) {
            val c = collector.offer(url, referer ?: pageUrl) ?: return
            if (c.kind != MediaClassifier.Kind.VIDEO_FILE && c.kind != MediaClassifier.Kind.AUDIO_FILE &&
                c.kind != MediaClassifier.Kind.UNKNOWN_MEDIA
            ) return
            verify(c.url, c.referer, userAgent = null)
        }

        private fun verify(url: String, referer: String?, userAgent: String?) {
            scope.launch {
                // Cookies and user agent are read where the page's own requests would get them.
                val ua = userAgent ?: webView.settings.userAgentString
                val headers = DownloadHeaders.fromBrowser(CookieManager.getInstance().getCookie(url), ua, referer ?: pageUrl, withOrigin = true)
                val probed = withContext(Dispatchers.IO) { matcher.check(url, headers) } ?: return@launch
                val page = pageUrl.takeIf { UrlUtils.isHttpUrl(it) } ?: startUrl
                result.complete(RefreshOutcome.Found(url, headers, page, probed))
            }
        }

        private fun startPlayback(view: WebView) {
            for (delay in RefreshScript.RETRY_DELAYS_MS) {
                handler.postDelayed({ if (!result.isCompleted) view.evaluateJavascript(RefreshScript.PLAY, null) }, delay)
            }
        }

        private inner class Client : WebViewClient() {
            // Pages that hand over to an app (intent:, market:...) are not followed.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !UrlUtils.isHttpUrl(request.url.toString())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (request.isForMainFrame) {
                    pageUrl = url
                    return null
                }
                val type = RequestTypeInference.infer(url, false, request.requestHeaders.orEmpty())
                return when (adBlocker.decide(url, type, contextFor(), false, request.method == "GET")) {
                    RequestAction.BLOCK -> WebResourceResponse(
                        BlockedResponse.mimeFor(url), "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)),
                    )
                    RequestAction.SNIFF -> {
                        offer(url, request.requestHeaders["Referer"])
                        null
                    }
                    RequestAction.PASS -> null
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageUrl = url
                collector.reset(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageUrl = url
                view.evaluateJavascript(MediaScript.SOURCE, null) // idempotent; covers WebViews without document-start scripts
                startPlayback(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) result.complete(RefreshOutcome.NotFound)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) result.complete(RefreshOutcome.NotFound)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                result.complete(RefreshOutcome.NotFound)
                return true
            }
        }
    }

    private val documentStartSupported: Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)
}
