package com.musicfind.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musicfind.app.AppGraph
import com.musicfind.app.data.local.LocalLibrary
import com.musicfind.app.data.local.OfflinePlaylist
import com.musicfind.app.data.model.Account
import com.musicfind.app.data.model.Artist
import com.musicfind.app.data.model.LeaderboardEntry
import com.musicfind.app.data.model.Playlist
import com.musicfind.app.data.model.Profile
import com.musicfind.app.data.model.RecognizedTrack
import com.musicfind.app.data.model.Statistics
import com.musicfind.app.data.model.Track
import com.musicfind.app.player.PlayerController
import com.musicfind.app.player.QueueEntry
import com.musicfind.app.util.AudioRecorder
import com.musicfind.app.util.Formatters
import com.musicfind.app.util.NetErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val profile: Profile? = null,
    val account: Account? = null,
    val playlists: List<Playlist> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<Track> = emptyList(),
    val searching: Boolean = false,
    val recognizing: Boolean = false,
    val recognized: RecognizedTrack? = null,
    val statistics: Statistics? = null,
    val statisticsLoading: Boolean = false,
    val busy: Set<String> = emptySet(),
    val downloadingKeys: Set<String> = emptySet(),
    val downloadProgress: Map<String, Float> = emptyMap(),
    val online: Boolean = true,
    val serverReachable: Boolean = true,
    val offlinePlaylists: List<OfflinePlaylist> = emptyList(),
    val downloadedKeys: Set<String> = emptySet(),
    val offlinePlaylistNames: Set<String> = emptySet(),
    val coverOverrides: Map<String, String> = emptyMap(),
    val downloadingPlaylist: String? = null,
    val playlistDownloadDone: Int = 0,
    val playlistDownloadTotal: Int = 0,
    val favoriteArtists: List<Artist> = emptyList(),
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val leaderboardMe: LeaderboardEntry? = null,
    val leaderboardLoading: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppGraph.repository

    private var searchJob: Job? = null
    private var recognizeJob: Job? = null
    private var playlistDownloadJob: Job? = null
    private val trackDownloadJobs = mutableMapOf<String, Job>()
    private val activeDownloadIds = ConcurrentHashMap<String, String>()
    private val activeCalls = ConcurrentHashMap<String, Call>()

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    val playerState = PlayerController.state

    val favoriteKeys: Set<String>
        get() = (_ui.value.playlists.firstOrNull { it.isFavorites }?.tracks ?: emptyList())
            .map { favoriteKey(it) }
            .toSet()

    init {
        PlayerController.initialize()
        PlayerController.onPlaylistsChanged = { playlists -> applyPlaylists(playlists) }
        startConnectivityWatch()
        refreshOffline()
        refreshCovers()
    }

    private fun applyPlaylists(playlists: List<Playlist>) {
        _ui.update { it.copy(playlists = playlists) }
        PlayerController.setFavoriteKeys(
            playlists.firstOrNull { it.isFavorites }?.tracks
                ?.map { PlayerController.favoriteKey(it) }
                ?.toSet()
                ?: emptySet(),
        )
    }

    fun refreshCovers() {
        _ui.update { it.copy(coverOverrides = AppGraph.session.playlistCovers()) }
    }

    private fun startConnectivityWatch() {
        viewModelScope.launch {
            while (isActive) {
                val online = Formatters.isOnline(getApplication())
                val wasOnline = _ui.value.online
                if (online != wasOnline) {
                    _ui.update { it.copy(online = online, serverReachable = if (online) it.serverReachable else false) }
                    if (online) {
                        load()
                    }
                }
                delay(5000)
            }
        }
    }

    fun refreshOffline() {
        viewModelScope.launch {
            val library = withContext(Dispatchers.IO) { LocalLibrary.scan(getApplication()) }
            _ui.update {
                it.copy(
                    offlinePlaylists = library,
                    offlinePlaylistNames = library.map { playlist -> playlist.name }.toSet(),
                    downloadedKeys = library.flatMap { playlist -> playlist.tracks }
                        .map { track -> favoriteKey(track) }
                        .toSet(),
                )
            }
        }
    }

    fun isDownloaded(track: Track): Boolean =
        favoriteKey(track) in _ui.value.downloadedKeys

    fun deleteOfflineTrack(track: Track) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { LocalLibrary.deleteTrack(getApplication(), track) }
            if (removed) {
                refreshOffline()
                _ui.update { it.copy(message = "Удалено с устройства") }
            } else {
                _ui.update { it.copy(error = "Файл не найден на устройстве") }
            }
        }
    }

    fun deleteOfflinePlaylist(name: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { LocalLibrary.deletePlaylist(getApplication(), name) }
            if (removed) {
                refreshOffline()
                _ui.update { it.copy(message = "Плейлист удалён с устройства") }
            } else {
                _ui.update { it.copy(error = "Скачанный плейлист не найден") }
            }
        }
    }

    fun load() {
        refreshOffline()
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val online = Formatters.isOnline(getApplication())
            if (!online) {
                _ui.update {
                    it.copy(
                        loading = false,
                        online = false,
                        serverReachable = false,
                        error = null,
                        message = NetErrors.NO_INTERNET,
                    )
                }
                return@launch
            }
            val result = repo.bootstrap()
            result.onSuccess { data ->
                applyPlaylists(data.playlists)
                _ui.update {
                    it.copy(
                        loading = false,
                        online = true,
                        serverReachable = true,
                        profile = data.profile,
                        account = data.account,
                        favoriteArtists = data.favoriteArtists,
                        error = if (data.ok) null else data.message,
                    )
                }
            }.onFailure { error ->
                val connection = NetErrors.isConnectionError(error)
                _ui.update {
                    it.copy(
                        loading = false,
                        serverReachable = !connection,
                        error = NetErrors.message(error, online),
                    )
                }
            }
        }
    }

    fun setError(message: String?) = _ui.update { it.copy(error = message) }

    fun setMessage(message: String?) = _ui.update { it.copy(message = message) }

    fun updateQuery(value: String) = _ui.update { it.copy(searchQuery = value) }

    fun search() {
        val query = _ui.value.searchQuery.trim()
        if (query.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _ui.update { it.copy(searching = true, error = null, recognized = null) }
            repo.search(query)
                .onSuccess { data ->
                    _ui.update {
                        it.copy(
                            searching = false,
                            searchResults = data.tracks,
                            error = if (data.ok) null else data.message,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(searching = false, error = NetErrors.message(error, AppGraph.isOnline())) }
                }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        _ui.update { it.copy(searching = false) }
    }

    /** Clear the current query and its results. */
    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _ui.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                recognized = null,
                searching = false,
                error = null,
            )
        }
    }

    fun recognize(uri: Uri) {
        recognizeJob?.cancel()
        recognizeJob = viewModelScope.launch {
            _ui.update { it.copy(recognizing = true, error = null) }
            try {
                val file = withContext(Dispatchers.IO) { copyToCache(uri) }
                    ?: throw IllegalStateException("Не удалось прочитать файл")
                uploadRecognition(file)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _ui.update { it.copy(recognizing = false, error = NetErrors.message(t, AppGraph.isOnline())) }
            }
        }
    }

    /** Recognize a PCM file recorded from the microphone; encodes it to M4A first. */
    fun recognizeRecording(pcmFile: File) {
        if (pcmFile.length() < 44_100) {
            runCatching { pcmFile.delete() }
            _ui.update { it.copy(error = "Запись слишком короткая — попробуйте ещё раз") }
            return
        }
        recognizeJob?.cancel()
        recognizeJob = viewModelScope.launch {
            _ui.update { it.copy(recognizing = true, error = null) }
            try {
                val file = withContext(Dispatchers.IO) { AudioRecorder.encodeToM4a(pcmFile) }
                    ?: throw IllegalStateException("Не удалось обработать запись")
                uploadRecognition(file)
            } catch (c: CancellationException) {
                runCatching { pcmFile.delete() }
                throw c
            } catch (t: Throwable) {
                _ui.update { it.copy(recognizing = false, error = NetErrors.message(t, AppGraph.isOnline())) }
            }
        }
    }

    fun cancelRecognize() {
        recognizeJob?.cancel()
        recognizeJob = null
        _ui.update { it.copy(recognizing = false) }
    }

    private suspend fun uploadRecognition(file: File) {
        try {
            val part = MultipartBody.Part.createFormData(
                "file",
                file.name,
                file.asRequestBody("audio/*".toMediaType()),
            )
            repo.recognize(part)
                .onSuccess { data ->
                    _ui.update {
                        it.copy(
                            recognizing = false,
                            recognized = data.recognized,
                            searchResults = data.tracks,
                            searchQuery = data.query.ifBlank { it.searchQuery },
                            error = if (data.ok) null else data.message,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update { it.copy(recognizing = false, error = NetErrors.message(error, AppGraph.isOnline())) }
                }
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun copyToCache(uri: Uri): File? {
        return try {
            val resolver = getApplication<Application>().contentResolver
            val extension = resolver.getType(uri)?.let { mime ->
                when {
                    mime.contains("wav") -> ".wav"
                    mime.contains("mpeg") || mime.contains("mp3") -> ".mp3"
                    mime.contains("ogg") || mime.contains("opus") -> ".ogg"
                    mime.contains("webm") -> ".webm"
                    mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> ".m4a"
                    else -> null
                }
            } ?: uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() }
                ?.let { ".$it" }
                ?: ""
            val input = resolver.openInputStream(uri) ?: return null
            val file = File(getApplication<Application>().cacheDir, "temp_${System.currentTimeMillis()}$extension")
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            file
        } catch (_: Exception) {
            null
        }
    }

    fun loadStatistics() {
        if (_ui.value.statisticsLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(statisticsLoading = true) }
            repo.statistics()
                .onSuccess { data ->
                    _ui.update { it.copy(statisticsLoading = false, statistics = data.statistics) }
                }
                .onFailure { error ->
                    _ui.update { it.copy(statisticsLoading = false, error = NetErrors.message(error, AppGraph.isOnline())) }
                }
            repo.activityPing()
        }
    }

    fun loadLeaderboard() {
        if (_ui.value.leaderboardLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(leaderboardLoading = true) }
            repo.leaderboard()
                .onSuccess { data ->
                    _ui.update {
                        it.copy(
                            leaderboardLoading = false,
                            leaderboard = data.top,
                            leaderboardMe = data.me,
                        )
                    }
                }
                .onFailure { error ->
                    _ui.update {
                        it.copy(
                            leaderboardLoading = false,
                            error = NetErrors.message(error, AppGraph.isOnline()),
                        )
                    }
                }
        }
    }

    fun playTrack(
        track: Track,
        playlistName: String? = null,
        queue: List<Track> = listOf(track),
        index: Int = 0,
        contextLabel: String? = null,
    ) {
        val label = playlistName?.takeIf { it.isNotBlank() }
            ?: contextLabel?.takeIf { it.isNotBlank() }
        // If a track was downloaded to the device, play it from the local file even
        // when it appears in a server playlist — so it works offline.
        val localByKey = _ui.value.offlinePlaylists
            .flatMap { playlist -> playlist.tracks }
            .associateBy { favoriteKey(it) }
        val playableQueue = queue.map { localByKey[favoriteKey(it)] ?: it }
        val entries = playableQueue.map { QueueEntry(it, playlistName) }
        val current = PlayerController.state.value
        if (current.current != null && favoriteKey(current.current) == favoriteKey(track)) {
            // Tapping the current track must not restart its preparation; repeated
            // taps previously cancelled the in-flight request so it never loaded.
            when {
                current.isPreparing -> return
                current.error != null -> PlayerController.setQueue(entries, index, label)
                else -> PlayerController.toggle()
            }
            return
        }
        PlayerController.setQueue(entries, index, label)
    }

    fun toggleFavorite(track: Track) {
        val key = trackKey(track)
        viewModelScope.launch {
            _ui.update { it.copy(busy = it.busy + key) }
            repo.toggleFavorite(track)
                .onSuccess { data -> applyPlaylists(data.playlists); _ui.update { it.copy(busy = it.busy - key) } }
                .onFailure { error -> _ui.update { it.copy(busy = it.busy - key, error = error.message) } }
        }
    }

    fun addToPlaylist(playlistName: String, track: Track) {
        val key = trackKey(track)
        viewModelScope.launch {
            _ui.update { it.copy(busy = it.busy + key) }
            repo.addTrackToPlaylist(playlistName, track)
                .onSuccess { data -> _ui.update { it.copy(busy = it.busy - key, playlists = data.playlists, message = "Добавлено в $playlistName") } }
                .onFailure { error -> _ui.update { it.copy(busy = it.busy - key, error = error.message) } }
        }
    }

    fun createPlaylist(name: String, onCreated: ((String) -> Unit)? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repo.createPlaylist(name)
                .onSuccess { data ->
                    applyPlaylists(data.playlists)
                    onCreated?.invoke(data.playlistName ?: name)
                }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    fun removePlaylist(name: String) {
        viewModelScope.launch {
            repo.removePlaylist(name)
                .onSuccess { data -> applyPlaylists(data.playlists) }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    fun removeTrackFromPlaylist(playlistName: String, track: Track) {
        viewModelScope.launch {
            repo.removePlaylistTrack(playlistName, track)
                .onSuccess { data -> applyPlaylists(data.playlists) }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    fun renameTrack(playlistName: String, track: Track, title: String, artist: String) {
        val newTitle = title.trim()
        val newArtist = artist.trim()
        if (newTitle.isBlank() && newArtist.isBlank()) return
        val effectiveTitle = newTitle.ifBlank { track.title }
        val wasDownloaded = favoriteKey(track) in _ui.value.downloadedKeys
        viewModelScope.launch {
            repo.renamePlaylistTrack(playlistName, track, effectiveTitle, newArtist)
                .onSuccess { data ->
                    applyPlaylists(data.playlists)
                    _ui.update { it.copy(message = "Трек обновлён") }
                    if (wasDownloaded) {
                        // Re-download on the device under the new name.
                        val updated = track.copy(
                            title = effectiveTitle,
                            artist = newArtist,
                            fullTitle = listOf(newArtist, effectiveTitle)
                                .filter { it.isNotBlank() }
                                .joinToString(" - "),
                        )
                        withContext(Dispatchers.IO) {
                            LocalLibrary.deleteTrack(getApplication(), track)
                        }
                        refreshOffline()
                        downloadTrack(updated, playlistName)
                    }
                }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    fun updatePlaylistCover(name: String, coverUrl: String) {
        viewModelScope.launch {
            repo.updatePlaylistCover(name, coverUrl)
                .onSuccess { data -> applyPlaylists(data.playlists) }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    fun setLocalPlaylistCover(name: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val folder = File(
                    getApplication<Application>().getExternalFilesDir(null),
                    "MusicFind/covers",
                ).apply { mkdirs() }
                val target = File(folder, "${LocalLibrary.sanitize(name)}.jpg")
                withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Не удалось открыть изображение")
                    input.use { source -> target.outputStream().use { source.copyTo(it) } }
                }
                val fileUri = "file://${target.absolutePath}"
                AppGraph.session.setPlaylistCover(name, fileUri)
                _ui.update {
                    it.copy(
                        coverOverrides = it.coverOverrides + (name to fileUri),
                        message = "Обложка обновлена",
                    )
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(error = t.message ?: "Не удалось открыть изображение") }
            }
        }
    }

    fun updateProfile(displayName: String?, avatarUrl: String?) {
        viewModelScope.launch {
            repo.updateProfile(displayName, avatarUrl)
                .onSuccess {
                    _ui.update { it.copy(message = "Профиль обновлён") }
                    // Re-read from the server so cleared/updated fields are reflected exactly.
                    load()
                }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { copyToCache(uri) }
                if (file == null) {
                    _ui.update { it.copy(error = "Не удалось прочитать файл") }
                    return@launch
                }
                val part = MultipartBody.Part.createFormData(
                    "avatar",
                    file.name,
                    file.asRequestBody("image/*".toMediaType()),
                )
                repo.uploadAvatar(part)
                    .onSuccess {
                        _ui.update { it.copy(message = "Аватар обновлён") }
                        // Re-read from the server so the new avatar URL is applied.
                        load()
                    }
                    .onFailure { error -> _ui.update { it.copy(error = error.message) } }
            } catch (t: Throwable) {
                _ui.update { it.copy(error = t.message) }
            }
        }
    }

    /** Upload an avatar that was already cropped/saved by the editor. */
    fun uploadAvatarFile(file: File) {
        viewModelScope.launch {
            try {
                val part = MultipartBody.Part.createFormData(
                    "avatar",
                    file.name,
                    file.asRequestBody("image/jpeg".toMediaType()),
                )
                repo.uploadAvatar(part)
                    .onSuccess {
                        _ui.update { it.copy(message = "Аватар обновлён") }
                        load()
                    }
                    .onFailure { error -> _ui.update { it.copy(error = error.message) } }
            } catch (t: Throwable) {
                _ui.update { it.copy(error = t.message ?: "Не удалось загрузить аватар") }
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    fun deleteAvatar() {
        viewModelScope.launch {
            repo.updateProfile(null, "")
                .onSuccess {
                    _ui.update { it.copy(message = "Аватар удалён") }
                    load()
                }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    fun unlinkTelegram() {
        viewModelScope.launch {
            repo.unlinkTelegram()
                .onSuccess { _ui.update { state -> state.copy(message = "Telegram отвязан") } }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    fun logout() {
        searchJob?.cancel()
        recognizeJob?.cancel()
        playlistDownloadJob?.cancel()
        trackDownloadJobs.values.forEach { it.cancel() }
        trackDownloadJobs.clear()
        PlayerController.close()
        AppGraph.logout()
        // Drop the previous account's data so the next login starts clean.
        _ui.value = HomeUiState()
    }

    fun downloadTrack(track: Track, playlistName: String? = null) {
        val key = trackKey(track)
        val downloadKey = "track:$key"
        val targetPlaylist = playlistName?.takeIf { it.isNotBlank() } ?: "Загрузки"
        trackDownloadJobs.remove(key)?.cancel()
        trackDownloadJobs[key] = viewModelScope.launch {
            _ui.update {
                it.copy(
                    busy = it.busy + key,
                    downloadingKeys = it.downloadingKeys + key,
                    downloadProgress = it.downloadProgress + (key to 0f),
                    message = "Скачивание…",
                )
            }
            try {
                val media = repo.resolveMedia(
                    track,
                    playlistName,
                    onProgress = { progress -> updateDownloadProgress(key, progress.toFloat()) },
                    onDownloadId = { activeDownloadIds[downloadKey] = it },
                )
                if (media == null || media.audioUrl.isBlank()) {
                    throw IllegalStateException("Не удалось получить файл")
                }
                val audioUrl = com.musicfind.app.data.remote.ApiClient.absoluteUrl(media.audioUrl)
                val coverUrl = media.coverUrl?.let { com.musicfind.app.data.remote.ApiClient.absoluteUrl(it) }
                val (bytes, cover) = withContext(Dispatchers.IO) {
                    val audio = downloadBytes(audioUrl, downloadKey) { p ->
                        updateDownloadProgress(key, p * 100f)
                    }
                    val coverBytes = coverUrl?.let { runCatching { downloadBytes(it, downloadKey) }.getOrNull() }
                    audio to coverBytes
                }
                withContext(Dispatchers.IO) {
                    LocalLibrary.save(getApplication(), targetPlaylist, track, bytes, cover)
                }
                refreshOffline()
                _ui.update {
                    it.copy(
                        busy = it.busy - key,
                        downloadingKeys = it.downloadingKeys - key,
                        downloadProgress = it.downloadProgress - key,
                        message = "Скачано в «$targetPlaylist»",
                    )
                }
            } catch (c: CancellationException) {
                _ui.update { it.copy(busy = it.busy - key, downloadingKeys = it.downloadingKeys - key, downloadProgress = it.downloadProgress - key) }
                throw c
            } catch (t: Throwable) {
                _ui.update { it.copy(busy = it.busy - key, downloadingKeys = it.downloadingKeys - key, downloadProgress = it.downloadProgress - key, error = NetErrors.message(t, AppGraph.isOnline())) }
            } finally {
                activeDownloadIds.remove(downloadKey)
                activeCalls.remove(downloadKey)
            }
        }
    }

    private fun updateDownloadProgress(key: String, percent: Float) {
        _ui.update { it.copy(downloadProgress = it.downloadProgress + (key to percent.coerceIn(0f, 100f))) }
    }

    fun cancelDownloadTrack(track: Track) {
        val key = trackKey(track)
        val downloadKey = "track:$key"
        cancelActiveServerDownload(downloadKey)
        trackDownloadJobs.remove(key)?.cancel()
        _ui.update { it.copy(message = "Скачивание отменено") }
    }

    private fun downloadBytes(
        url: String,
        downloadKey: String? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): ByteArray {
        val request = okhttp3.Request.Builder().url(url).build()
        val call = com.musicfind.app.data.remote.ApiClient.okHttp.newCall(request)
        if (downloadKey != null) activeCalls[downloadKey] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Пустой ответ")
                val total = body.contentLength()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            onProgress?.invoke((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                return output.toByteArray()
            }
        } finally {
            if (downloadKey != null && activeCalls[downloadKey] === call) activeCalls.remove(downloadKey)
        }
    }

    fun downloadPlaylist(playlistName: String) {
        val playlist = _ui.value.playlists.firstOrNull { it.name == playlistName } ?: return
        if (_ui.value.downloadingPlaylist != null) return
        if (playlist.tracks.isEmpty()) {
            _ui.update { it.copy(message = "Плейлист «$playlistName» пуст") }
            return
        }
        if (!Formatters.isOnline(getApplication())) {
            _ui.update { it.copy(error = NetErrors.NO_INTERNET) }
            return
        }
        val downloadKey = "playlist:$playlistName"
        playlistDownloadJob?.cancel()
        playlistDownloadJob = viewModelScope.launch {
            val tracks = playlist.tracks
            _ui.update {
                it.copy(
                    downloadingPlaylist = playlistName,
                    playlistDownloadDone = 0,
                    playlistDownloadTotal = tracks.size,
                    message = "Скачивание «$playlistName»…",
                    error = null,
                )
            }
            var done = 0
            var failed = 0
            var skipped = 0
            val alreadyDownloaded = _ui.value.downloadedKeys
            try {
                for (track in tracks) {
                    if (favoriteKey(track) in alreadyDownloaded) {
                        skipped++
                        done++
                        _ui.update { it.copy(playlistDownloadDone = done) }
                        continue
                    }
                    try {
                        val media = repo.resolveMedia(track, playlistName, onDownloadId = { activeDownloadIds[downloadKey] = it })
                        if (media == null || media.audioUrl.isBlank()) {
                            throw IllegalStateException("Не удалось получить файл")
                        }
                        val audioUrl = com.musicfind.app.data.remote.ApiClient.absoluteUrl(media.audioUrl)
                        val coverUrl = media.coverUrl?.let { com.musicfind.app.data.remote.ApiClient.absoluteUrl(it) }
                        val (bytes, cover) = withContext(Dispatchers.IO) {
                            val audio = downloadBytes(audioUrl, downloadKey)
                            val coverBytes = coverUrl?.let { runCatching { downloadBytes(it, downloadKey) }.getOrNull() }
                            audio to coverBytes
                        }
                        withContext(Dispatchers.IO) {
                            LocalLibrary.save(getApplication(), playlistName, track, bytes, cover)
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        failed++
                    }
                    done++
                    _ui.update { it.copy(playlistDownloadDone = done) }
                }
            } catch (c: CancellationException) {
                refreshOffline()
                _ui.update {
                    it.copy(
                        downloadingPlaylist = null,
                        playlistDownloadTotal = 0,
                        playlistDownloadDone = 0,
                        message = "Скачивание отменено",
                    )
                }
                throw c
            } finally {
                activeDownloadIds.remove(downloadKey)
                activeCalls.remove(downloadKey)
            }
            refreshOffline()
            val suffix = if (failed > 0) ", ошибок: $failed" else ""
            val skipSuffix = if (skipped > 0) ", пропущено: $skipped" else ""
            _ui.update {
                it.copy(
                    downloadingPlaylist = null,
                    playlistDownloadTotal = 0,
                    playlistDownloadDone = 0,
                    message = "Скачано «$playlistName»: $done${if (failed > 0) "/${tracks.size}" else ""}$suffix$skipSuffix",
                )
            }
        }
    }

    fun cancelPlaylistDownload() {
        val name = _ui.value.downloadingPlaylist ?: return
        val downloadKey = "playlist:$name"
        cancelActiveServerDownload(downloadKey)
        playlistDownloadJob?.cancel()
        playlistDownloadJob = null
        _ui.update {
            it.copy(
                downloadingPlaylist = null,
                playlistDownloadTotal = 0,
                playlistDownloadDone = 0,
                message = "Скачивание отменено",
            )
        }
    }

    private fun cancelActiveServerDownload(downloadKey: String) {
        val id = activeDownloadIds.remove(downloadKey)
        if (id != null) {
            viewModelScope.launch { repo.cancelDownload(id) }
        }
        activeCalls.remove(downloadKey)?.cancel()
    }

    companion object {
        fun trackKey(track: Track): String =
            "${track.artist.lowercase()}|${track.title.lowercase()}|${track.url}"

        fun favoriteKey(track: Track): String =
            "${track.artist.lowercase().trim()}|${track.title.lowercase().trim()}"
    }
}
