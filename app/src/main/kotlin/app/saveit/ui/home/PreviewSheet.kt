package app.saveit.ui.home

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.saveit.core.model.MediaItem
import app.saveit.core.model.MediaType
import app.saveit.core.model.PostInfo
import app.saveit.core.model.Quality
import app.saveit.ui.components.MediaThumb
import app.saveit.ui.components.PlatformChip
import app.saveit.ui.components.formatDuration

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PreviewSheet(
    post: PostInfo,
    defaultQuality: Quality,
    onDismiss: () -> Unit,
    onDownload: (List<Int>, Quality) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selected = remember(post) {
        mutableStateListOf<Int>().apply { addAll(post.items.indices.filter { post.items[it].selectedByDefault }) }
    }
    var quality by remember(post) { mutableStateOf(defaultQuality) }
    val selectedHasVideo = selected.any { post.items[it].type == MediaType.VIDEO }
    val heights = post.items.filter { it.type == MediaType.VIDEO }.flatMap { it.availableHeights }.toSet()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MediaThumb(post.thumbnailUrl, post.items.first().type, Modifier.size(72.dp), contentDescription = "Post thumbnail")
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(post.title ?: "Untitled post", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    post.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(6.dp))
                    PlatformChip(post.platform)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (post.items.size == 1) "1 item" else "${post.items.size} items",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                if (post.items.size > 1) {
                    val all = selected.size == post.items.size
                    TextButton(onClick = {
                        selected.clear()
                        if (!all) selected.addAll(post.items.indices)
                    }) { Text(if (all) "Select none" else "Select all") }
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                itemsIndexed(post.items) { i, item ->
                    val checked = i in selected
                    ItemRow(item, i, checked) { if (checked) selected.remove(i) else selected.add(i) }
                }
            }
            HorizontalDivider()
            if (selectedHasVideo) {
                Spacer(Modifier.height(12.dp))
                Text("Video quality", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Quality.entries.forEach { q ->
                        val available = q.maxHeight == null || heights.isEmpty() || heights.any { it >= q.maxHeight!! }
                        FilterChip(
                            selected = quality == q,
                            onClick = { quality = q },
                            label = { Text(q.label) },
                            enabled = available || quality == q,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onDownload(selected.sorted(), quality) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    when {
                        selected.isEmpty() -> "Select something to download"
                        selected.size == post.items.size && post.items.size > 1 -> "Download all (${selected.size})"
                        selected.size == 1 -> "Download"
                        else -> "Download ${selected.size} items"
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ItemRow(item: MediaItem, index: Int, checked: Boolean, onToggle: () -> Unit) {
    val typeName = when (item.type) {
        MediaType.VIDEO -> "Video"
        MediaType.IMAGE -> "Photo"
        MediaType.GIF -> "GIF"
        MediaType.AUDIO -> "Audio"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        MediaThumb(item.thumbnailUrl ?: item.url.takeIf { item.type == MediaType.IMAGE }, item.type, Modifier.size(56.dp),
            contentDescription = "$typeName ${index + 1}")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label ?: "$typeName ${index + 1}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                if (item.width != null && item.height != null) "${item.width}×${item.height}" else null,
                formatDuration(item.durationSec),
                if (item.type == MediaType.VIDEO && item.hasAudio == false) "no sound" else null,
                if (!item.selectedByDefault) "optional" else null,
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
