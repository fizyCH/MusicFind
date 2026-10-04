package com.musicfind.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.musicfind.app.data.model.Playlist
import com.musicfind.app.data.model.Track
import com.musicfind.app.data.remote.ApiClient
import com.musicfind.app.player.PlayerController
import com.musicfind.app.ui.AppViewModel
import com.musicfind.app.ui.components.GlassSurface
import com.musicfind.app.ui.components.SectionTitle
import com.musicfind.app.ui.components.TrackRow
import com.musicfind.app.ui.theme.LocalMfColors

@Composable
fun PlaylistsScreen(
    vm: AppViewModel,
    openPlaylist: String?,
    onOpenPlaylist: (String?) -> Unit,
    onOpenPlaylistPicker: (Track) -> Unit,
    onEditCover: (String) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val offlineMode = !ui.online
    if (openPlaylist == null) {
        if (offlineMode) {
            OfflinePlaylistList(ui.offlinePlaylists.map { it.name }, onOpenPlaylist)
        } else {
            PlaylistList(vm, onOpenPlaylist, onEditCover)
        }
    } else {
        if (offlineMode) {
            OfflinePlaylistDetail(vm, openPlaylist, onOpenPlaylist)
        } else {
            PlaylistDetail(vm, openPlaylist, onOpenPlaylist, onOpenPlaylistPicker)
        }
    }
}

