package com.musicfind.app.ui.screens

import android.Manifest
import com.musicfind.app.R
import com.musicfind.app.util.Loc
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.musicfind.app.data.model.Track
import com.musicfind.app.ui.AppViewModel
import com.musicfind.app.ui.components.GlassSurface
import com.musicfind.app.ui.components.MicVisualizer
import com.musicfind.app.ui.components.SectionTitle
import com.musicfind.app.ui.components.TrackRow
import com.musicfind.app.ui.theme.LocalMfColors
import com.musicfind.app.util.AudioRecorder
import kotlinx.coroutines.delay

private const val MAX_RECORD_SECONDS = 12

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpenPlaylistPicker: (Track) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val player by vm.playerState.collectAsState()
    val showBitrate by com.musicfind.app.ui.SettingsController.bitrate.collectAsState()
    val colors = LocalMfColors.current
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.recognize(it) }
    }

    val recorder = remember { AudioRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    val amplitudes = remember { mutableStateListOf<Float>() }

    fun beginRecording() {
        if (recorder.start() != null) {
            amplitudes.clear()
            recordSeconds = 0
            recording = true
        } else {
            vm.setError(Loc.s(R.string.record_start_failed))
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            beginRecording()
        } else {
            vm.setError(Loc.s(R.string.no_mic_access))
        }
    }

    fun startRecording() {
        if (recorder.hasPermission()) beginRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        val pcm = recorder.stop()
        if (pcm != null) vm.recognizeRecording(pcm) else vm.setError(Loc.s(R.string.record_save_failed))
    }

    // Poll microphone loudness while recording to drive the visualizer.
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (true) {
            amplitudes.add(recorder.amplitude())
            if (amplitudes.size > 80) amplitudes.removeAt(0)
            delay(40)
        }
    }

    // Countdown + auto stop.
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (recordSeconds < MAX_RECORD_SECONDS) {
            delay(1000)
            recordSeconds++
        }
        stopRecording()
    }

    val infinite = rememberInfiniteTransition(label = "rec")
    val blink by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "recBlink",
    )

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
            GlassSurface(Modifier.fillMaxWidth()) {
                SectionTitle(
                    title = Loc.s(R.string.search_music),
                    subtitle = Loc.s(R.string.search_tagline),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.widthIn(max = 300.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = ui.searchQuery,
                        onValueChange = vm::updateQuery,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                Loc.s(R.string.title_or_artist),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (ui.searchQuery.isNotEmpty()) {
                                IconButton(onClick = vm::clearSearch) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = Loc.s(R.string.clear),
                                        tint = colors.muted,
                                    )
                                }
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (ui.searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = if (ui.searching) vm::cancelSearch else vm::search) {
                        if (ui.searching) {
                            Icon(Icons.Filled.Close, contentDescription = Loc.s(R.string.cancel_search))
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.widthIn(max = 300.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { filePicker.launch("audio/*") },
                        modifier = Modifier.weight(1f),
                        enabled = !ui.recognizing && !recording,
                    ) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(Loc.s(R.string.file))
                    }
                    OutlinedButton(
                        onClick = { if (recording) stopRecording() else startRecording() },
                        modifier = Modifier.weight(1f),
                        enabled = !ui.recognizing,
                    ) {
                        if (recording) {
                            Icon(Icons.Filled.Stop, contentDescription = null, tint = colors.accent)
                            Spacer(Modifier.width(8.dp))
                            Text(Loc.s(R.string.stop_countdown, MAX_RECORD_SECONDS - recordSeconds))
                        } else {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(Loc.s(R.string.listen))
                        }
                    }
                }

                if (recording) {
                    MicVisualizer(
                        amplitudes = amplitudes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(top = 12.dp),
                        color = colors.accent,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .graphicsLayer { alpha = blink }
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Loc.s(R.string.listening_hint),
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (ui.recognizing) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(Loc.s(R.string.recognizing), color = colors.accent, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = vm::cancelRecognize) {
                            Text(Loc.s(R.string.cancel), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                ui.recognized?.let { rec ->
                    Text(
                        Loc.s(R.string.recognized_result, rec.artist, rec.title),
                        color = colors.accent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (ui.searchResults.isNotEmpty()) {
            item {
                SectionTitle(
                    title = Loc.s(R.string.results),
                    subtitle = Loc.s(R.string.tracks_count, ui.searchResults.size),
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        IconButton(onClick = vm::clearSearch) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = Loc.s(R.string.clear_results),
                                tint = colors.muted,
                            )
                        }
                    },
                )
            }
            itemsIndexed(ui.searchResults) { index, track ->
                val key = AppViewModel.trackKey(track)
                val isActive = player.current?.let { current ->
                    AppViewModel.favoriteKey(current) == AppViewModel.favoriteKey(track) ||
                        (track.url.isNotBlank() && track.url == current.url)
                } ?: false
                val downloading = key in ui.downloadingKeys
                TrackRow(
                    track = track,
                    isActive = isActive,
                    isPlaying = player.isPlaying,
                    isFavorite = AppViewModel.favoriteKey(track) in vm.favoriteKeys,
                    busy = key in ui.busy,
                    isDownloading = downloading,
                    isPreparing = isActive && player.isPreparing,
                    progressPercent = when {
                        downloading -> ui.downloadProgress[key]?.toInt()
                        isActive && player.isPreparing -> player.preparingProgress.takeIf { it >= 0f }?.toInt()
                        else -> null
                    },
                    showBitrate = showBitrate,
                    onPlay = { vm.playTrack(track, null, ui.searchResults, index, ui.searchQuery) },
                    onToggleFavorite = { vm.toggleFavorite(track) },
                    onAddToPlaylist = { onOpenPlaylistPicker(track) },
                    onDownload = { vm.downloadTrack(track) },
                    onCancelDownload = if (downloading) {
                        { vm.cancelDownloadTrack(track) }
                    } else {
                        null
                    },
                    onDeleteFromDevice = if (AppViewModel.favoriteKey(track) in ui.downloadedKeys) {
                        { vm.deleteOfflineTrack(track) }
                    } else {
                        null
                    },
                )
            }
        } else if (!ui.searching && ui.searchQuery.length >= 2) {
            item {
                Text(
                    Loc.s(R.string.nothing_found),
                    color = colors.muted,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
}
