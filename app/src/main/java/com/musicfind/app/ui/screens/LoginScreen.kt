package com.musicfind.app.ui.screens

import android.annotation.SuppressLint
import com.musicfind.app.R
import com.musicfind.app.util.Loc
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.musicfind.app.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    serverUrl: String,
    onAuthenticated: (String) -> Unit,
    onChangeServer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val verifying = remember { AtomicBoolean(false) }
    var showProgress by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = settings.userAgentString + " MusicFindAndroid"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val pageUrl = url ?: return
                            val path = runCatching { URI(pageUrl).path ?: "" }.getOrDefault("")
                            if (path == "/login" || path == "/createacc") return
                            val cookies = CookieManager.getInstance().getCookie(pageUrl) ?: return
                            val token = cookies.split(';')
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("session_token=") }
                                ?.substringAfter('=')
                                ?.takeIf { it.isNotBlank() }
                                ?: return
                            if (!verifying.compareAndSet(false, true)) return
                            showProgress = true
                            scope.launch {
                                val valid = withContext(Dispatchers.IO) { verifyToken(serverUrl, token) }
                                if (valid) {
                                    CookieManager.getInstance().flush()
                                    onAuthenticated(token)
                                } else {
                                    verifying.set(false)
                                    showProgress = false
                                }
                            }
                        }
                    }
                    loadUrl("$serverUrl/login")
                }
            },
        )
        if (showProgress) {
            Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        androidx.compose.material3.TextButton(
            onClick = onChangeServer,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(8.dp),
        ) {
            androidx.compose.material3.Text(Loc.s(R.string.change_server), color = androidx.compose.ui.graphics.Color(0xFF64748B))
        }
    }
}

private fun verifyToken(serverUrl: String, token: String): Boolean {
    return try {
        val request = Request.Builder()
            .url("${serverUrl.trimEnd('/')}/api/bootstrap")
            .header("X-Session-Token", token)
            .build()
        ApiClient.okHttp.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    } catch (_: Exception) {
        false
    }
}
