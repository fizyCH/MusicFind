package com.musicfind.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val title: String = "",
    val artist: String = "",
    @SerialName("full_title") val fullTitle: String = "",
    val url: String = "",
    val src: String = "",
    val cover: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @Serializable(with = LenientNullableInt::class)
    @SerialName("bitrate_kbps") val bitrateKbps: Int? = null,
    @Serializable(with = LenientNullableInt::class)
    val duration: Int? = null,
    @Serializable(with = LenientNullableInt::class)
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("file_name") val fileName: String? = null,
    val id: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    val reason: String? = null,
    val artistName: String? = null,
    @SerialName("local_path") val localPath: String? = null,
) {
    val displayTitle: String get() = title.ifBlank { fullTitle.ifBlank { "Unknown" } }
    val coverOrEmpty: String get() = cover.ifBlank { coverUrl }
    val durationValue: Int? get() = duration ?: durationSeconds
}

@Serializable
data class Profile(
    @Serializable(with = LenientNullableLong::class)
    val id: Long? = null,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val username: String = "",
    @SerialName("photo_url") val photoUrl: String = "",
    @SerialName("is_account") val isAccount: Boolean = false,
    @SerialName("display_name") val displayName: String? = null,
    @Serializable(with = LenientNullableLong::class)
    @SerialName("telegram_id") val telegramId: Long? = null,
    @SerialName("account_username") val accountUsername: String? = null,
    @SerialName("profile_featured") val profileFeatured: List<Track> = emptyList(),
)

@Serializable
data class Playlist(
    val name: String = "",
    @Serializable(with = LenientInt::class)
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("is_favorites") val isFavorites: Boolean = false,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class Account(
    @Serializable(with = LenientNullableLong::class)
    val uid: Long? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @Serializable(with = LenientNullableLong::class)
    @SerialName("telegram_id") val telegramId: Long? = null,
)

@Serializable
data class MediaTrack(
    val id: String = "",
    @SerialName("audio_url") val audioUrl: String = "",
    val title: String = "",
    val artist: String = "",
    val src: String = "",
    @SerialName("cover_url") val coverUrl: String? = null,
    @Serializable(with = LenientNullableInt::class)
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
)

@Serializable
data class RecognizedTrack(
    val title: String = "",
    val artist: String = "",
    val cover: String = "",
    @SerialName("share_url") val shareUrl: String = "",
)

@Serializable
data class LyricsLine(
    @Serializable(with = LenientDouble::class) val time: Double = 0.0,
    val text: String = "",
)

@Serializable
data class TopArtist(
    val key: String = "",
    val name: String = "",
    @Serializable(with = LenientInt::class) val seconds: Int = 0,
    @Serializable(with = LenientDouble::class) val hours: Double = 0.0,
    @SerialName("avatar_url") val avatarUrl: String = "",
)

@Serializable
data class Artist(
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @Serializable(with = LenientInt::class) val seconds: Int = 0,
    @Serializable(with = LenientDouble::class) val hours: Double = 0.0,
)

@Serializable
data class Statistics(
    @Serializable(with = LenientLong::class)
    @SerialName("total_active_seconds") val totalActiveSeconds: Long = 0,
    @Serializable(with = LenientDouble::class)
    @SerialName("total_active_hours") val totalActiveHours: Double = 0.0,
    @Serializable(with = LenientDouble::class)
    @SerialName("total_active_days") val totalActiveDays: Double = 0.0,
    @Serializable(with = LenientInt::class)
    @SerialName("playlist_count") val playlistCount: Int = 0,
    @Serializable(with = LenientInt::class)
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("top_artists") val topArtists: List<TopArtist> = emptyList(),
)

// ---- Responses ----

@Serializable
data class GenericResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class BootstrapResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val profile: Profile? = null,
    val playlists: List<Playlist> = emptyList(),
    val authorized: Boolean = false,
    val account: Account? = null,
    @SerialName("favorite_artists") val favoriteArtists: List<Artist> = emptyList(),
)

@Serializable
data class SearchResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val query: String = "",
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class RecognizeResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val query: String = "",
    val recognized: RecognizedTrack? = null,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class FavoriteArtistsResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    @SerialName("favorite_artists") val favoriteArtists: List<Artist> = emptyList(),
)

@Serializable
data class StatisticsResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val statistics: Statistics? = null,
)

@Serializable
data class LeaderboardEntry(
    val name: String = "",
    val username: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @Serializable(with = LenientInt::class) val seconds: Int = 0,
    @Serializable(with = LenientDouble::class) val hours: Double = 0.0,
    @Serializable(with = LenientInt::class) val rank: Int = 0,
)

@Serializable
data class LeaderboardResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val top: List<LeaderboardEntry> = emptyList(),
    val me: LeaderboardEntry? = null,
)

@Serializable
data class PlaylistsResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    @SerialName("playlist_name") val playlistName: String? = null,
    val playlists: List<Playlist> = emptyList(),
)

@Serializable
data class PlayTrackResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val status: String = "",
    val track: MediaTrack? = null,
    @SerialName("download_id") val downloadId: String? = null,
    @Serializable(with = LenientDouble::class)
    val progress: Double = 0.0,
)

@Serializable
data class DownloadStatusResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val status: String = "",
    @Serializable(with = LenientDouble::class)
    val progress: Double = 0.0,
    val track: MediaTrack? = null,
)

@Serializable
data class LyricsResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val synced: List<LyricsLine>? = null,
    val plain: String? = null,
    val source: String? = null,
)

@Serializable
data class AppVersionResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    @Serializable(with = LenientInt::class)
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("version_name") val versionName: String = "",
    val notes: String = "",
    @Serializable(with = LenientLong::class)
    val size: Long = 0,
    @SerialName("download_url") val downloadUrl: String = "",
)

@Serializable
data class ProfileResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val account: Account? = null,
    @SerialName("is_telegram_profile") val isTelegramProfile: Boolean = false,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
