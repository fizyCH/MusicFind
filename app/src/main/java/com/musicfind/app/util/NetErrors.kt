package com.musicfind.app.util

import java.io.IOException
import com.musicfind.app.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Maps low-level network failures to friendly, user-facing messages. */
object NetErrors {

    val noInternet: String get() = Loc.s(R.string.no_internet_offline)
    val noServer: String get() = Loc.s(R.string.server_connection_error_offline)

    fun isConnectionError(t: Throwable): Boolean {
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 8) {
            when (current) {
                is UnknownHostException, is ConnectException, is SocketTimeoutException -> return true
                is IOException -> return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    fun message(t: Throwable, online: Boolean): String = when {
        !online -> noInternet
        isConnectionError(t) -> noServer
        else -> t.message?.takeIf { it.isNotBlank() } ?: noServer
    }

    fun offlineMessage(online: Boolean): String = if (!online) noInternet else noServer
}
