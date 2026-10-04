package com.musicfind.app.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Maps low-level network failures to friendly, user-facing messages. */
object NetErrors {

    const val NO_INTERNET = "Нет интернета. Доступны скачанные треки"
    const val NO_SERVER = "Ошибка соединения с сервером. Слушайте скачанные треки"

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
        !online -> NO_INTERNET
        isConnectionError(t) -> NO_SERVER
        else -> t.message?.takeIf { it.isNotBlank() } ?: NO_SERVER
    }

    fun offlineMessage(online: Boolean): String = if (!online) NO_INTERNET else NO_SERVER
}
