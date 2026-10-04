package com.musicfind.app.util

import android.content.Context
import com.musicfind.app.R
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Formatters {

    fun formatTime(seconds: Number?): String {
        val total = (seconds?.toDouble() ?: 0.0).toInt().coerceAtLeast(0)
        val mins = total / 60
        val secs = total % 60
        return "%d:%02d".format(mins, secs)
    }

    fun initials(vararg values: String?): String {
        for (value in values) {
            firstLetter(value)?.let { return it.toString() }
        }
        return "?"
    }

    /** First letters of the values, e.g. title + artist -> Loc.s(R.string.na). Falls back to "?". */
    fun initials2(vararg values: String?): String {
        val letters = StringBuilder()
        for (value in values) {
            firstLetter(value)?.let { letters.append(it) }
            if (letters.length >= 2) break
        }
        return if (letters.isEmpty()) "?" else letters.toString()
    }

    private fun firstLetter(value: String?): Char? {
        val text = value?.trim().orEmpty()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) {
                return String(Character.toChars(codePoint)).uppercase().firstOrNull()
            }
            index += Character.charCount(codePoint)
        }
        return null
    }

    fun sourceLabel(src: String?): String = when (src?.uppercase()) {
        "SC" -> "SoundCloud"
        "YM" -> "YouTube Music"
        "YT" -> "YouTube"
        null, "" -> ""
        else -> src!!
    }

    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
