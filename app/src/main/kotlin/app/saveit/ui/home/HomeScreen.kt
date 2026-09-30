package app.saveit.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.saveit.core.error.ErrorKind
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.url.PlatformDetector
import app.saveit.data.DownloadEntity
import app.saveit.download.DownloadTask
import app.saveit.download.TaskState
import app.saveit.ui.components.EmptyState
import app.saveit.ui.components.ErrorCard
import app.saveit.ui.components.MediaThumb
import app.saveit.ui.components.PlatformBadge
import app.saveit.ui.components.formatBytes
import androidx.compose.foundation.layout.Box

@Composable
fun HomeScreen(vm: HomeViewModel, onOpenHistory: () -> Unit, onLogin: (Platform) -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Clipboard detection: Android 10+ only lets the focused app read the clipboard.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, settings.clipboardDetection) {
        if (focused && settings.clipboardDetection) vm.onClipboard(readClipboard(context))
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ensurePermissions: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            false
        } else true
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header() }
            item {
                AnimatedVisibility(visible = state.clipboardSuggestion != null) {
                    state.clipboardSuggestion?.let { ClipboardCard(it, onUse = vm::useSuggestion, onDismiss = vm::dismissSuggestion) }
                }
            }
            item {
                InputCard(
                    text = state.input,
                    analyzing = state.analyzing,
                    onText = vm::onInput,
                    onPaste = { vm.paste(readClipboard(context)) },
                    onClear = { vm.onInput("") },
                    onSubmit = vm::analyze,
                )
            }
            state.error?.let { err ->
                item {
                    ErrorCard(
                        error = err,
                        onUpdateEngine = vm::updateEngine,
                        onLogin = onLogin,
                        onRetry = vm::analyze,
                        onDismiss = vm::dismissError,
                    )
                }
            }
            if (state.engineUpdating) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Updating extractor engine…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            val visibleTasks = tasks.filter { it.state != TaskState.CANCELLED }.takeLast(20).reversed()
            if (visibleTasks.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (visibleTasks.any { !it.isActive }) TextButton(onClick = vm::clearFinished) { Text("Clear finished") }
                    }
                }
                items(visibleTasks, key = { it.id }) { task ->
                    TaskRow(task, onCancel = { vm.cancelTask(task.id) }, onRetry = { vm.retryTask(task.id) },
                        onDismiss = { vm.dismissTask(task.id) }, onOpen = { task.resultUri?.let { openUri(context, it, null) } }, onLogin = onLogin)
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenHistory) { Text("See all") }
                    }
                }
                items(recent, key = { "r${it.id}" }) { d -> RecentRow(d) { openUri(context, d.contentUri, d.mimeType) } }
            }
            if (visibleTasks.isEmpty() && recent.isEmpty() && state.error == null) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.CloudDownload,
                        title = "Nothing downloaded yet",
                        body = "Copy a post link, or use Share → SaveIt in any app. Videos, photos, GIFs and whole galleries are saved to your gallery.",
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    state.preview?.let { post ->
        PreviewSheet(
            post = post,
            defaultQuality = settings.defaultQuality,
            onDismiss = vm::dismissPreview,
            onDownload = { indexes, quality -> if (ensurePermissions()) vm.download(indexes, quality) },
        )
    }
}

@Composable
private fun Header() {
    Column {
        Text("SaveIt", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Save videos and photos from public posts.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Platform.entries.filter { it != Platform.DIRECT }.forEach { PlatformBadge(it, 24.dp) }
        }
    }
}

@Composable
private fun ClipboardCard(url: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    val platform = PlatformDetector.detect(url) ?: Platform.DIRECT
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PlatformBadge(platform, 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Link found in clipboard", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            TextButton(onClick = onUse) { Text("Use") }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss clipboard suggestion") }
        }
    }
}

@Composable
private fun InputCard(text: String, analyzing: Boolean, onText: (String) -> Unit, onPaste: () -> Unit, onClear: () -> Unit, onSubmit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text("Paste a post link") },
            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "Clear link") }
            },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onPaste, modifier = Modifier.height(56.dp), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Paste")
            }
            Button(
                onClick = onSubmit,
                enabled = !analyzing,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                if (analyzing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Analyzing…")
                } else {
                    Text("Download")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: DownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onLogin: (Platform) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(enabled = task.state == TaskState.DONE, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumb(task.item.thumbnailUrl ?: task.post.thumbnailUrl, task.item.type, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.post.title ?: task.post.platform.displayName,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val detail = when (task.state) {
                    TaskState.RUNNING -> listOfNotNull(
                        task.stage,
                        task.progress?.let { "${(it * 100).toInt()}%" },
                        task.speedBytesPerSec.takeIf { it > 0 }?.let { formatBytes(it) + "/s" },
                    ).joinToString(" · ")
                    TaskState.FAILED -> task.error?.userMessage ?: "Failed"
                    else -> task.stage
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (task.state == TaskState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (task.state == TaskState.RUNNING || task.state == TaskState.QUEUED) {
                    Spacer(Modifier.height(8.dp))
                    val p = task.progress
                    if (p != null) {
                        val animated by animateFloatAsState(p, label = "progress")
                        LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Download progress ${(p * 100).toInt()} percent"
                        })
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            when (task.state) {
                TaskState.RUNNING, TaskState.QUEUED ->
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel download") }
                TaskState.FAILED -> {
                    val p = task.error?.platform
                    val kind = task.error?.kind
                    val needsLogin = kind == ErrorKind.LOGIN_REQUIRED || kind == ErrorKind.PRIVATE || kind == ErrorKind.AGE_RESTRICTED
                    if (needsLogin && p != null && p.loginSupported) {
                        TextButton(onClick = { onLogin(p) }) { Text("Log in") }
                    } else {
                        IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Retry download") }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
                }
                else -> IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
            }
        }
    }
}

@Composable
private fun RecentRow(d: DownloadEntity, onOpen: () -> Unit) {
    val type = runCatching { MediaType.valueOf(d.mediaType) }.getOrDefault(MediaType.IMAGE)
    val platform = runCatching { Platform.valueOf(d.platform) }.getOrDefault(Platform.DIRECT)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumb(d.contentUri, type, Modifier.size(52.dp), contentDescription = d.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.title ?: d.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${platform.displayName} · ${formatBytes(d.sizeBytes)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlatformBadge(platform, 22.dp)
    }
}

internal fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

internal fun openUri(context: Context, uri: String, mime: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(uri), mime ?: context.contentResolver.getType(Uri.parse(uri)))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
