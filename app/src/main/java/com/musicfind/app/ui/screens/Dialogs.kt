package com.musicfind.app.ui.screens

import android.net.Uri
import com.musicfind.app.R
import com.musicfind.app.util.Loc
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicfind.app.AppGraph
import com.musicfind.app.data.model.LyricsLine
import com.musicfind.app.data.model.Playlist
import com.musicfind.app.data.model.Track
import com.musicfind.app.ui.AppViewModel
import com.musicfind.app.ui.SettingsController
import com.musicfind.app.ui.components.UserAvatar
import com.musicfind.app.ui.theme.LocalMfColors
import com.musicfind.app.util.UpdateInfo
import com.musicfind.app.util.Updater
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
    onServerChanged: () -> Unit = {},
) {
    val theme by SettingsController.theme.collectAsState()
    val accent by SettingsController.accent.collectAsState()
    val bitrate by SettingsController.bitrate.collectAsState()
    val seekStep by SettingsController.seekStep.collectAsState()
    val language by SettingsController.language.collectAsState()
    val colors = LocalMfColors.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val accents = listOf("#22C55E", "#10B981", "#38BDF8", "#A855F7", "#F59E0B", "#F43F5E")

    fun applyLanguage(value: String) {
        SettingsController.setLanguage(value)
        com.musicfind.app.util.LocaleHelper.apply(context, value)
        (context as? android.app.Activity)?.recreate()
    }

    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var serverUrl by remember { mutableStateOf(AppGraph.session.serverUrl) }

    fun checkUpdate() {
        checking = true
        updateMessage = null
        availableUpdate = null
        scope.launch {
            Updater.check()
                .onSuccess { info ->
                    checking = false
                    if (info.versionCode > Updater.currentVersionCode()) {
                        availableUpdate = info
                    } else {
                        updateMessage = Loc.s(R.string.latest_version)
                    }
                }
                .onFailure {
                    checking = false
                    updateMessage = it.message ?: Loc.s(R.string.update_check_failed)
                }
        }
    }

    fun installUpdate(info: UpdateInfo) {
        if (!Updater.canInstallPackages(context)) {
            updateMessage = Loc.s(R.string.allow_install_source)
            Updater.openInstallPermissionSettings(context)
            return
        }
        downloading = true
        downloadProgress = 0f
        updateMessage = null
        scope.launch {
            runCatching { Updater.download(context, info) { progress -> downloadProgress = progress } }
                .onSuccess { file ->
                    downloading = false
                    Updater.install(context, file)
                }
                .onFailure {
                    downloading = false
                    updateMessage = it.message ?: Loc.s(R.string.update_download_error)
                }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Loc.s(R.string.settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(Loc.s(R.string.server), color = colors.muted, style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(Loc.s(R.string.server_address)) },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val value = serverUrl.trim()
                        if (value.isNotBlank()) {
                            AppGraph.updateServer(value)
                            serverUrl = AppGraph.session.serverUrl
                            onServerChanged()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(Loc.s(R.string.save_server)) }
                Text(Loc.s(R.string.language), color = colors.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(Loc.s(R.string.language_en), language == "en") { applyLanguage("en") }
                    ThemeChip(Loc.s(R.string.language_ru), language == "ru") { applyLanguage("ru") }
                }
                Text(Loc.s(R.string.theme), color = colors.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(Loc.s(R.string.theme_dark), theme == "dark") { SettingsController.setTheme("dark") }
                    ThemeChip(Loc.s(R.string.theme_light), theme == "light") { SettingsController.setTheme("light") }
                }
                Text(Loc.s(R.string.accent), color = colors.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    accents.forEach { value ->
                        val color = com.musicfind.app.ui.theme.parseHexColor(value)
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (accent.equals(value, true)) 3.dp else 0.dp,
                                    color = if (accent.equals(value, true)) colors.text else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { SettingsController.setAccent(value) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Loc.s(R.string.show_bitrate), modifier = Modifier.weight(1f))
                    Switch(checked = bitrate, onCheckedChange = { SettingsController.setBitrate(it) })
                }
                Text(Loc.s(R.string.seek_step), color = colors.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { value ->
                        ThemeChip(Loc.s(R.string.seconds_count, value), seekStep == value) { SettingsController.setSeekStep(value) }
                    }
                }
                TextButton(
                    onClick = { com.musicfind.app.util.PowerUtils.requestIgnoreBatteryOptimizations(context) },
                ) {
                    Text(
                        if (com.musicfind.app.util.PowerUtils.isIgnoringBatteryOptimizations(context)) {
                            Loc.s(R.string.background_playback_on)
                        } else {
                            Loc.s(R.string.allow_background_playback)
                        },
                        color = colors.accent,
                    )
                }
                Text(Loc.s(R.string.update), color = colors.muted, style = MaterialTheme.typography.labelMedium)
                Text(
                    Loc.s(R.string.version_full, Updater.currentVersionName(), Updater.currentVersionCode()),
                    color = colors.text,
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    downloading -> {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accent,
                        )
                        Text(
                            Loc.s(R.string.loading_download_percent, (downloadProgress * 100).toInt()),
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    availableUpdate != null -> {
                        val info = availableUpdate!!
                        Text(
                            Loc.s(R.string.version_available, info.versionName) +
                                if (info.notes.isNotBlank()) "\n${info.notes}" else "",
                            color = colors.accent,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { installUpdate(info) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(Loc.s(R.string.download_and_install)) }
                    }
                    else -> {
                        updateMessage?.let {
                            Text(it, color = colors.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { checkUpdate() }, enabled = !checking) {
                            Text(
                                if (checking) Loc.s(R.string.checking) else Loc.s(R.string.check_updates),
                                color = colors.accent,
                            )
                        }
                    }
                }
                TextButton(onClick = onLogout) {
                    Text(Loc.s(R.string.logout), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Loc.s(R.string.done)) }
        },
    )
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalMfColors.current
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) colors.accentSoft else colors.glass)
            .border(1.dp, if (selected) colors.accent else colors.glassBorder, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) colors.accent else colors.text)
    }
}

@Composable
fun ProfileDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val colors = LocalMfColors.current
    val profile = ui.profile
    val avatarUrl = profile?.photoUrl?.takeIf { it.isNotBlank() }
        ?: ui.account?.avatarUrl?.takeIf { it.isNotBlank() }
    var editorUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) editorUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile?.firstName?.ifBlank { profile?.username } ?: Loc.s(R.string.profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    UserAvatar(
                        photoUrl = avatarUrl,
                        name = profile?.displayName?.takeIf { it.isNotBlank() }
                            ?: profile?.firstName ?: profile?.username,
                        size = 96.dp,
                    )
                }
                Text(
                    profile?.username?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "ID ${profile?.id ?: "-"}",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (profile?.telegramId != null) {
                    Text(
                        Loc.s(R.string.telegram_linked, profile.telegramId),
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(Loc.s(R.string.change_avatar))
                }
                if (!avatarUrl.isNullOrBlank()) {
                    TextButton(
                        onClick = { vm.deleteAvatar() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(Loc.s(R.string.delete_avatar), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Loc.s(R.string.close)) }
        },
    )

    editorUri?.let { uri ->
        AvatarEditorDialog(
            uri = uri,
            onDismiss = { editorUri = null },
            onSave = { file ->
                vm.uploadAvatarFile(file)
                editorUri = null
            },
        )
    }
}

@Composable
fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    track: Track,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
) {
    val colors = LocalMfColors.current
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Loc.s(R.string.to_playlist)) },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    track.displayTitle,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                playlists.forEach { playlist ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .clickable { onSelect(playlist.name) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                    ) {
                        Text(playlist.name, color = colors.text)
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(Loc.s(R.string.new_playlist)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (newName.isNotBlank()) onCreate(newName.trim()) },
                enabled = newName.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
            ) { Text(Loc.s(R.string.create_and_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Loc.s(R.string.cancel)) } },
    )
}

@Composable
fun EditTrackDialog(
    track: Track,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String) -> Unit,
) {
    var title by remember { mutableStateOf(track.displayTitle) }
    var artist by remember { mutableStateOf(track.artist) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Loc.s(R.string.edit_track)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(Loc.s(R.string.title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(Loc.s(R.string.artist)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), artist.trim()) },
                enabled = title.isNotBlank() || artist.isNotBlank(),
            ) { Text(Loc.s(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Loc.s(R.string.cancel)) } },
    )
}

@Composable
fun CoverDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onPickGallery: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Loc.s(R.string.playlist_cover)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPickGallery, modifier = Modifier.fillMaxWidth()) {
                    Text(Loc.s(R.string.choose_from_gallery))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(Loc.s(R.string.or_link)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text(Loc.s(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Loc.s(R.string.cancel)) } },
    )
}
