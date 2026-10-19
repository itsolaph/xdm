package com.olaph.xdm.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olaph.xdm.core.Formatters
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.media.MediaFiles
import com.olaph.xdm.vault.PinAttemptPolicy
import com.olaph.xdm.vault.PinHasher
import com.olaph.xdm.vault.UnlockResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** No screenshots, screen recording or recents thumbnail while anything of the private folder is on screen. */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private object Biometrics {
    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** The PIN always works as well; cancelling just falls back to it. */
    fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED
                    if (!cancelled) onError(errString.toString())
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { onError(it.message ?: "Biometric unlock is not available") }
    }
}

@Composable
fun PrivateScreen(onBack: () -> Unit) {
    val vm: PrivateViewModel = viewModel()
    SecureWindow()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    when {
        pinSet == null -> Unit
        pinSet == false -> SetupPin(vm, snackbar, onBack)
        !unlocked -> LockScreen(vm, snackbar, onBack)
        else -> VaultContent(vm, snackbar, onBack)
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it in '0'..'9' }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
}

@Composable
private fun SetupPin(vm: PrivateViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val valid = PinHasher.isValidPin(pin) && pin == confirm
    Scaffold(topBar = { SimpleTopBar("Private folder", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Set a PIN", style = MaterialTheme.typography.titleLarge)
            Text(
                "Files in the private folder are kept inside XDM's own storage: they don't show up in the gallery, " +
                    "in file managers or in other apps, and they're not included in backups. Opening the folder needs this PIN.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "There is no PIN recovery. If you forget it, the only way back in is \"Reset private folder\", which deletes " +
                    "everything in it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            PinField(pin, { pin = it }, "PIN (4 to 8 digits)")
            PinField(confirm, { confirm = it }, "Repeat the PIN", isError = confirm.isNotEmpty() && confirm != pin)
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        if (!vm.setupPin(pin)) snackbar.showSnackbar("Could not set the PIN")
                        busy = false
                    }
                },
                enabled = valid && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set PIN") }
        }
    }
}

