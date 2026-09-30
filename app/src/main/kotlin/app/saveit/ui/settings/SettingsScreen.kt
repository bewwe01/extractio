package app.saveit.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.saveit.core.download.FilenameTemplate
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import app.saveit.core.model.PostInfo
import app.saveit.core.model.Quality
import app.saveit.data.ThemeMode
import app.saveit.ui.components.PlatformBadge
import app.saveit.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onLogin: (Platform) -> Unit, onAbout: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("Downloads")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Default video quality", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quality.entries.forEach { q ->
                        FilterChip(selected = settings.defaultQuality == q, onClick = { vm.setQuality(q) }, label = { Text(q.label) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                TemplateEditor(settings.filenameTemplate, vm::setTemplate)
            }
            SwitchRow(
                title = "Detect links in clipboard",
                subtitle = "Offer to download a copied link when you open SaveIt",
                checked = settings.clipboardDetection,
                onChange = vm::setClipboard,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Appearance")
            Column(Modifier.padding(horizontal = 20.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { t ->
                        FilterChip(selected = settings.theme == t, onClick = { vm.setTheme(t) }, label = { Text(t.label) })
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("Dynamic color", "Match your wallpaper colors", settings.dynamicColor, vm::setDynamic)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Accounts (optional)")
            Text(
                "Public posts work without logging in. Log in only for content a platform hides from visitors " +
                    "(Instagram stories, some Facebook videos, restricted posts). Sessions are stored encrypted on this device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Platform.entries.filter { it.loginSupported }.forEach { p ->
                val isIn = p in loggedIn
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformBadge(p, 32.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(if (isIn) "Logged in" else "Not logged in", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isIn) OutlinedButton(onClick = { vm.logout(p) }) { Text("Log out") }
                    else TextButton(onClick = { onLogin(p) }) { Text("Log in") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Extractor engine")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("yt-dlp ${engine.version ?: "…"}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Platforms change often. If downloads from a platform stop working, update the engine first.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = vm::updateEngine, enabled = !engine.updating, shape = RoundedCornerShape(16.dp)) {
                    if (engine.updating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (engine.updating) "Updating…" else "Update extractor engine")
                }
                engine.message?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader("Data")
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { confirmClear = true }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Clear download history", style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onAbout).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("About SaveIt", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Saved files stay in your gallery.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditor(template: String, onChange: (String) -> Unit) {
    var text by remember(template) { mutableStateOf(template) }
    val sample = remember {
        PostInfo(Platform.INSTAGRAM, "", "C8abcDEF", "Sunset over the bay", "@traveler",
            null, List(3) { MediaItem("x", MediaType.IMAGE, url = "", ext = "jpg") }, "sample")
    }
    Text("File name", style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Preview: ${FilenameTemplate.render(text, sample, sample.items[0], 0, "Best")}.jpg") },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilenameTemplate.TOKENS.forEach { token ->
            AssistChip(onClick = { text += token; onChange(text) }, label = { Text(token) })
        }
        AssistChip(onClick = { text = FilenameTemplate.DEFAULT; onChange(text) }, label = { Text("Reset") })
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
