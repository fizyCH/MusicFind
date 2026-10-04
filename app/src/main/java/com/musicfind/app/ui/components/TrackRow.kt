package com.musicfind.app.ui.components

import androidx.compose.foundation.background
import com.musicfind.app.R
import com.musicfind.app.util.Loc
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.musicfind.app.data.model.Track
import com.musicfind.app.data.remote.ApiClient
import com.musicfind.app.ui.theme.LocalMfColors
import com.musicfind.app.util.Formatters

private val RowHeight = 88.dp
private val ActionSize = 40.dp
private val CoverSize = 52

@Composable
fun TrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    isFavorite: Boolean = false,
    busy: Boolean = false,
    isDownloading: Boolean = false,
    isPreparing: Boolean = false,
    progressPercent: Int? = null,
    showBitrate: Boolean = false,
    onPlay: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onEditTrack: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onCancelDownload: (() -> Unit)? = null,
    onDeleteFromDevice: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = LocalMfColors.current
    var menuOpen by remember { mutableStateOf(false) }
    // Download and "delete from device" are mutually exclusive: a downloaded track
    // must show only the delete action.
    val canDownload = onDownload != null && onDeleteFromDevice == null && !busy
    val hasMenu = onAddToPlaylist != null || canDownload || onEditTrack != null

    GlassRow(
        modifier = modifier.height(RowHeight),
        onClick = onPlay,
    ) {
        TrackCover(track, size = CoverSize)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = if (isActive) colors.accent else colors.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                track.artist.ifBlank { "Unknown" },
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            val extra = buildList {
                if (showBitrate) track.bitrateKbps?.let { add("$it kbps") }
                Formatters.sourceLabel(track.src).takeIf { it.isNotBlank() }?.let { add(it) }
                subtitle?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ")
            if (extra.isNotBlank()) {
                Text(
                    extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val durationSeconds = track.durationValue
            if (durationSeconds != null && durationSeconds > 0) {
                Text(
                    Formatters.formatTime(durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        }

        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(ActionSize)) {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = Loc.s(R.string.add_to_favorites),
                    tint = if (isFavorite) colors.accent else colors.muted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(ActionSize)) {
                Icon(
                    Icons.Filled.PlaylistRemove,
                    contentDescription = Loc.s(R.string.remove_from_playlist),
                    tint = colors.muted,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (onDeleteFromDevice != null) {
            IconButton(onClick = onDeleteFromDevice, modifier = Modifier.size(ActionSize)) {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = Loc.s(R.string.delete_from_device),
                    tint = colors.error,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (isDownloading) {
            Box(Modifier.size(ActionSize), contentAlignment = Alignment.Center) {
                ProgressRing(progressPercent, colors.accent)
            }
            if (onCancelDownload != null) {
                IconButton(onClick = onCancelDownload, modifier = Modifier.size(ActionSize)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = Loc.s(R.string.cancel_download),
                        tint = colors.error,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else if (isPreparing) {
            Box(Modifier.size(ActionSize), contentAlignment = Alignment.Center) {
                ProgressRing(progressPercent, colors.accent)
            }
        } else {
            IconButton(onClick = onPlay, modifier = Modifier.size(ActionSize)) {
                Icon(
                    if (isActive && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = Loc.s(R.string.play),
                    tint = if (isActive) colors.accent else colors.text,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        if (hasMenu) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(ActionSize)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = Loc.s(R.string.more),
                        tint = colors.muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text(Loc.s(R.string.add_to_playlist)) },
                            leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onAddToPlaylist()
                            },
                        )
                    }
                    if (onEditTrack != null) {
                        DropdownMenuItem(
                            text = { Text(Loc.s(R.string.edit_artist_title)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEditTrack()
                            },
                        )
                    }
                    if (canDownload) {
                        DropdownMenuItem(
                            text = { Text(Loc.s(R.string.download)) },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDownload?.invoke()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(percent: Int?, color: androidx.compose.ui.graphics.Color) {
    if (percent != null && percent in 0..100) {
        CircularProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = color,
        )
    } else {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = color,
        )
    }
}

@Composable
fun TrackCover(track: Track, size: Int = CoverSize) {
    val colors = LocalMfColors.current
    val cover = ApiClient.absoluteUrl(track.coverOrEmpty)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3.5f).dp))
            .background(colors.glassStrong),
        contentAlignment = Alignment.Center,
    ) {
        if (cover.isNotBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                track.displayTitle.take(1).uppercase(),
                color = colors.text,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
