package app.saveit.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.saveit.BuildConfig
import app.saveit.core.model.Platform
import app.saveit.ui.components.PlatformBadge

private val platformNotes = mapOf(
    Platform.REDDIT to "Videos (with sound), images, galleries, GIFs, Redgifs and Imgur links.",
    Platform.X to "Videos (highest quality), GIFs (saved as MP4), photos in original size, multi-media posts.",
    Platform.INSTAGRAM to "Reels, videos, photos and carousels. Stories and highlights need a login.",
    Platform.TIKTOK to "Videos (watermark-free stream when TikTok provides one) and photo slideshows with optional sound.",
    Platform.SNAPCHAT to "Public Spotlight videos and public stories only. Private snaps and chats can't be downloaded.",
    Platform.FACEBOOK to "Public videos, reels, watch links and photos. Many posts are only visible when logged in.",
    Platform.PINTEREST to "Images in original resolution, video pins, GIFs, idea pins and pin.it links.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("SaveIt ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("No ads, no analytics, no trackers. SaveIt only talks to the site you download from, and to GitHub when you update the extractor engine.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Your responsibility", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "SaveIt is for saving publicly available content for personal use. You are responsible for respecting " +
                            "creators' rights and each platform's terms of service. Don't re-upload or redistribute other people's " +
                            "work without permission. SaveIt does not bypass DRM, paywalls or privacy settings.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Supported platforms", style = MaterialTheme.typography.titleMedium)
            platformNotes.forEach { (p, note) ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    PlatformBadge(p, 28.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Open-source components", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "yt-dlp (Unlicense) · youtubedl-android (GPL-3.0) · FFmpeg (LGPL/GPL) · Python · OkHttp · jsoup · Coil · Jetpack Compose. " +
                    "Platform badges are generic icons, not the platforms' logos; all trademarks belong to their owners.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