@Composable
private fun OfflinePlaylistList(
    names: List<String>,
    onOpenPlaylist: (String?) -> Unit,
) {
    val colors = LocalMfColors.current
    val player by PlayerController.state.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = if (player.current != null) 150.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionTitle(
                title = "Скачанные треки",
                subtitle = "Доступны без интернета",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (names.isEmpty()) {
            item {
                Text("Пока ничего не скачано", color = colors.muted)
            }
        }
        itemsIndexed(names) { _, name ->
            GlassSurface(Modifier.fillMaxWidth(), onClick = { onOpenPlaylist(name) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(colors.glassStrong),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = colors.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        name,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflinePlaylistDetail(
    vm: AppViewModel,
    playlistName: String,
    onOpenPlaylist: (String?) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val player by vm.playerState.collectAsState()
    val colors = LocalMfColors.current
    val playlist = ui.offlinePlaylists.firstOrNull { it.name == playlistName }
    val tracks = playlist?.tracks.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = if (player.current != null) 150.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onOpenPlaylist(null) }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = colors.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        playlistName,
                        color = colors.text,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${tracks.size} треков",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        itemsIndexed(tracks) { index, track ->
            TrackRow(
                track = track,
                isActive = player.current?.let { current ->
                    current.localPath == track.localPath ||
                        AppViewModel.favoriteKey(current) == AppViewModel.favoriteKey(track)
                } ?: false,
                isPlaying = player.isPlaying,
                onPlay = { vm.playTrack(track, null, tracks, index, playlistName) },
                onDeleteFromDevice = { vm.deleteOfflineTrack(track) },
            )
        }
    }
}

@Composable
private fun PlaylistList(
    vm: AppViewModel,
    onOpenPlaylist: (String?) -> Unit,
    onEditCover: (String) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val player by PlayerController.state.collectAsState()
    var createOpen by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = if (player.current != null) 150.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionTitle(
                title = "Плейлисты",
                subtitle = "Твоя коллекция",
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    IconButton(onClick = { createOpen = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Создать",
                            tint = LocalMfColors.current.accent,
                        )
                    }
                },
            )
        }
        itemsIndexed(ui.playlists) { _, playlist ->
            val downloading = ui.downloadingPlaylist == playlist.name
            PlaylistCard(
                playlist = playlist,
                coverOverride = ui.coverOverrides[playlist.name],
                downloading = downloading,
                downloadEnabled = ui.online && ui.downloadingPlaylist == null && playlist.tracks.isNotEmpty(),
                downloadLabel = if (downloading) "${ui.playlistDownloadDone}/${ui.playlistDownloadTotal}" else null,
                onClick = { onOpenPlaylist(playlist.name) },
                onEditCover = { onEditCover(playlist.name) },
                onDownload = { vm.downloadPlaylist(playlist.name) },
                onCancelDownload = { vm.cancelPlaylistDownload() },
                isDownloaded = playlist.name in ui.offlinePlaylistNames,
                onDeleteFromDevice = { vm.deleteOfflinePlaylist(playlist.name) },
                onDelete = if (!playlist.isFavorites) {
                    { vm.removePlaylist(playlist.name) }
                } else {
                    null
                },
            )
        }
    }

    if (createOpen) {
        CreatePlaylistDialog(
            onDismiss = { createOpen = false },
            onCreate = { name ->
                vm.createPlaylist(name)
                createOpen = false
            },
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    coverOverride: String?,
    downloading: Boolean,
    downloadEnabled: Boolean,
    downloadLabel: String?,
    isDownloaded: Boolean,
    onClick: () -> Unit,
    onEditCover: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteFromDevice: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = LocalMfColors.current
    var menuOpen by remember { mutableStateOf(false) }
    GlassSurface(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(colors.glassStrong),
                contentAlignment = Alignment.Center,
            ) {
                val cover = ApiClient.absoluteUrl(coverOverride ?: playlist.coverUrl)
                if (cover.isNotBlank()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (playlist.isFavorites) {
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = colors.accent)
                } else {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = colors.muted)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    downloadLabel?.let { "Скачивание… $it" } ?: "${playlist.trackCount} треков",
                    color = if (downloading) colors.accent else colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (downloading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onCancelDownload, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Отменить скачивание",
                            tint = colors.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Ещё",
                            tint = colors.muted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // Download and delete-from-device are mutually exclusive.
                        if (isDownloaded) {
                            DropdownMenuItem(
                                text = { Text("Удалить с устройства") },
                                leadingIcon = {
                                    Icon(Icons.Filled.DeleteForever, contentDescription = null, tint = colors.error)
                                },
                                onClick = {
                                    menuOpen = false
                                    onDeleteFromDevice()
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Скачать плейлист") },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                enabled = downloadEnabled,
                                onClick = {
                                    menuOpen = false
                                    onDownload()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Обложка") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEditCover()
                            },
                        )
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("Удалить плейлист") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    vm: AppViewModel,
    playlistName: String,
    onOpenPlaylist: (String?) -> Unit,
    onOpenPlaylistPicker: (Track) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val player by vm.playerState.collectAsState()
    val showBitrate by com.musicfind.app.ui.SettingsController.bitrate.collectAsState()
    val colors = LocalMfColors.current
    val playlist = ui.playlists.firstOrNull { it.name == playlistName }
    val tracks = playlist?.tracks.orEmpty()
    val downloading = ui.downloadingPlaylist == playlistName
    var editingTrack by remember { mutableStateOf<Track?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = if (player.current != null) 150.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onOpenPlaylist(null) }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = colors.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        playlistName,
                        color = colors.text,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (downloading) {
                            "Скачивание… ${ui.playlistDownloadDone}/${ui.playlistDownloadTotal}"
                        } else {
                            "${playlist?.trackCount ?: 0} треков"
                        },
                        color = if (downloading) colors.accent else colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (downloading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
                        IconButton(
                            onClick = { vm.cancelPlaylistDownload() },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Отменить скачивание",
                                tint = colors.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else {
                    IconButton(
                        onClick = { vm.downloadPlaylist(playlistName) },
                        enabled = ui.online && ui.downloadingPlaylist == null && tracks.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Скачать плейлист",
                            tint = if (ui.online && tracks.isNotEmpty()) colors.accent else colors.muted,
                        )
                    }
                }
            }
        }
        itemsIndexed(tracks) { index, track ->
            val key = AppViewModel.trackKey(track)
            val downloading = key in ui.downloadingKeys
            val isActive = player.current?.let { current ->
                AppViewModel.favoriteKey(current) == AppViewModel.favoriteKey(track) ||
                    (track.url.isNotBlank() && track.url == current.url) ||
                    (track.fileName != null && current.fileName == track.fileName)
            } ?: false
            TrackRow(
                track = track,
                isActive = isActive,
                isPlaying = player.isPlaying,
                isFavorite = AppViewModel.favoriteKey(track) in vm.favoriteKeys,
                isDownloading = downloading,
                isPreparing = isActive && player.isPreparing,
                progressPercent = when {
                    downloading -> ui.downloadProgress[key]?.toInt()
                    isActive && player.isPreparing -> player.preparingProgress.takeIf { it >= 0f }?.toInt()
                    else -> null
                },
                showBitrate = showBitrate,
                onPlay = { vm.playTrack(track, playlistName, tracks, index) },
                onToggleFavorite = if (track.url.isNotBlank()) ({ vm.toggleFavorite(track) }) else null,
                onAddToPlaylist = { onOpenPlaylistPicker(track) },
                onEditTrack = { editingTrack = track },
                onDownload = { vm.downloadTrack(track, playlistName) },
                onCancelDownload = if (downloading) {
                    { vm.cancelDownloadTrack(track) }
                } else {
                    null
                },
                onRemove = { vm.removeTrackFromPlaylist(playlistName, track) },
                onDeleteFromDevice = if (AppViewModel.favoriteKey(track) in ui.downloadedKeys) {
                    { vm.deleteOfflineTrack(track) }
                } else {
                    null
                },
            )
        }
        if (tracks.isEmpty()) {
            item {
                Text(
                    "Плейлист пуст",
                    color = colors.muted,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }

    editingTrack?.let { track ->
        EditTrackDialog(
            track = track,
            onDismiss = { editingTrack = null },
            onSave = { title, artist ->
                vm.renameTrack(playlistName, track, title, artist)
                editingTrack = null
            },
        )
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый плейлист") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Название") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                colors = ButtonDefaults.textButtonColors(contentColor = LocalMfColors.current.accent),
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
