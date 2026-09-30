package app.saveit.ui.components

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.saveit.core.error.ErrorKind
import app.saveit.core.error.ErrorMessages
import app.saveit.core.error.SaveItException
import app.saveit.core.model.MediaType
import app.saveit.core.model.Platform
import coil3.compose.AsyncImage

/** Generic, original badge per platform: a colored shape with a neutral glyph (not the platforms' logos). */
data class PlatformStyle(val background: Brush, val glyph: ImageVector, val glyphColor: Color)

fun Platform.style(): PlatformStyle = when (this) {
    Platform.REDDIT -> PlatformStyle(Brush.linearGradient(listOf(Color(0xFFFF5A1F), Color(0xFFFF8A3D))), Icons.Filled.Forum, Color.White)
    Platform.X -> PlatformStyle(Brush.linearGradient(listOf(Color(0xFF1F1F23), Color(0xFF3A3A42))), Icons.Filled.Tag, Color.White)
    Platform.INSTAGRAM -> PlatformStyle(Brush.linearGradient(listOf(Color(0xFF8A3FFC), Color(0xFFE8406F), Color(0xFFFFA24C))), Icons.Filled.PhotoCamera, Color.White)
    Platform.TIKTOK -> PlatformStyle(Brush.linearGradient(listOf(Color(0xFF101014), Color(0xFF26262E))), Icons.Filled.MusicNote, Color(0xFF3FE0E6))
    Platform.SNAPCHAT -> PlatformStyle(Brush.linearGradient(listOf(Color(0xFFFFE94A), Color(0xFFFFD21F))), Icons.Filled.Bolt, Color(0xFF1C1C1C))
    Platform.FACEBOOK -> PlatformStyle(Brush.linearGradient(listOf(Color(0xFF2F6BFF), Color(0xFF4F8BFF))), Icons.Filled.Groups, Color.White)
    Platform.PINTEREST -> PlatformStyle(Brush.linearGradient(listOf(Color(0xFFD5173A), Color(0xFFF03C57))), Icons.Filled.PushPin, Color.White)
    Platform.DIRECT -> PlatformStyle(Brush.linearGradient(listOf(Color(0xFF6B6F7B), Color(0xFF8A8E99))), Icons.Filled.Link, Color.White)
}

@Composable
fun PlatformBadge(platform: Platform, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val s = platform.style()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(s.background)
            .semantics { contentDescription = platform.displayName },
        contentAlignment = Alignment.Center,
    ) {
        Icon(s.glyph, contentDescription = null, tint = s.glyphColor, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
fun PlatformChip(platform: Platform, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformBadge(platform, 20.dp)
        Spacer(Modifier.size(6.dp))
        Text(platform.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

fun MediaType.icon(): ImageVector = when (this) {
    MediaType.VIDEO -> Icons.Filled.PlayArrow
    MediaType.IMAGE -> Icons.Filled.Image
    MediaType.GIF -> Icons.Filled.Gif
    MediaType.AUDIO -> Icons.Filled.AudioFile
}

/** Thumbnail from a remote URL or a local content:// URI, with a type glyph as placeholder. */
@Composable
fun MediaThumb(model: Any?, type: MediaType, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        if (model != null && (model !is String || model.isNotBlank())) {
            AsyncImage(
                model = if (model is String && model.startsWith("content://")) Uri.parse(model) else model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        if (type == MediaType.VIDEO || type == MediaType.GIF) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(2.dp),
            ) {
                Icon(type.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Error card with the specific message and the one-tap action that can fix it. */
@Composable
fun ErrorCard(
    error: SaveItException,
    onUpdateEngine: () -> Unit,
    onLogin: (Platform) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(8.dp))
                Text(ErrorMessages.title(error.kind), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(6.dp))
            Text(error.userMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                val platform = error.platform
                when {
                    error.kind == ErrorKind.ENGINE_OUTDATED -> TextButton(onClick = onUpdateEngine) { Text("Update engine") }
                    error.kind in setOf(ErrorKind.LOGIN_REQUIRED, ErrorKind.PRIVATE, ErrorKind.AGE_RESTRICTED, ErrorKind.RATE_LIMITED) &&
                        platform != null && platform.loginSupported -> TextButton(onClick = { onLogin(platform) }) { Text("Log in to ${platform.displayName}") }
                    error.kind.retryable -> TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
fun FadeIn(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible) { content() }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatDuration(sec: Double?): String? {
    if (sec == null || sec <= 0) return null
    val s = sec.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
