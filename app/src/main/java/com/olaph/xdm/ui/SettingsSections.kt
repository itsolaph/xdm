package com.olaph.xdm.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olaph.xdm.core.BackgroundHelp
import com.olaph.xdm.core.BatteryAdvice
import com.olaph.xdm.core.Formatters
import com.olaph.xdm.core.RunGate
import com.olaph.xdm.core.SpeedLimitInput
import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.engine.FileCategory
import com.olaph.xdm.service.ScheduleAlarm

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The system folder picker, asked for access that survives a restart. */
private class PickFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent = super.createIntent(context, input).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
    )
}

/** "Save location": Download/XDM or a folder of the user's choice (SD card, any storage provider), optionally sorted by type. */
@Composable
fun SaveLocationSettings(vm: MainViewModel, settings: AppSettings) {
    val folder by vm.saveFolder.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(PickFolder()) { uri -> if (uri != null) vm.chooseSaveFolder(uri) }
    LaunchedEffect(Unit) { vm.refreshSaveFolder() }

    Text("Save location", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(folder?.label ?: "Download/XDM", style = MaterialTheme.typography.titleSmall)
        InfoText(
            when {
                folder == null -> "The public Download/XDM folder, visible to the gallery and other apps"
                folder?.available == false ->
                    "Not available right now (folder deleted, card removed or access revoked). Finished files go to Download/XDM until you choose another folder."
                else -> "Folder you chose. Finished files are saved here."
            },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { picker.launch(null) }) { Text("Choose folder…") }
        TextButton(onClick = vm::resetSaveFolder, enabled = settings.saveTreeUri != null) { Text("Reset to Download/XDM") }
    }
    SwitchRow(
        "Sort into subfolders by type",
        FileCategory.entries.joinToString(", ") { it.folder },
        settings.sortByType,
        vm::setSortByType,
    )
}

/** "Schedule": a daily window, optionally only on the charger. */
@Composable
fun ScheduleSettings(vm: MainViewModel, settings: AppSettings) {
    val context = LocalContext.current
    var exactAllowed by remember { mutableStateOf(ScheduleAlarm.canScheduleExact(context)) }
    LifecycleResumeEffect(Unit) {
        exactAllowed = ScheduleAlarm.canScheduleExact(context)
        onPauseOrDispose {}
    }

    Text("Schedule", style = MaterialTheme.typography.titleMedium)
    SwitchRow(
        "Download only in a daily window",
        "Outside it, queued downloads wait (shown as Scheduled) and running ones go back to the queue. \"Start now\" on a download ignores the schedule.",
        settings.scheduleEnabled,
        vm::setScheduleEnabled,
    )
    if (!settings.scheduleEnabled) return
    TimeRow("From", settings.scheduleStartMinute) { vm.setScheduleWindow(it, settings.scheduleEndMinute) }
    TimeRow("Until", settings.scheduleEndMinute) { vm.setScheduleWindow(settings.scheduleStartMinute, it) }
    if (settings.scheduleStartMinute == settings.scheduleEndMinute) InfoText("From and until are the same time, so the window is always open.")
    SwitchRow("Only while charging", "Downloads wait for the charger, also inside the window", settings.scheduleOnlyCharging, vm::setScheduleOnlyCharging)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.scheduleStartMinute != settings.scheduleEndMinute) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Start on time", style = MaterialTheme.typography.titleSmall)
                InfoText(
                    if (exactAllowed) "Android lets XDM wake up exactly when the window opens"
                    else "Without the \"Alarms & reminders\" access Android may refuse to start the downloads in the background; XDM then posts a \"Scheduled downloads are ready\" notification to tap.",
                )
            }
            if (!exactAllowed) TextButton(onClick = { if (!BackgroundHelp.openExactAlarmSettings(context)) cantOpen(context) }) { Text("Allow") }
        }
    }
}

@Composable
private fun TimeRow(label: String, minuteOfDay: Int, onPicked: (Int) -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable {
            TimePickerDialog(
                context, { _, h, m -> onPicked(h * 60 + m) }, minuteOfDay / 60, minuteOfDay % 60, DateFormat.is24HourFormat(context),
            ).show()
        }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(RunGate.clock(minuteOfDay), style = MaterialTheme.typography.titleMedium)
    }
}

/** "Speed limit": one cap for everything XDM downloads, Off by default. */
@Composable
fun SpeedLimitSettings(vm: MainViewModel, settings: AppSettings) {
    val limit = settings.speedLimitBytesPerSec
    val (shownText, shownMegabytes) = if (limit > 0) SpeedLimitInput.display(limit) else ("" to true)
    var text by remember(limit) { mutableStateOf(shownText) }
    var megabytes by remember(limit) { mutableStateOf(shownMegabytes) }
    val parsed = SpeedLimitInput.parse(text, megabytes)

    Text("Speed limit", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.padding(top = 8.dp)) {
        Text(if (limit > 0) "Limited to ${Formatters.speed(limit)}" else "Off", style = MaterialTheme.typography.titleSmall)
        InfoText("Applies to all downloads together and takes effect at once, also for downloads that are running.")
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Limit") },
            singleLine = true,
            isError = text.isNotBlank() && parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        FilterChip(selected = !megabytes, onClick = { megabytes = false }, label = { Text("KB/s") })
        FilterChip(selected = megabytes, onClick = { megabytes = true }, label = { Text("MB/s") })
    }
    Row {
        TextButton(onClick = { parsed?.let(vm::setSpeedLimit) }, enabled = parsed != null && parsed != limit) { Text("Set limit") }
        TextButton(onClick = { vm.setSpeedLimit(0) }, enabled = limit > 0) { Text("Off") }
    }
}

/** "Background": what keeps downloads alive when the phone wants to save battery. */
@Composable
fun BackgroundSettings() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BackgroundHelp.isExempt(context)) }
    LifecycleResumeEffect(Unit) {
        exempt = BackgroundHelp.isExempt(context)
        onPauseOrDispose {}
    }
    val xiaomi = remember { BatteryAdvice.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND) }

    Text("Background", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.fillMaxWidth().clickable {
        if (BackgroundHelp.openBatterySettings(context) == BackgroundHelp.Opened.NOTHING) cantOpen(context)
    }.padding(vertical = 8.dp)) {
        Text("Battery optimisation", style = MaterialTheme.typography.titleSmall)
        InfoText(
            if (exempt) "XDM is not restricted. Tap to see the list."
            else "XDM may be stopped in the background to save battery. Tap to allow it to keep downloading.",
        )
    }
    Column(Modifier.fillMaxWidth().clickable {
        if (BackgroundHelp.openBackgroundTips(context) == BackgroundHelp.Opened.NOTHING) cantOpen(context)
    }.padding(vertical = 8.dp)) {
        Text("Background tips", style = MaterialTheme.typography.titleSmall)
        InfoText(
            if (xiaomi) "Opens the Autostart list: switch XDM on there, or MIUI / HyperOS may stop it when the screen is off."
            else "Opens XDM's app info, where you can check battery and background settings.",
        )
    }
    InfoText(
        "Android 15 also limits download services that run in the background to about 6 hours in 24. XDM then pauses " +
            "the downloads and offers to continue; opening the app starts the clock over.",
    )
}

private fun cantOpen(context: Context) {
    Toast.makeText(context, "This phone has no screen for that", Toast.LENGTH_SHORT).show()
}
