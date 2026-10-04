package com.musicfind.app.data

import android.content.Context
import android.content.SharedPreferences

class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mf_session", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER, normalize(value)).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME, "dark") ?: "dark"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var accentColor: String
        get() = prefs.getString(KEY_ACCENT, "#22C55E") ?: "#22C55E"
        set(value) = prefs.edit().putString(KEY_ACCENT, value).apply()

    var showBitrate: Boolean
        get() = prefs.getBoolean(KEY_BITRATE, false)
        set(value) = prefs.edit().putBoolean(KEY_BITRATE, value).apply()

    var seekStepSeconds: Int
        get() = prefs.getInt(KEY_SEEK_STEP, 5).coerceIn(1, 120)
        set(value) = prefs.edit().putInt(KEY_SEEK_STEP, value.coerceIn(1, 120)).apply()

    val isLoggedIn: Boolean
        get() = token.isNotBlank() && serverUrl.isNotBlank()

    fun setPlaylistCover(name: String, fileUri: String) {
        prefs.edit().putString(COVER_PREFIX + name, fileUri).apply()
    }

    fun playlistCover(name: String): String? = prefs.getString(COVER_PREFIX + name, null)

    fun playlistCovers(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(COVER_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(COVER_PREFIX) to (it.value as String) }

    fun savePlayerState(json: String) {
        prefs.edit().putString(KEY_PLAYER_STATE, json).apply()
    }

    fun loadPlayerState(): String? = prefs.getString(KEY_PLAYER_STATE, null)

    fun clearPlayerState() {
        prefs.edit().remove(KEY_PLAYER_STATE).apply()
    }

    fun clearAuth() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun normalize(url: String): String = url.trim().removeSuffix("/")

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_BITRATE = "show_bitrate"
        private const val KEY_SEEK_STEP = "seek_step_seconds"
        private const val COVER_PREFIX = "cover_"
        private const val KEY_PLAYER_STATE = "player_state"
    }
}
