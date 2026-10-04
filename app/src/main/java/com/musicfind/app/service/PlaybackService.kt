package com.musicfind.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicfind.app.AppGraph
import com.musicfind.app.R
import com.musicfind.app.ui.MainActivity
import com.musicfind.app.util.ArtworkGenerator
import com.musicfind.app.util.Formatters

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("MusicFind", "PlaybackService.onCreate")
        createChannel()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
                setHandleAudioBecomingNoisy(true)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) onEnded?.invoke()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onPlayingChanged?.invoke(isPlaying)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("MusicFind", "ExoPlayer error: ${error.errorCodeName} ${error.message}", error)
                        onError?.invoke(error)
                    }
                })
            }
        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, QueueForwardingPlayer(player))
            .setSessionActivity(sessionActivity)
            .setCallback(object : MediaSession.Callback {
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == ACTION_FAVORITE) {
                        onToggleFavorite?.invoke()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
        applyFavoritePreferences(favoriteState)
        // If playback was requested before the service finished starting, play it now.
        pendingMedia?.let { media ->
            pendingMedia = null
            playInternal(media)
        }
    }

    fun applyFavoritePreferences(favorite: Boolean) {
        val session = mediaSession ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                session.setMediaButtonPreferences(listOf(favoriteButton(favorite)))
            } catch (_: Exception) {
            }
        }
    }

    private fun favoriteButton(favorite: Boolean): CommandButton {
        val command = SessionCommand(ACTION_FAVORITE, Bundle.EMPTY)
        return CommandButton.Builder()
            .setDisplayName(if (favorite) "Убрать из избранного" else "Нравится")
            .setIconResId(if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
            .setSessionCommand(command)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        return if (result == START_NOT_STICKY) START_STICKY else result
    }

    /**
     * Media3 only runs in the foreground when a MediaController is connected to the
     * session. This app drives the player directly, so Media3 would never promote the
     * service to the foreground and Android would kill playback in the background.
     * We therefore manage the foreground service (and its minimal silent
     * notification) ourselves and keep Media3's notification manager out of the way.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // Managed manually via ensureForeground().
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        foregroundStarted = true
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.e("MusicFind", "startForeground failed", it) }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MusicFind")
            .setContentText("Воспроизведение")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Воспроизведение",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Фоновое воспроизведение музыки"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private class QueueForwardingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToNext() {
            val callback = onNext
            if (callback != null) callback() else super.seekToNext()
        }

        override fun seekToNextMediaItem() {
            val callback = onNext
            if (callback != null) callback() else super.seekToNextMediaItem()
        }

        override fun seekToPrevious() {
            val callback = onPrevious
            if (callback != null) callback() else super.seekToPrevious()
        }

        override fun seekToPreviousMediaItem() {
            val callback = onPrevious
            if (callback != null) callback() else super.seekToPreviousMediaItem()
        }

        override fun hasNextMediaItem(): Boolean = onNext != null || super.hasNextMediaItem()

        override fun hasPreviousMediaItem(): Boolean = onPrevious != null || super.hasPreviousMediaItem()

        override fun getAvailableCommands(): Player.Commands {
            val builder = super.getAvailableCommands().buildUpon()
            if (onNext != null) {
                builder.add(Player.COMMAND_SEEK_TO_NEXT)
                builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            if (onPrevious != null) {
                builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
                builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
            return builder.build()
        }
    }

    private fun playInternal(media: PlayableMedia) {
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.artist)
        val artwork = media.artwork?.takeIf { it.isNotBlank() }
        if (artwork != null) {
            metadata.setArtworkUri(Uri.parse(artwork))
        } else {
            // No cover: hand the Android player a generated two-letter artwork.
            val argb = com.musicfind.app.ui.SettingsController.accentColor().toArgb()
            val letters = Formatters.initials2(media.title, media.artist)
            val foreground = if (isLightColor(argb)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            ArtworkGenerator.generate(letters, argb, foreground)?.let { bytes ->
                metadata.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }
        val item = MediaItem.Builder()
            .setUri(media.url)
            .setMediaId(media.mediaId ?: media.url)
            .setMediaMetadata(metadata.build())
            .apply {
                if (media.url.startsWith("file:") && media.url.lowercase().endsWith(".mp3")) {
                    setMimeType(MimeTypes.AUDIO_MPEG)
                }
            }
            .build()
        Log.d("MusicFind", "playInternal: url=${media.url} mime=${item.localConfiguration?.mimeType}")
        player.setMediaItem(item)
        player.prepare()
        ensureForeground()
        player.play()
    }

    private fun isLightColor(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return luminance > 0.6
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player.mediaItemCount == 0 || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        player.release()
        mediaSession = null
        instance = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    data class PlayableMedia(
        val url: String,
        val title: String,
        val artist: String,
        val artwork: String?,
        val mediaId: String?,
    )

    companion object {
        const val CHANNEL_ID = "musicfind_playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_FAVORITE = "com.musicfind.app.action.TOGGLE_FAVORITE"

        @Volatile
        private var favoriteState = false

        @Volatile
        var onToggleFavorite: (() -> Unit)? = null

        @Volatile
        var onError: ((Throwable) -> Unit)? = null

        @Volatile
        private var pendingMedia: PlayableMedia? = null

        @Volatile
        var instance: PlaybackService? = null
            private set

        @Volatile
        var onEnded: (() -> Unit)? = null

        @Volatile
        var onPlayingChanged: ((Boolean) -> Unit)? = null

        @Volatile
        var onNext: (() -> Unit)? = null

        @Volatile
        var onPrevious: (() -> Unit)? = null

        fun play(media: PlayableMedia) {
            val service = instance
            Log.d("MusicFind", "PlaybackService.play instance=${service != null} url=${media.url}")
            if (service != null) {
                service.playInternal(media)
            } else {
                // Service not ready yet: remember the request and (re)start it.
                pendingMedia = media
                val context = AppGraph.appContext
                if (context == null) {
                    Log.e("MusicFind", "appContext is null; cannot start PlaybackService")
                } else {
                    runCatching {
                        context.startService(Intent(context, PlaybackService::class.java))
                    }.onFailure { Log.e("MusicFind", "startService failed", it) }
                }
            }
        }

        fun updateFavoriteButton(favorite: Boolean) {
            favoriteState = favorite
            instance?.applyFavoritePreferences(favorite)
        }

        fun pause() {
            instance?.player?.pause()
        }

        fun resume() {
            instance?.player?.play()
        }

        fun stop() {
            instance?.player?.stop()
            instance?.player?.clearMediaItems()
        }

        fun seekTo(positionMs: Long) {
            instance?.player?.seekTo(positionMs)
        }

        fun isPlaying(): Boolean = instance?.player?.isPlaying == true

        fun position(): Long = instance?.player?.currentPosition?.coerceAtLeast(0L) ?: 0L

        fun duration(): Long = instance?.player?.duration?.takeIf { it > 0 } ?: 0L
    }
}
