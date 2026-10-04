package com.musicfind.app.player

import android.util.Log
import com.musicfind.app.AppGraph
import com.musicfind.app.data.local.LocalLibrary
import com.musicfind.app.data.model.MediaTrack
import com.musicfind.app.data.model.Playlist
import com.musicfind.app.data.model.Track
import com.musicfind.app.data.remote.ApiClient
import com.musicfind.app.service.PlaybackService
import com.musicfind.app.util.NetErrors
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

enum class RepeatMode { OFF, ALL, ONE }

data class QueueEntry(
    val track: Track,
    val playlistName: String? = null,
)

@Serializable
private data class PersistedEntry(
    val track: Track,
    val playlistName: String? = null,
)

@Serializable
private data class PersistedPlayer(
    val track: Track,
    val playlistName: String? = null,
    val index: Int = 0,
    val positionMs: Long = 0L,
    val repeatMode: String = "OFF",
    val shuffle: Boolean = false,
    val queue: List<PersistedEntry> = emptyList(),
    val savedAt: Long = 0L,
    val contextLabel: String? = null,
)

data class PlayerUiState(
    val current: Track? = null,
    val currentPlaylist: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val isPreparing: Boolean = false,
    val preparingLabel: String = "",
    val preparingProgress: Float = -1f,
    val error: String? = null,
    val hasQueue: Boolean = false,
    val isFavorite: Boolean = false,
    val contextLabel: String? = null,
)

object PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val favoriteKeys = mutableSetOf<String>()

    var onPlaylistsChanged: ((List<Playlist>) -> Unit)? = null

    private var queue: List<QueueEntry> = emptyList()
    private var index: Int = -1
    private var shuffleOrder: List<Int> = emptyList()
    private var currentMediaId: String? = null
    private var playJob: Job? = null
    private var initialized = false
    private var pendingSeekMs: Long = 0L
    private var restoredPending = false
    private var lastPersistAt = 0L

    fun initialize() {
        if (initialized) return
        initialized = true
        PlaybackService.onEnded = { handleEnded() }
        PlaybackService.onPlayingChanged = { playing ->
            _state.value = _state.value.copy(isPlaying = playing)
            persist()
        }
        PlaybackService.onNext = { next() }
        PlaybackService.onPrevious = { previous() }
        PlaybackService.onToggleFavorite = { toggleCurrentFavorite() }
        PlaybackService.onError = { throwable ->
            _state.value = _state.value.copy(
                isPreparing = false,
                error = NetErrors.message(throwable, AppGraph.isOnline()),
            )
        }
        restorePersisted()
        scope.launch {
            while (isActive) {
                val service = PlaybackService.instance
                if (service != null && _state.value.current != null && !restoredPending) {
                    val playerDuration = PlaybackService.duration()
                    _state.value = _state.value.copy(
                        isPlaying = PlaybackService.isPlaying(),
                        positionMs = PlaybackService.position(),
                        durationMs = if (playerDuration > 0) playerDuration else _state.value.durationMs,
                    )
                }
                val now = System.currentTimeMillis()
                if (now - lastPersistAt >= 5000) {
                    lastPersistAt = now
                    persist()
                }
                delay(500)
            }
        }
    }

    private fun persist() {
        val track = _state.value.current ?: return
        val data = PersistedPlayer(
            track = track,
            playlistName = _state.value.currentPlaylist,
            index = index,
            positionMs = _state.value.positionMs,
            repeatMode = _state.value.repeatMode.name,
            shuffle = _state.value.shuffle,
            queue = queue.map { PersistedEntry(it.track, it.playlistName) },
            savedAt = System.currentTimeMillis(),
            contextLabel = _state.value.contextLabel,
        )
        runCatching {
            AppGraph.session.savePlayerState(
                ApiClient.json.encodeToString(PersistedPlayer.serializer(), data),
            )
        }
    }

    private fun restorePersisted() {
        val raw = AppGraph.session.loadPlayerState() ?: return
        val data = runCatching {
            ApiClient.json.decodeFromString(PersistedPlayer.serializer(), raw)
        }.getOrNull() ?: return
        if (data.track.displayTitle.isBlank() && data.queue.isEmpty()) return

        queue = if (data.queue.isNotEmpty()) {
            data.queue.map { QueueEntry(it.track, it.playlistName) }
        } else {
            listOf(QueueEntry(data.track, data.playlistName))
        }
        shuffleOrder = queue.indices.shuffled()
        index = data.index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(
            current = data.track,
            currentPlaylist = data.playlistName,
            positionMs = data.positionMs,
            durationMs = (data.track.durationValue ?: 0).toLong() * 1000L,
            isPlaying = false,
            hasQueue = queue.isNotEmpty(),
            repeatMode = runCatching { RepeatMode.valueOf(data.repeatMode) }.getOrDefault(RepeatMode.OFF),
            shuffle = data.shuffle,
            contextLabel = data.contextLabel,
        )
        pendingSeekMs = data.positionMs
        restoredPending = true
        syncFavoriteState()
    }

    fun setQueue(entries: List<QueueEntry>, startIndex: Int, contextLabel: String? = null) {
        initialize()
        if (entries.isEmpty()) return
        queue = entries
        shuffleOrder = entries.indices.shuffled()
        index = startIndex.coerceIn(entries.indices)
        // An explicit selection must never inherit the saved position of a
        // previously restored track, otherwise the first chosen track gets
        // seeked near its end and appears to not start.
        pendingSeekMs = 0L
        restoredPending = false
        _state.value = _state.value.copy(hasQueue = true, error = null, contextLabel = contextLabel)
        playCurrent()
    }

    fun playSingle(entry: QueueEntry) {
        setQueue(listOf(entry), 0)
    }

    fun favoriteKey(track: Track): String =
        "${track.artist.lowercase().trim()}|${track.title.lowercase().trim()}"

    fun isFavorite(track: Track): Boolean = favoriteKeys.contains(favoriteKey(track))

    fun setFavoriteKeys(keys: Set<String>) {
        favoriteKeys.clear()
        favoriteKeys.addAll(keys)
        syncFavoriteState()
    }

    private fun applyFavoriteKeys(playlists: List<Playlist>) {
        val likes = playlists.firstOrNull { it.isFavorites }?.tracks.orEmpty()
        favoriteKeys.clear()
        favoriteKeys.addAll(likes.map { favoriteKey(it) })
        syncFavoriteState()
    }

    private fun syncFavoriteState() {
        val track = _state.value.current
        val favorite = track != null && favoriteKeys.contains(favoriteKey(track))
        _state.value = _state.value.copy(isFavorite = favorite)
        PlaybackService.updateFavoriteButton(favorite)
    }

    fun toggleCurrentFavorite() {
        val track = _state.value.current ?: return
        val key = favoriteKey(track)
        val willLike = !favoriteKeys.contains(key)
        if (willLike) favoriteKeys.add(key) else favoriteKeys.remove(key)
        syncFavoriteState()
        scope.launch {
            AppGraph.repository.toggleFavorite(track)
                .onSuccess { response ->
                    onPlaylistsChanged?.invoke(response.playlists)
                    applyFavoriteKeys(response.playlists)
                }
                .onFailure {
                    if (willLike) favoriteKeys.remove(key) else favoriteKeys.add(key)
                    syncFavoriteState()
                }
        }
    }

    private fun orderedIndices(): List<Int> =
        if (_state.value.shuffle) shuffleOrder else queue.indices.toList()

    private fun currentEntry(): QueueEntry? = queue.getOrNull(index)

    private fun playCurrent() {
        val entry = currentEntry() ?: return
        // Stop the current playback immediately; the next track only starts once it
        // has been prepared (e.g. downloaded by the server from its source).
        PlaybackService.stop()
        _state.value = _state.value.copy(
            current = entry.track,
            currentPlaylist = entry.playlistName,
            positionMs = 0L,
            durationMs = 0L,
            isPlaying = false,
            isPreparing = true,
            preparingLabel = "Подготовка трека…",
            preparingProgress = -1f,
            error = null,
        )
        syncFavoriteState()
        playJob?.cancel()
        playJob = scope.launch {
            try {
                val media = resolveMedia(entry)
                if (media == null) {
                    _state.value = _state.value.copy(
                        isPreparing = false,
                        error = NetErrors.offlineMessage(AppGraph.isOnline()),
                    )
                    return@launch
                }
                currentMediaId = media.id
                PlaybackService.play(
                    PlaybackService.PlayableMedia(
                        url = ApiClient.absoluteUrl(media.audioUrl),
                        title = media.title.ifBlank { entry.track.displayTitle },
                        artist = media.artist.ifBlank { entry.track.artist },
                        artwork = ApiClient.absoluteUrl(
                            entry.track.coverOrEmpty.ifBlank { media.coverUrl ?: "" },
                        ),
                        mediaId = media.id,
                    )
                )
                if (pendingSeekMs > 0L) {
                    PlaybackService.seekTo(pendingSeekMs)
                    _state.value = _state.value.copy(positionMs = pendingSeekMs)
                }
                pendingSeekMs = 0L
                restoredPending = false
                // Keep the loading indicator until the player really starts (or errors),
                // otherwise the ring disappears before audio is actually ready.
                var waited = 0
                while (waited < 20_000 &&
                    _state.value.error == null &&
                    !PlaybackService.isPlaying() &&
                    PlaybackService.duration() <= 0L
                ) {
                    delay(250)
                    waited += 250
                }
                val failed = _state.value.error == null &&
                    !PlaybackService.isPlaying() &&
                    PlaybackService.duration() <= 0L
                val diag = buildString {
                    append("Не удалось воспроизвести. service=")
                    append(PlaybackService.instance != null)
                    append(" dur=")
                    append(PlaybackService.duration())
                    append(" playing=")
                    append(PlaybackService.isPlaying())
                    append(" url=")
                    append(ApiClient.absoluteUrl(media.audioUrl))
                }
                _state.value = _state.value.copy(
                    isPreparing = false,
                    error = _state.value.error ?: if (failed) diag else null,
                    durationMs = (media.durationSeconds ?: entry.track.durationValue ?: 0).toLong() * 1000L,
                )
                persist()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isPreparing = false,
                    error = NetErrors.message(t, AppGraph.isOnline()),
                )
            }
        }
    }

    private suspend fun resolveMedia(entry: QueueEntry): MediaTrack? {
        var localPath = entry.track.localPath
        if (localPath.isNullOrBlank()) {
            val context = AppGraph.appContext
            if (context != null) {
                val found = withContext(Dispatchers.IO) { runCatching { LocalLibrary.find(context, entry.track) }.getOrNull() }
                localPath = found?.localPath
                Log.d("MusicFind", "resolveMedia: local lookup for '${entry.track.artist} - ${entry.track.title}' -> ${localPath ?: "not found"}")
            }
        }
        if (!localPath.isNullOrBlank()) {
            val exists = File(localPath).exists()
            Log.d("MusicFind", "resolveMedia: using LOCAL $localPath (exists=$exists)")
            return MediaTrack(
                id = localPath,
                audioUrl = android.net.Uri.fromFile(File(localPath)).toString(),
                title = entry.track.displayTitle,
                artist = entry.track.artist,
                src = "OFFLINE",
                coverUrl = entry.track.coverOrEmpty.ifBlank { null },
                durationSeconds = entry.track.durationValue,
            )
        }
        Log.d("MusicFind", "resolveMedia: using SERVER for '${entry.track.artist} - ${entry.track.title}' (online=${AppGraph.isOnline()})")
        return AppGraph.repository.resolveMedia(entry.track, entry.playlistName) { progress ->
            _state.value = _state.value.copy(
                isPreparing = true,
                preparingLabel = "Загрузка ${progress.toInt()}%",
                preparingProgress = progress.toFloat(),
            )
        }
    }

    private fun handleEnded() {
        when (_state.value.repeatMode) {
            RepeatMode.ONE -> {
                PlaybackService.seekTo(0)
                PlaybackService.resume()
            }
            else -> next(auto = true)
        }
    }

    fun toggle() {
        initialize()
        val service = PlaybackService.instance
        if (_state.value.current == null) {
            if (queue.isNotEmpty()) playCurrent()
            return
        }
        if (restoredPending || service == null) {
            playCurrent()
            return
        }
        if (PlaybackService.isPlaying()) {
            PlaybackService.pause()
        } else if (PlaybackService.duration() <= 0L && PlaybackService.position() == 0L) {
            // Nothing is loaded in the player (e.g. media was cleared): (re)prepare.
            playCurrent()
            return
        } else {
            PlaybackService.resume()
        }
        _state.value = _state.value.copy(isPlaying = PlaybackService.isPlaying())
        persist()
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        restoredPending = false
        pendingSeekMs = 0L
        val order = orderedIndices()
        val position = order.indexOf(index)
        if (position == -1) return
        if (position < order.size - 1) {
            index = order[position + 1]
            playCurrent()
        } else {
            if (auto && _state.value.repeatMode == RepeatMode.OFF) {
                PlaybackService.stop()
                _state.value = _state.value.copy(isPlaying = false, positionMs = 0L)
                releaseCurrent()
                persist()
            } else {
                index = order.first()
                playCurrent()
            }
        }
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (PlaybackService.position() > 3000) {
            PlaybackService.seekTo(0)
            persist()
            return
        }
        restoredPending = false
        pendingSeekMs = 0L
        val order = orderedIndices()
        val position = order.indexOf(index)
        if (position > 0) {
            index = order[position - 1]
            playCurrent()
        } else {
            index = order.last()
            playCurrent()
        }
    }

    fun seekTo(positionMs: Long) {
        PlaybackService.seekTo(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs)
        persist()
    }

    /** Seek forward/backward by [seconds]; the result is clamped to the track bounds. */
    fun seekBy(seconds: Int) {
        val duration = _state.value.durationMs
        val target = (_state.value.positionMs + seconds * 1000L).coerceAtLeast(0L)
        val clamped = if (duration > 0) target.coerceAtMost(duration) else target
        PlaybackService.seekTo(clamped)
        _state.value = _state.value.copy(positionMs = clamped)
        persist()
    }

    fun toggleShuffle() {
        val shuffle = !_state.value.shuffle
        _state.value = _state.value.copy(shuffle = shuffle)
        if (shuffle) shuffleOrder = queue.indices.shuffled()
        persist()
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _state.value = _state.value.copy(repeatMode = next)
        persist()
    }

    fun close() {
        PlaybackService.stop()
        releaseCurrent()
        _state.value = PlayerUiState()
        queue = emptyList()
        index = -1
        restoredPending = false
        pendingSeekMs = 0L
        AppGraph.session.clearPlayerState()
    }

    private fun releaseCurrent() {
        val mediaId = currentMediaId ?: return
        currentMediaId = null
        if (mediaId.startsWith("/")) return
        scope.launch {
            runCatching { AppGraph.repository.releaseMedia(mediaId) }
        }
    }

    fun consumeError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }
}