@Composable
private fun LockScreen(vm: PrivateViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val biometricEnabled by vm.biometricEnabled.collectAsStateWithLifecycle()
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val remaining = PinAttemptPolicy.remaining(attempts, now)
    LaunchedEffect(attempts) {
        while (true) {
            now = System.currentTimeMillis()
            if (PinAttemptPolicy.remaining(attempts, now) <= 0) break
            delay(500)
        }
    }
    val activity = context.findActivity() as? FragmentActivity
    val canUseBiometric = biometricEnabled && activity != null && Biometrics.available(context)
    fun biometric() {
        if (activity != null) Biometrics.prompt(activity, "Unlock private folder", vm::unlockWithBiometric) { hint = it }
    }
    // Offer the fingerprint/face prompt straight away once per visit.
    LaunchedEffect(canUseBiometric) { if (canUseBiometric) biometric() }

    fun submit() {
        if (busy || remaining > 0 || pin.isEmpty()) return
        busy = true
        scope.launch {
            hint = when (val r = vm.unlock(pin)) {
                UnlockResult.Ok, UnlockResult.NoPin -> null
                is UnlockResult.CoolingDown -> null
                is UnlockResult.Wrong -> when {
                    r.cooldownMs > 0 -> "Wrong PIN"
                    r.attemptsLeft in 1..2 -> "Wrong PIN · ${r.attemptsLeft} more before a pause"
                    else -> "Wrong PIN"
                }
            }
            pin = ""
            busy = false
        }
    }

    Scaffold(topBar = { SimpleTopBar("Private folder", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Enter your PIN", style = MaterialTheme.typography.titleLarge)
            PinField(pin, { pin = it; hint = null }, "PIN", isError = hint != null)
            when {
                remaining > 0 -> Text(
                    "Too many wrong PINs. Try again in ${(remaining + 999) / 1000} s.",
                    color = MaterialTheme.colorScheme.error,
                )
                hint != null -> Text(hint.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = ::submit, enabled = !busy && remaining <= 0 && pin.length >= 4, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Checking…" else "Unlock")
            }
            if (canUseBiometric) {
                OutlinedButton(onClick = ::biometric, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Use fingerprint or face")
                }
            }
            TextButton(onClick = { resetting = true }) { Text("Forgot PIN?") }
        }
    }
    if (resetting) ResetDialog(onDismiss = { resetting = false }) { resetting = false; vm.reset() }
}

/** The only way past a forgotten PIN: delete everything. Needs the word typed out. */
@Composable
private fun ResetDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset private folder?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This deletes every file in the private folder, any private download still in progress, and the PIN.")
                Text("There is no way to get the files back.", color = MaterialTheme.colorScheme.error)
                Text("Type $RESET_WORD to confirm.")
                OutlinedTextField(value = typed, onValueChange = { typed = it.take(20) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = typed.trim() == RESET_WORD) {
                Text("Delete everything", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val RESET_WORD = "RESET"

private sealed interface PendingAction {
    data class Share(val d: DownloadEntity) : PendingAction
    data class OpenElsewhere(val d: DownloadEntity) : PendingAction
    data class MoveOut(val d: DownloadEntity) : PendingAction
    data class Rename(val d: DownloadEntity) : PendingAction
    data class Delete(val items: List<DownloadEntity>) : PendingAction
    data object ChangePin : PendingAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultContent(vm: PrivateViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val biometricEnabled by vm.biometricEnabled.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var overflow by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val active = items.filter { it.entity.status != DownloadStatus.COMPLETED }
    val finished = items.filter { it.entity.status == DownloadStatus.COMPLETED }
    val activity = context.findActivity() as? FragmentActivity

    BackHandler(enabled = selecting) { vm.clearSelection() }

    fun open(d: DownloadEntity) {
        pending = PendingAction.OpenElsewhere(d)
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { vm.selectAll(items.map { it.entity.id }) }) { Icon(Icons.Filled.SelectAll, "Select all") }
                        IconButton(onClick = { pending = PendingAction.Delete(items.map { it.entity }.filter { it.id in selected }) }) {
                            Icon(Icons.Filled.Delete, "Delete")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Private folder") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        IconButton(onClick = { vm.lockNow(); onBack() }) { Icon(Icons.Filled.Lock, "Lock now") }
                        Box {
                            IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(
                                    text = { Text("Change PIN") },
                                    leadingIcon = { Icon(Icons.Filled.Password, null) },
                                    onClick = { overflow = false; pending = PendingAction.ChangePin },
                                )
                                if (activity != null && Biometrics.available(context)) {
                                    DropdownMenuItem(
                                        text = { Text("Unlock with fingerprint or face") },
                                        leadingIcon = { Icon(Icons.Filled.Fingerprint, null) },
                                        trailingIcon = { Switch(checked = biometricEnabled, onCheckedChange = null) },
                                        onClick = {
                                            overflow = false
                                            if (biometricEnabled) {
                                                vm.setBiometricEnabled(false)
                                            } else {
                                                // Only switched on after it worked once.
                                                Biometrics.prompt(activity, "Use fingerprint or face", { vm.setBiometricEnabled(true) }) {}
                                            }
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete all (${items.size})") },
                                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) },
                                    enabled = items.isNotEmpty(),
                                    onClick = { overflow = false; pending = PendingAction.Delete(items.map { it.entity }) },
                                )
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing here yet.\nTick \"Save to private folder\" when adding a download, or use \"Move to private folder\" " +
                        "on a finished one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active.isNotEmpty()) {
                item { SectionLabel("In progress") }
                items(active, key = { it.entity.id }) { item ->
                    ActiveRow(item, vm, selecting, item.entity.id in selected, onDelete = { pending = PendingAction.Delete(listOf(item.entity)) })
                }
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            }
            items(finished, key = { it.entity.id }) { item ->
                val d = item.entity
                FileRow(
                    d = d,
                    selecting = selecting,
                    checked = d.id in selected,
                    onClick = { if (selecting) vm.toggleSelected(d.id) else open(d) },
                    onLongClick = { if (!selecting) vm.toggleSelected(d.id) },
                    onAction = { pending = it },
                    onOpen = { open(d) },
                )
            }
        }
    }

    when (val p = pending) {
        null -> Unit
        is PendingAction.Share -> LeaveWarning(
            title = "Share outside the private folder?",
            text = "The app you share with gets its own copy. It can keep it, and that copy is no longer protected by XDM.",
            confirm = "Share",
            onDismiss = { pending = null },
        ) { pending = null; FileActions.share(context, vm.container, p.d) }
        is PendingAction.OpenElsewhere -> LeaveWarning(
            title = "Open in another app?",
            text = "The other app can read this file and may keep a copy or a thumbnail.",
            confirm = "Open",
            onDismiss = { pending = null },
        ) { pending = null; FileActions.openWith(context, vm.container, p.d) }
        is PendingAction.MoveOut -> LeaveWarning(
            title = "Move out of the private folder?",
            text = "The file goes back to Download/XDM, where the gallery and other apps can see it.",
            confirm = "Move out",
            onDismiss = { pending = null },
        ) { pending = null; vm.moveOut(p.d) }
        is PendingAction.Rename -> RenameDialog(p.d.fileName, onDismiss = { pending = null }) { name -> pending = null; vm.rename(p.d, name) }
        is PendingAction.Delete -> AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (p.items.size == 1) "Delete this file?" else "Delete ${p.items.size} items?") },
            text = {
                Text(
                    "Deleted from the private folder for good" +
                        p.items.sumOf { it.totalBytes.coerceAtLeast(0) }.takeIf { it > 0 }?.let { " (${Formatters.bytes(it)})" }.orEmpty() +
                        ". Downloads in progress are cancelled.",
                )
            },
            confirmButton = {
                TextButton(onClick = { pending = null; vm.delete(p.items.map { it.id }) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Keep") } },
        )
        PendingAction.ChangePin -> ChangePinDialog(vm, onDone = { message ->
            pending = null
            message?.let(vm::say)
        })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActiveRow(item: DownloadItem, vm: PrivateViewModel, selecting: Boolean, checked: Boolean, onDelete: () -> Unit) {
    val context = LocalContext.current
    val d = item.entity
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selecting) vm.toggleSelected(d.id) },
            onLongClick = { if (!selecting) vm.toggleSelected(d.id) },
        ),
        colors = if (checked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = checked, onCheckedChange = { vm.toggleSelected(d.id) })
            Column(Modifier.weight(1f)) {
                Text(d.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val fraction = item.fraction
                when {
                    fraction != null -> LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    d.status == DownloadStatus.RUNNING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                Text(sizeLine(item), style = MaterialTheme.typography.bodySmall)
                Text(
                    statusLine(item, QueueStatus(null, null)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selecting) {
                when (d.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> IconButton(onClick = { vm.pause(d.id) }) { Icon(Icons.Filled.Pause, "Pause") }
                    DownloadStatus.PAUSED -> IconButton(onClick = { vm.resume(d.id) }) { Icon(Icons.Filled.PlayArrow, "Resume") }
                    else -> IconButton(onClick = { vm.resume(d.id) }) { Icon(Icons.Filled.Refresh, "Retry") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (d.status != DownloadStatus.FAILED && d.status != DownloadStatus.CANCELLED) {
                            DropdownMenuItem(
                                text = { Text("Cancel") },
                                leadingIcon = { Icon(Icons.Filled.Close, null) },
                                onClick = { menu = false; vm.cancel(d.id) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    d: DownloadEntity,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (PendingAction) -> Unit,
    onOpen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val duration = if (MediaFiles.isMedia(d)) mediaDuration(d) else -1
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (checked) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = checked, onCheckedChange = { onClick() })
            MediaThumbnail(d, Modifier.size(width = 96.dp, height = 54.dp)) {
                Box(Modifier.size(width = 96.dp, height = 54.dp), contentAlignment = Alignment.Center) { FileTypeIcon(d.fileName, d.mimeType) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(Formatters.bytes(d.totalBytes), duration.takeIf { it > 0 }?.let { MediaFiles.formatTime(it) })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!selecting) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open with…") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                            onClick = { menu = false; onOpen() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move out") },
                            leadingIcon = { Icon(Icons.Filled.LockOpen, null) },
                            onClick = { menu = false; onAction(PendingAction.MoveOut(d)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; onAction(PendingAction.Rename(d)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { menu = false; onAction(PendingAction.Share(d)) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onAction(PendingAction.Delete(listOf(d))) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaveWarning(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank() && name.trim() != current) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChangePinDialog(vm: PrivateViewModel, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Change PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PinField(old, { old = it; error = null }, "Current PIN", isError = error != null)
                PinField(new, { new = it }, "New PIN (4 to 8 digits)")
                PinField(repeat, { repeat = it }, "Repeat the new PIN", isError = repeat.isNotEmpty() && repeat != new)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && old.length >= 4 && PinHasher.isValidPin(new) && new == repeat,
                onClick = {
                    busy = true
                    scope.launch {
                        when (val r = vm.changePin(old, new)) {
                            UnlockResult.Ok -> onDone("PIN changed")
                            is UnlockResult.CoolingDown -> error = "Too many wrong PINs. Try again in ${(r.remainingMs + 999) / 1000} s."
                            is UnlockResult.Wrong -> error = "The current PIN is wrong"
                            UnlockResult.NoPin -> onDone(null)
                        }
                        busy = false
                    }
                },
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
