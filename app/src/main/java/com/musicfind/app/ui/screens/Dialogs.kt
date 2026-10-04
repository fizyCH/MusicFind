package com.musicfind.app.ui.screens

import android.net.Uri
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
    val colors = LocalMfColors.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val accents = listOf("#22C55E", "#10B981", "#38BDF8", "#A855F7", "#F59E0B", "#F43F5E")

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
                        updateMessage = "Установлена последняя версия"
                    }
                }
                .onFailure {
                    checking = false
                    updateMessage = it.message ?: "Не удалось проверить обновление"
                }
        }
    }

    fun installUpdate(info: UpdateInfo) {
        if (!Updater.canInstallPackages(context)) {
            updateMessage = "Разрешите установку из этого источника"
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
                    updateMessage = it.message ?: "Ошибка загрузки обновления"
                }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Сервер", color = colors.muted, style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Адрес сервера") },
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
                ) { Text("Сохранить сервер") }
                Text("Тема", color = colors.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip("Тёмная", theme == "dark") { SettingsController.setTheme("dark") }
                    ThemeChip("Светлая", theme == "light") { SettingsController.setTheme("light") }
                }
                Text("Акцент", color = colors.muted, style = MaterialTheme.typography.labelMedium)
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
                    Text("Показывать битрейт", modifier = Modifier.weight(1f))
                    Switch(checked = bitrate, onCheckedChange = { SettingsController.setBitrate(it) })
                }
                Text("Шаг перемотки (двойной тап)", color = colors.muted, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { value ->
                        ThemeChip("$value с", seekStep == value) { SettingsController.setSeekStep(value) }
                    }
                }
                TextButton(
                    onClick = { com.musicfind.app.util.PowerUtils.requestIgnoreBatteryOptimizations(context) },
                ) {
                    Text(
                        if (com.musicfind.app.util.PowerUtils.isIgnoringBatteryOptimizations(context)) {
                            "Фоновое воспроизведение: включено"
                        } else {
                            "Разрешить фоновое воспроизведение"
                        },
                        color = colors.accent,
                    )
                }
                Text("Обновление", color = colors.muted, style = MaterialTheme.typography.labelMedium)
                Text(
                    "Версия ${Updater.currentVersionName()} (${Updater.currentVersionCode()})",
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
                            "Загрузка… ${(downloadProgress * 100).toInt()}%",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    availableUpdate != null -> {
                        val info = availableUpdate!!
                        Text(
                            "Доступна версия ${info.versionName}" +
                                if (info.notes.isNotBlank()) "\n${info.notes}" else "",
                            color = colors.accent,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { installUpdate(info) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Скачать и установить") }
                    }
                    else -> {
                        updateMessage?.let {
                            Text(it, color = colors.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { checkUpdate() }, enabled = !checking) {
                            Text(
                                if (checking) "Проверяем…" else "Проверить обновление",
                                color = colors.accent,
                            )
                        }
                    }
                }
                TextButton(onClick = onLogout) {
                    Text("Выйти из аккаунта", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
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
        title = { Text(profile?.firstName?.ifBlank { profile?.username } ?: "Профиль") },
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
                        "Telegram привязан (ID ${profile.telegramId})",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Изменить аватар")
                }
                if (!avatarUrl.isNullOrBlank()) {
                    TextButton(
                        onClick = { vm.deleteAvatar() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Удалить аватар", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
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
        title = { Text("В плейлист") },
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
                    label = { Text("Новый плейлист") },
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
            ) { Text("Создать и добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
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
        title = { Text("Изменить трек") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Автор") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), artist.trim()) },
                enabled = title.isNotBlank() || artist.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
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
        title = { Text("Обложка плейлиста") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onPickGallery, modifier = Modifier.fillMaxWidth()) {
                    Text("Выбрать из галереи")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Или ссылка (http/https)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(value.trim()) },
                enabled = value.isNotBlank(),
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
