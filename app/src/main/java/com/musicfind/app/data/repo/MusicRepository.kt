package com.musicfind.app.data.repo

import com.musicfind.app.data.model.AppVersionResponse
import com.musicfind.app.data.model.BootstrapResponse
import com.musicfind.app.data.model.DownloadStatusResponse
import com.musicfind.app.data.model.GenericResponse
import com.musicfind.app.data.model.LeaderboardResponse
import com.musicfind.app.data.model.LyricsResponse
import com.musicfind.app.data.model.MediaTrack
import com.musicfind.app.data.model.PlayTrackResponse
import com.musicfind.app.data.model.PlaylistsResponse
import com.musicfind.app.data.model.ProfileResponse
import com.musicfind.app.data.model.FavoriteArtistsResponse
import com.musicfind.app.data.model.RecognizeResponse
import com.musicfind.app.data.model.SearchResponse
import com.musicfind.app.data.model.StatisticsResponse
import com.musicfind.app.data.model.Track
import com.musicfind.app.data.remote.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MultipartBody

class MusicRepository {

    private val api = ApiClient.service

    private fun trackJson(track: Track): JsonObject =
        ApiClient.json.encodeToJsonElement(track).jsonObject

    suspend fun bootstrap(): Result<BootstrapResponse> = safe { api.bootstrap() }

    suspend fun search(query: String): Result<SearchResponse> = safe { api.search(query) }

    suspend fun recognize(file: MultipartBody.Part): Result<RecognizeResponse> = safe {
        api.recognize(file)
    }

    suspend fun favoriteArtists(): Result<FavoriteArtistsResponse> = safe {
        api.favoriteArtists()
    }

    suspend fun toggleFavoriteArtist(name: String, avatarUrl: String): Result<FavoriteArtistsResponse> = safe {
        api.toggleFavoriteArtist(buildJsonObject {
            put("name", name)
            put("avatar_url", avatarUrl)
        })
    }

    suspend fun statistics(): Result<StatisticsResponse> = safe { api.statistics() }

    suspend fun leaderboard(): Result<LeaderboardResponse> = safe { api.leaderboard() }

    suspend fun playlists(): Result<PlaylistsResponse> = safe { api.playlists() }

    suspend fun createPlaylist(name: String): Result<PlaylistsResponse> = safe {
        api.createPlaylist(buildJsonObject { put("playlist_name", name) })
    }

    suspend fun updatePlaylistCover(name: String, coverUrl: String): Result<PlaylistsResponse> = safe {
        api.updatePlaylistCover(buildJsonObject {
            put("playlist_name", name)
            put("cover_url", coverUrl)
        })
    }

    suspend fun playTrack(track: Track): Result<PlayTrackResponse> = safe {
        api.playTrack(buildJsonObject { put("track", trackJson(track)) })
    }

    suspend fun playPlaylistTrack(playlistName: String, track: Track): Result<PlayTrackResponse> = safe {
        api.playPlaylistTrack(buildJsonObject {
            put("playlist_name", playlistName)
            put("track", trackJson(track))
        })
    }

    suspend fun downloadStatus(id: String): Result<DownloadStatusResponse> = safe {
        api.downloadStatus(id)
    }

    suspend fun cancelDownload(id: String): Result<GenericResponse> = safe {
        api.cancelDownload(buildJsonObject { put("id", id) })
    }

    suspend fun addTrackToPlaylist(playlistName: String, track: Track): Result<PlaylistsResponse> = safe {
        api.addTrackToPlaylist(buildJsonObject {
            put("playlist_name", playlistName)
            put("track", trackJson(track))
        })
    }

    suspend fun removePlaylistTrack(playlistName: String, track: Track): Result<PlaylistsResponse> = safe {
        api.removePlaylistTrack(buildJsonObject {
            put("playlist_name", playlistName)
            put("track", trackJson(track))
        })
    }

    suspend fun renamePlaylistTrack(
        playlistName: String,
        track: Track,
        title: String,
        artist: String,
    ): Result<PlaylistsResponse> = safe {
        api.renameTrack(buildJsonObject {
            put("playlist_name", playlistName)
            put("track", trackJson(track))
            put("title", title)
            put("artist", artist)
        })
    }

    suspend fun removePlaylist(name: String): Result<PlaylistsResponse> = safe {
        api.removePlaylist(buildJsonObject { put("playlist_name", name) })
    }

    suspend fun toggleFavorite(track: Track): Result<PlaylistsResponse> = safe {
        api.toggleFavorite(buildJsonObject { put("track", trackJson(track)) })
    }

    suspend fun releaseMedia(mediaId: String): Result<GenericResponse> = safe {
        api.releaseMedia(buildJsonObject { put("media_id", mediaId) })
    }

    suspend fun activityPing(): Result<GenericResponse> = safe { api.activityPing() }

    suspend fun lyrics(artist: String, title: String): Result<LyricsResponse> = safe {
        api.lyrics(artist, title)
    }

    suspend fun appVersion(): Result<AppVersionResponse> = safe { api.appVersion() }

    suspend fun accountProfile(): Result<ProfileResponse> = safe { api.accountProfile() }

    suspend fun updateProfile(displayName: String?, avatarUrl: String?): Result<ProfileResponse> = safe {
        api.updateProfile(buildJsonObject {
            if (displayName != null) put("display_name", displayName)
            if (avatarUrl != null) put("avatar_url", avatarUrl)
        })
    }

    suspend fun uploadAvatar(file: MultipartBody.Part): Result<ProfileResponse> = safe {
        api.uploadAvatar(file)
    }

    suspend fun unlinkTelegram(): Result<GenericResponse> = safe { api.unlinkTelegram() }

    suspend fun resolveMedia(
        track: Track,
        playlistName: String? = null,
        onProgress: ((Double) -> Unit)? = null,
        onDownloadId: ((String) -> Unit)? = null,
    ): MediaTrack? {
        // Both single tracks and playlist tracks may need a server-side download
        // from SoundCloud/YouTube, so poll the status until it is ready and play.
        val response = if (playlistName != null) {
            playPlaylistTrack(playlistName, track).getOrNull()
        } else {
            playTrack(track).getOrNull()
        } ?: return null
        if (response.status == "ready" && response.track != null) {
            return response.track
        }
        val downloadId = response.downloadId ?: return null
        onDownloadId?.invoke(downloadId)
        var attempts = 0
        while (attempts < 150) {
            attempts++
            val status = downloadStatus(downloadId).getOrNull()
            if (status == null) {
                kotlinx.coroutines.delay(800)
                continue
            }
            when (status.status) {
                "ready" -> return status.track
                "error" -> return null
                else -> {
                    onProgress?.invoke(status.progress)
                    kotlinx.coroutines.delay(800)
                }
            }
        }
        return null
    }

    private inline fun <T> safe(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
