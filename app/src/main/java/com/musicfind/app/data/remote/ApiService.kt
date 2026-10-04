package com.musicfind.app.data.remote

import com.musicfind.app.data.model.AppVersionResponse
import com.musicfind.app.data.model.BootstrapResponse
import com.musicfind.app.data.model.DownloadStatusResponse
import com.musicfind.app.data.model.FavoriteArtistsResponse
import com.musicfind.app.data.model.GenericResponse
import com.musicfind.app.data.model.LeaderboardResponse
import com.musicfind.app.data.model.LyricsResponse
import com.musicfind.app.data.model.PlayTrackResponse
import com.musicfind.app.data.model.PlaylistsResponse
import com.musicfind.app.data.model.ProfileResponse
import com.musicfind.app.data.model.RecognizeResponse
import com.musicfind.app.data.model.SearchResponse
import com.musicfind.app.data.model.StatisticsResponse
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface ApiService {

    @GET("api/bootstrap")
    suspend fun bootstrap(): BootstrapResponse

    @GET("api/search")
    suspend fun search(@Query("q") query: String): SearchResponse

    @Multipart
    @POST("api/recognize-track")
    suspend fun recognize(@Part file: MultipartBody.Part): RecognizeResponse

    @GET("api/artists/favorite")
    suspend fun favoriteArtists(): FavoriteArtistsResponse

    @POST("api/artists/favorite")
    suspend fun toggleFavoriteArtist(@Body body: JsonObject): FavoriteArtistsResponse

    @GET("api/statistics")
    suspend fun statistics(): StatisticsResponse

    @GET("api/leaderboard")
    suspend fun leaderboard(): LeaderboardResponse

    @GET("api/playlists")
    suspend fun playlists(): PlaylistsResponse

    @POST("api/create-playlist")
    suspend fun createPlaylist(@Body body: JsonObject): PlaylistsResponse

    @POST("api/playlist-cover")
    suspend fun updatePlaylistCover(@Body body: JsonObject): PlaylistsResponse

    @POST("api/play-track")
    suspend fun playTrack(@Body body: JsonObject): PlayTrackResponse

    @POST("api/play-playlist-track")
    suspend fun playPlaylistTrack(@Body body: JsonObject): PlayTrackResponse

    @GET("api/download-status")
    suspend fun downloadStatus(@Query("id") id: String): DownloadStatusResponse

    @POST("api/cancel-download")
    suspend fun cancelDownload(@Body body: JsonObject): GenericResponse

    @POST("api/add-track-to-playlist")
    suspend fun addTrackToPlaylist(@Body body: JsonObject): PlaylistsResponse

    @POST("api/remove-playlist-track")
    suspend fun removePlaylistTrack(@Body body: JsonObject): PlaylistsResponse

    @POST("api/rename-track")
    suspend fun renameTrack(@Body body: JsonObject): PlaylistsResponse

    @POST("api/remove-playlist")
    suspend fun removePlaylist(@Body body: JsonObject): PlaylistsResponse

    @POST("api/toggle-favorite")
    suspend fun toggleFavorite(@Body body: JsonObject): PlaylistsResponse

    @POST("api/release-media")
    suspend fun releaseMedia(@Body body: JsonObject): GenericResponse

    @POST("api/activity-ping")
    suspend fun activityPing(@Body body: JsonObject = JsonObject(emptyMap())): GenericResponse

    @GET("api/lyrics")
    suspend fun lyrics(
        @Query("artist") artist: String,
        @Query("title") title: String,
    ): LyricsResponse

    @GET("api/app/version")
    suspend fun appVersion(): AppVersionResponse

    @GET("api/account/profile")
    suspend fun accountProfile(): ProfileResponse

    @POST("api/account/profile/update")
    suspend fun updateProfile(@Body body: JsonObject): ProfileResponse

    @Multipart
    @POST("api/account/avatar")
    suspend fun uploadAvatar(@Part file: MultipartBody.Part): ProfileResponse

    @POST("api/account/unlink-telegram")
    suspend fun unlinkTelegram(@Body body: JsonObject = JsonObject(emptyMap())): GenericResponse

    @POST("api/account/featured")
    suspend fun featuredToggle(@Body body: JsonObject): GenericResponse

    @POST("api/account/login")
    suspend fun accountLogin(@Body body: JsonObject): GenericResponse

    @POST("api/account/register")
    suspend fun accountRegister(@Body body: JsonObject): GenericResponse
}
