package com.olaph.xdm.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olaph.xdm.browser.BlockNotice
import com.olaph.xdm.browser.BrowserPage
import com.olaph.xdm.browser.BrowserTab
import com.olaph.xdm.browser.BrowserViewModel
import com.olaph.xdm.browser.FullscreenVideo
import com.olaph.xdm.browser.LinkTarget
import com.olaph.xdm.engine.UrlInput
import com.olaph.xdm.engine.UrlUtils

/** Entry point of the "Browser" bottom tab. */
@Composable
fun BrowserScreen(vm: BrowserViewModel, onOpenSettings: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(dark) { vm.updateDarkTheme(dark) }

    // <input type=file>: the WebView hands over an intent; the picked Uris go back through the callback.
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.completeFileChooser(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }
    val request = vm.fileChooser
    LaunchedEffect(request) {
        if (request == null || request.launched) return@LaunchedEffect
        request.launched = true // survives recomposition / rotation while the picker is open
        try {
            chooser.launch(request.params.createIntent())
        } catch (_: ActivityNotFoundException) {
            vm.completeFileChooser(null)
        }
    }

    when (vm.page) {
        BrowserPage.TABS -> TabSwitcherPage(vm)
        BrowserPage.BOOKMARKS -> BookmarksPage(vm)
        BrowserPage.HISTORY -> HistoryPage(vm)
        BrowserPage.TAB -> vm.current?.let { tab -> BrowserTabPage(vm, tab, onOpenSettings) }
    }
}

