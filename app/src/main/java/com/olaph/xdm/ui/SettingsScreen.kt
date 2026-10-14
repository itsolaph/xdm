package com.olaph.xdm.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olaph.xdm.BuildConfig
import com.olaph.xdm.adblock.PopupMode
import com.olaph.xdm.adblock.UpdateState
import com.olaph.xdm.core.RestartResume
import com.olaph.xdm.crash.TestReportResult
import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.ThemeMode
import com.olaph.xdm.engine.SearchEngine
import com.olaph.xdm.youtube.YoutubeQuality
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SliderSetting(
                title = "Default connections per download",
                value = settings.connections,
                range = 1..16,
                onCommit = vm::setConnections,
            )
            SliderSetting(
                title = "Max parallel downloads",
                value = settings.maxParallel,
                range = 1..10,
                onCommit = vm::setMaxParallel,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Row(
                Modifier.fillMaxWidth().clickable { vm.setWifiOnly(!settings.wifiOnly) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Wait while connected to a metered network (mobile data, hotspot)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.wifiOnly, onCheckedChange = vm::setWifiOnly)
            }
            Row(
                Modifier.fillMaxWidth().clickable { vm.setResumeAfterRestart(!settings.resumeAfterRestart) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Resume downloads after restart", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (Build.VERSION.SDK_INT >= RestartResume.BOOT_FGS_RESTRICTED_SDK) {
                            "After a reboot, a notification offers to resume interrupted downloads (Android doesn't let them start on their own). After an app update they continue by themselves."
                        } else {
                            "Interrupted downloads continue by themselves after a reboot or an app update"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.resumeAfterRestart, onCheckedChange = vm::setResumeAfterRestart)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Theme", style = MaterialTheme.typography.titleSmall)
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setTheme(mode) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.theme == mode, onClick = { vm.setTheme(mode) })
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "System default"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Browser search engine", style = MaterialTheme.typography.titleSmall)
            SearchEngine.entries.forEach { engine ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setSearchEngine(engine) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.searchEngine == engine, onClick = { vm.setSearchEngine(engine) })
                    Text(engine.label)
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { vm.setInterceptDownloads(!settings.interceptDownloads) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Intercept all browser downloads", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Send downloads started in the built-in browser to XDM instead of the system downloader",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.interceptDownloads, onCheckedChange = vm::setInterceptDownloads)
            }
            Row(
                Modifier.fillMaxWidth().clickable { vm.setConvertHlsToMp4(!settings.convertHlsToMp4) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Convert HLS to MP4", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Turn finished HLS videos (MPEG-TS) into a normal .mp4 that plays in any gallery or player. " +
                            "No re-encoding; needs free space about the size of the video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.convertHlsToMp4, onCheckedChange = vm::setConvertHlsToMp4)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Default YouTube quality", style = MaterialTheme.typography.titleSmall)
            Text(
                "\"Ask every time\" shows the format list for each YouTube link; any other choice adds the download " +
                    "straight away (the closest lower resolution if a video doesn't have it).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            YoutubeQuality.entries.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setYoutubeQuality(q) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.youtubeQuality == q, onClick = { vm.setYoutubeQuality(q) })
                    Text(q.label)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            AdBlockSettings(vm, settings)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            UserAgentSetting(settings.userAgent, onSave = vm::setUserAgent)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            CrashReportSettings(vm)
            Spacer(Modifier.height(16.dp))
            var licensesOpen by remember { mutableStateOf(false) }
            Text(
                "About & licenses",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().clickable { licensesOpen = true }.padding(vertical = 8.dp),
            )
            if (licensesOpen) LicensesDialog(vm) { licensesOpen = false }
            Spacer(Modifier.height(8.dp))
            Text(
                "XDM ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdBlockSettings(vm: MainViewModel, settings: AppSettings) {
    val info by vm.filterListsInfo.collectAsStateWithLifecycle()
    val update by vm.filterListsUpdate.collectAsStateWithLifecycle()

    Text("Ad blocker", style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier.fillMaxWidth().clickable { vm.setBlockAds(!settings.blockAds) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Block ads", style = MaterialTheme.typography.titleSmall)
            Text(
                "In the built-in browser: blocks ad and tracker requests with filter lists (EasyList, AdGuard) and hides ad elements",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = settings.blockAds, onCheckedChange = vm::setBlockAds)
    }

    Text("Block pop-ups", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(
        when (settings.popupMode) {
            PopupMode.OFF -> "Pages may open new windows"
            PopupMode.ADS_ONLY -> "Blocks ad pop-ups and windows opened without a tap"
            PopupMode.ALL -> "Blocks every window a page opens; \"Open\" in the notice still opens it"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        PopupMode.entries.forEach { mode ->
            Row(Modifier.clickable { vm.setPopupMode(mode) }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.popupMode == mode, onClick = { vm.setPopupMode(mode) })
                Text(mode.label)
            }
        }
    }

    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Filter lists", style = MaterialTheme.typography.titleSmall)
        Text(
            when {
                info.loading -> "Loading…"
                !info.loaded -> "Not loaded"
                else -> "%,d rules active · loaded in %.1f s%s".format(info.totalRules, info.loadMillis / 1000.0, if (info.fromCache) " (cached)" else "")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val dateFormat = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }
    info.lists.forEach { list ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.setFilterListEnabled(list.id, !list.enabled) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(list.title, style = MaterialTheme.typography.bodyLarge)
                val date = if (list.updatedAtMillis > 0) dateFormat.format(java.util.Date(list.updatedAtMillis)) else null
                Text(
                    when {
                        !list.enabled -> "Off"
                        !info.loaded -> "…"
                        !list.updatable -> "%,d rules · built in".format(list.ruleCount)
                        date == null -> "%,d rules".format(list.ruleCount)
                        else -> "%,d rules · %s %s".format(list.ruleCount, if (list.bundled) "snapshot of" else "updated", date)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = list.enabled, onCheckedChange = { vm.setFilterListEnabled(list.id, it) })
        }
    }
    Column(Modifier.padding(vertical = 4.dp)) {
        when (val u = update) {
            is UpdateState.Failed -> Text(u.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            is UpdateState.Done -> {
                Text("Updated %d list%s: %,d rules active".format(u.updated, if (u.updated == 1) "" else "s", u.totalRules), style = MaterialTheme.typography.bodySmall)
                u.failures.forEach { (title, reason) ->
                    Text("$title: $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> Unit
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.clearFilterListsResult(); vm.updateFilterLists() }, enabled = update !is UpdateState.Running) {
                Text(if (update is UpdateState.Running) "Updating…" else "Update filter lists")
            }
            if (update is UpdateState.Running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        if (settings.wifiOnly) {
            Text("Downloads only on Wi-Fi (Wi-Fi only is on)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    val sites = settings.adAllowedSites.sorted()
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Allowed sites (${sites.size})", style = MaterialTheme.typography.titleSmall)
        Text(
            if (sites.isEmpty()) "Sites where you allowed ads appear here" else "Ads are not blocked on these sites",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    sites.forEach { site ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(site, Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = { vm.removeAllowedSite(site) }) { Icon(Icons.Filled.Close, "Remove $site") }
        }
    }
}

@Composable
private fun CrashReportSettings(vm: MainViewModel) {
    val enabled by vm.crashReportsEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }

    if (!vm.crashReportsAvailable) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Send crash reports", style = MaterialTheme.typography.titleSmall)
            Text(
                "Crash reports are not set up in this build.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Row(
        Modifier.fillMaxWidth().clickable { vm.setCrashReportsEnabled(!enabled) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Send crash reports", style = MaterialTheme.typography.titleSmall)
            Text(
                "If the app crashes, send the error details to the developer on the next start. " +
                    "No links, page titles, file names or browsing history are included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = vm::setCrashReportsEnabled)
    }
    TextButton(
        onClick = {
            sending = true
            scope.launch {
                val message = when (vm.sendTestCrashReport()) {
                    TestReportResult.SENT -> "Test report sent"
                    TestReportResult.RETRY_LATER -> "Could not send now, will retry later"
                    TestReportResult.REJECTED -> "Test report was rejected by the server"
                    TestReportResult.DISABLED -> "Turn on Send crash reports first"
                    TestReportResult.UNAVAILABLE -> "Crash reports are not set up in this build"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                sending = false
            }
        },
        enabled = enabled && !sending,
    ) {
        Text(if (sending) "Sending…" else "Send test report")
    }
}

@Composable
private fun LicensesDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val texts = remember {
        listOf(
            "YouTube support (NewPipeExtractor)" to vm.licenseText("licenses/newpipe-extractor-NOTICE.txt"),
            "Filter lists" to vm.licenseText("licenses/filter-lists-NOTICE.txt"),
            "StevenBlack/hosts — MIT License" to vm.licenseText("licenses/stevenblack-hosts-LICENSE.txt"),
            "GNU General Public License v3 (NewPipeExtractor, EasyList, AdGuard filters)" to vm.licenseText("licenses/GPL-3.0.txt"),
            "Mozilla Public License 2.0 (Public Suffix List)" to vm.licenseText("licenses/MPL-2.0.txt"),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("About & licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                texts.forEach { (title, body) ->
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(body, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                }
            }
        },
    )
}

@Composable
private fun SliderSetting(title: String, value: Int, range: IntRange, onCommit: (Int) -> Unit) {
    // Keep the thumb responsive while dragging; only write to DataStore when released.
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value else dragging.toInt()
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("$title: $shown", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = shown.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (!dragging.isNaN()) onCommit(dragging.toInt())
                dragging = Float.NaN
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
    }
}

@Composable
private fun UserAgentSetting(current: String, onSave: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    Column {
        Text("User-Agent", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            minLines = 2,
            maxLines = 4,
        )
        Row {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && text != current) { Text("Save") }
            TextButton(onClick = { onSave(AppSettings.DEFAULT_USER_AGENT) }, enabled = current != AppSettings.DEFAULT_USER_AGENT) {
                Text("Reset to Chrome default")
            }
        }
    }
}
