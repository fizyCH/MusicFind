package com.musicfind.app

import android.app.Application
import android.content.Context
import com.musicfind.app.data.SessionStore
import com.musicfind.app.data.remote.ApiClient
import com.musicfind.app.data.repo.MusicRepository
import com.musicfind.app.util.Formatters

class MusicFindApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        com.musicfind.app.util.LocaleHelper.apply(this, AppGraph.session.language)
    }
}

object AppGraph {
    lateinit var session: SessionStore
        private set
    lateinit var repository: MusicRepository
        private set

    @Volatile
    var appContext: Context? = null
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        if (::session.isInitialized) return
        session = SessionStore(context.applicationContext)
        repository = MusicRepository()
        ApiClient.serverUrl = session.serverUrl
        ApiClient.token = session.token
    }

    fun isOnline(): Boolean {
        val context = appContext ?: return true
        return Formatters.isOnline(context)
    }

    fun updateServer(url: String) {
        session.serverUrl = url
        ApiClient.serverUrl = session.serverUrl
    }

    fun updateToken(token: String) {
        session.token = token
        ApiClient.token = token
    }

    fun logout() {
        session.clearAuth()
        ApiClient.token = ""
    }
}