@Composable
private fun BrowserTabPage(vm: BrowserViewModel, tab: BrowserTab, onOpenSettings: () -> Unit) {
    var shieldOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val notice = vm.notice
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = when (notice.kind) {
                BlockNotice.Kind.POPUP -> "Pop-up blocked"
                BlockNotice.Kind.NAVIGATION -> "Blocked: ${UrlInput.displayHost(notice.url) ?: "ad page"}"
            },
            actionLabel = if (notice.kind == BlockNotice.Kind.POPUP) "Open" else "Open anyway",
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.openNotice(notice) else vm.dismissNotice(notice)
    }
    Column(Modifier.fillMaxSize()) {
        AddressBar(
            tab = tab,
            onSubmit = vm::load,
            onReloadOrStop = { if (tab.loading) vm.stop() else vm.reload() },
            shieldActive = vm.settings.blockAds && !vm.isSiteAllowed(tab),
            onShield = { shieldOpen = true },
        )
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (tab.loading) {
                LinearProgressIndicator(progress = { (tab.progress / 100f).coerceIn(0.02f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (tab.findActive) FindBar(vm, tab)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.showHome) {
                BrowserHome(vm, tab)
            } else {
                key(tab.id, tab.webViewEpoch) { WebViewHost(vm, tab) }
                if (vm.fullscreen == null) MediaButton(vm, tab, Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        HorizontalDivider()
        BottomToolbar(vm, tab, onOpenSettings)
    }
    vm.fullscreen?.let { FullscreenVideoHost(vm, it) }
    if (tab.mediaSheetOpen && vm.fullscreen == null) MediaSheet(vm, tab)
    if (shieldOpen) AdShieldSheet(vm, tab) { shieldOpen = false }
    vm.linkMenu?.let { LinkMenu(vm, it) }
}

/** Shield sheet: what was blocked on this page, by kind, and the per-site "allow ads" switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdShieldSheet(vm: BrowserViewModel, tab: BrowserTab, onDismiss: () -> Unit) {
    val host = com.olaph.xdm.adblock.HostNames.hostOf(tab.url)
    val allowed = vm.isSiteAllowed(tab)
    val masterOn = vm.settings.blockAds
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Ad blocker", style = MaterialTheme.typography.titleLarge)
            Text(host.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            when {
                !masterOn -> Text("Ad blocking is turned off in Settings.", style = MaterialTheme.typography.bodyLarge)
                allowed -> Text("Ads are allowed on ${host.orEmpty()}.", style = MaterialTheme.typography.bodyLarge)
                else -> {
                    Text(
                        "${tab.blockedCount} blocked on this page",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    ShieldCountRow("Requests (ads, trackers)", tab.blockedRequests)
                    ShieldCountRow("Pop-ups", tab.blockedPopups)
                    ShieldCountRow("Redirects", tab.blockedRedirects)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = host != null) { vm.setSiteAllowed(tab, !allowed); onDismiss() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Allow ads on this site", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(checked = allowed, onCheckedChange = null, enabled = host != null)
            }
            Text(
                "The page reloads when you change this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShieldCountRow(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
    }
}

// ---- WebView host ----

@Composable
private fun WebViewHost(vm: BrowserViewModel, tab: BrowserTab) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { FrameLayout(it) },
        update = { frame ->
            val wv = vm.webViewFor(tab)
            if (wv.parent !== frame) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                frame.removeAllViews()
                // Dialogs and pickers spawned by the WebView need the activity while it is on screen.
                (wv.context as? MutableContextWrapper)?.baseContext = frame.context
                frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        onRelease = { frame ->
            // Keep the WebView (and its page) alive in the view model; just let go of the activity.
            (frame.getChildAt(0) as? WebView)?.let { wv ->
                (wv.context as? MutableContextWrapper)?.baseContext = vm.appContext
            }
            frame.removeAllViews()
        },
    )
    LifecycleResumeEffect(tab.id) {
        tab.webView?.onResume()
        onPauseOrDispose {
            tab.webView?.onPause()
            vm.flushCookies()
        }
    }
}

/**
 * The page's fullscreen video, laid over the whole window on black: system bars hidden (they come back on a swipe),
 * screen kept on, and any orientation allowed. Leaving this composition (back, other tab, other screen) ends it.
 * Rotation does not recreate the activity (configChanges), so the video keeps playing in place.
 */
@Composable
private fun FullscreenVideoHost(vm: BrowserViewModel, fs: FullscreenVideo) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(fs) {
        val window = activity.window
        val decor = window.decorView as ViewGroup
        val frame = FrameLayout(activity).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
            isClickable = true // touches the video does not use must not reach the page underneath
        }
        (fs.view.parent as? ViewGroup)?.removeView(fs.view)
        frame.addView(fs.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        decor.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bars = WindowCompat.getInsetsController(window, decor)
        val previousBehavior = bars.systemBarsBehavior
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        onDispose {
            activity.requestedOrientation = previousOrientation
            bars.show(WindowInsetsCompat.Type.systemBars())
            bars.systemBarsBehavior = previousBehavior
            frame.removeAllViews()
            decor.removeView(frame)
            vm.exitFullscreen(only = fs)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ---- address bar ----

@Composable
private fun AddressBar(
    tab: BrowserTab,
    onSubmit: (String) -> Unit,
    onReloadOrStop: () -> Unit,
    shieldActive: Boolean,
    onShield: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var hadFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val url = tab.url
    val secure = url?.startsWith("https://", ignoreCase = true) == true

    LaunchedEffect(editing) { if (editing) focus.requestFocus() }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    tab.incognito -> Icons.Filled.VisibilityOff
                    url == null || tab.showHome -> Icons.Filled.Search
                    secure -> Icons.Filled.Lock
                    else -> Icons.Filled.LockOpen
                },
                contentDescription = when {
                    secure -> "Secure connection (HTTPS)"
                    url?.startsWith("http://") == true -> "Not secure (HTTP)"
                    else -> null
                },
                tint = if (url != null && !secure && !tab.showHome && url.startsWith("http://")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            if (editing) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        editing = false
                        focusManager.clearFocus()
                        if (text.text.isNotBlank()) onSubmit(text.text)
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                        .focusRequester(focus)
                        .onFocusChanged {
                            if (it.isFocused) hadFocus = true else if (hadFocus) { editing = false; hadFocus = false }
                        },
                )
                IconButton(onClick = { text = TextFieldValue("") }) { Icon(Icons.Filled.Close, "Clear") }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable {
                            val current = if (tab.showHome) "" else url.orEmpty()
                            text = TextFieldValue(current, TextRange(0, current.length))
                            editing = true
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    if (tab.showHome || url == null) {
                        Text("Search or enter address", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    } else {
                        Text(tab.displayTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!tab.showHome && url != null) {
                    if (UrlUtils.isHttpUrl(url)) {
                        IconButton(onClick = onShield) {
                            BadgedBox(badge = {
                                if (shieldActive && tab.blockedCount > 0) {
                                    Badge { Text(if (tab.blockedCount > 99) "99+" else tab.blockedCount.toString()) }
                                }
                            }) {
                                Icon(
                                    if (shieldActive) Icons.Filled.Shield else Icons.Outlined.Shield,
                                    contentDescription = if (shieldActive) "Ad blocker: ${tab.blockedCount} blocked" else "Ad blocker off for this site",
                                    tint = if (shieldActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    IconButton(onClick = onReloadOrStop) {
                        if (tab.loading) Icon(Icons.Filled.Close, "Stop") else Icon(Icons.Filled.Refresh, "Reload")
                    }
                }
            }
        }
    }
}

// ---- find in page ----

@Composable
private fun FindBar(vm: BrowserViewModel, tab: BrowserTab) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = tab.findQuery,
            onValueChange = { vm.find(tab, it) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.findNext(tab, true) }),
            modifier = Modifier.weight(1f).focusRequester(focus).padding(vertical = 10.dp),
            decorationBox = { inner ->
                if (tab.findQuery.isEmpty()) Text("Find in page", color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            },
        )
        if (tab.findQuery.isNotEmpty()) {
            Text(if (tab.findCount == 0) "0/0" else "${tab.findIndex + 1}/${tab.findCount}", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { vm.findNext(tab, false) }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous match") }
        IconButton(onClick = { vm.findNext(tab, true) }) { Icon(Icons.Filled.KeyboardArrowDown, "Next match") }
        IconButton(onClick = { vm.closeFind(tab) }) { Icon(Icons.Filled.Close, "Close find") }
    }
}

// ---- bottom toolbar and menu ----

@Composable
private fun BottomToolbar(vm: BrowserViewModel, tab: BrowserTab, onOpenSettings: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::goBack, enabled = tab.canGoBack && !tab.showHome) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        IconButton(onClick = vm::goForward, enabled = tab.canGoForward && !tab.showHome) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
        }
        IconButton(onClick = vm::goHome) { Icon(Icons.Filled.Home, "Home") }
        IconButton(onClick = { vm.page = BrowserPage.TABS }) {
            Box(
                Modifier.size(24.dp).border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(vm.tabs.size.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
            BrowserMenu(vm, tab, expanded = menuOpen, onDismiss = { menuOpen = false }, onOpenSettings = onOpenSettings)
        }
    }
}

@Composable
private fun BrowserMenu(vm: BrowserViewModel, tab: BrowserTab, expanded: Boolean, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val pageUrl = tab.url?.takeIf { UrlUtils.isHttpUrl(it) && !tab.showHome }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val bookmarked = pageUrl != null && bookmarks.any { it.url == pageUrl }

    @Composable
    fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, checked: Boolean = false, action: () -> Unit) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { Icon(icon, null) },
            trailingIcon = if (checked) ({ Icon(Icons.Filled.Check, null) }) else null,
            enabled = enabled,
            onClick = { onDismiss(); action() },
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        item("New tab", Icons.Filled.Add) { vm.newTab() }
        item("New incognito tab", Icons.Filled.VisibilityOff) { vm.newTab(incognito = true) }
        HorizontalDivider()
        item(if (bookmarked) "Remove bookmark" else "Add bookmark", if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, enabled = pageUrl != null) {
            vm.toggleBookmark(tab)
        }
        item("Bookmarks", Icons.Filled.Bookmarks) { vm.page = BrowserPage.BOOKMARKS }
        item("History", Icons.Filled.History) { vm.page = BrowserPage.HISTORY }
        HorizontalDivider()
        item("Share page", Icons.Filled.Share, enabled = pageUrl != null) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, pageUrl).putExtra(Intent.EXTRA_SUBJECT, tab.displayTitle)
            context.startActivity(Intent.createChooser(send, "Share page"))
        }
        item("Copy link", Icons.Filled.ContentCopy, enabled = pageUrl != null) { copyToClipboard(context, pageUrl.orEmpty()) }
        item("Open in external browser", Icons.AutoMirrored.Filled.OpenInNew, enabled = pageUrl != null) {
            openExternally(context, pageUrl.orEmpty())
        }
        item("Desktop site", Icons.Filled.DesktopWindows, checked = tab.desktop) { vm.toggleDesktop(tab) }
        val siteAllowed = pageUrl != null && vm.isSiteAllowed(tab)
        item("Allow ads on this site", Icons.Filled.Shield, enabled = pageUrl != null, checked = siteAllowed) {
            vm.setSiteAllowed(tab, !siteAllowed)
        }
        item("Find in page", Icons.Filled.Search, enabled = pageUrl != null) { vm.startFind(tab) }
        HorizontalDivider()
        item("Settings", Icons.Filled.Settings) { onOpenSettings() }
    }
}

// ---- long-press menu ----

@Composable
private fun LinkMenu(vm: BrowserViewModel, target: LinkTarget) {
    val context = LocalContext.current
    val dismiss = { vm.linkMenu = null }
    val link = target.linkUrl
    val image = target.imageUrl
    val video = target.videoUrl

    @Composable
    fun action(label: String, run: () -> Unit) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable { dismiss(); run() }.padding(vertical = 14.dp),
        )
    }

    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        title = {
            Text(
                link ?: image ?: video?.takeIf { it != "blob" } ?: "Video",
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Column {
                if (video != null) {
                    action("Download video with XDM") { vm.downloadVideoFromMenu(target.tabId, video) }
                    if (video != "blob") action("Copy video URL") { copyToClipboard(context, video) }
                }
                if (link != null) {
                    action("Download with XDM") { vm.downloadLink(target.tabId, link) }
                    action("Open in new tab") { vm.newTab(link) }
                    action("Copy link") { copyToClipboard(context, link) }
                    action("Share link") { shareText(context, link) }
                }
                if (image != null) {
                    action("Download image with XDM") { vm.downloadLink(target.tabId, image) }
                    if (link == null) {
                        action("Open image in new tab") { vm.newTab(image) }
                        action("Copy image URL") { copyToClipboard(context, image) }
                    }
                }
            }
        },
    )
}

internal fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share link"))
}

internal fun openExternally(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}
