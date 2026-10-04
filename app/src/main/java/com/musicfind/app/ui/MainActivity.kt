package com.musicfind.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicfind.app.AppGraph
import com.musicfind.app.service.PlaybackService
import com.musicfind.app.ui.screens.HomeScreen
import com.musicfind.app.ui.screens.LoginScreen
import com.musicfind.app.ui.screens.SetupScreen
import com.musicfind.app.ui.theme.MusicFindTheme
import com.musicfind.app.ui.theme.parseHexColor

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppGraph.init(this)
        startPlaybackService()
        requestNotificationPermission()

        setContent {
            val theme by SettingsController.theme.collectAsState()
            val accent by SettingsController.accent.collectAsState()
            val dark = theme != "light"
            val view = LocalView.current
            SideEffect {
                val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MusicFindTheme(darkTheme = dark, accent = parseHexColor(accent)) {
                AppRoot()
            }
        }
    }

    private fun startPlaybackService() {
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppRoot() {
    var serverUrl by remember { mutableStateOf(AppGraph.session.serverUrl) }
    var loggedIn by remember { mutableStateOf(AppGraph.session.isLoggedIn) }

    fun clearCookies() {
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
    }

    when {
        serverUrl.isBlank() -> SetupScreen(
            initialUrl = serverUrl,
            onSubmit = { url ->
                AppGraph.updateServer(url)
                serverUrl = AppGraph.session.serverUrl
            },
        )

        !loggedIn -> LoginScreen(
            serverUrl = serverUrl,
            onAuthenticated = { token ->
                AppGraph.updateToken(token)
                loggedIn = true
            },
            onChangeServer = {
                clearCookies()
                AppGraph.logout()
                serverUrl = ""
            },
        )

        else -> {
            val vm: AppViewModel = viewModel()
            HomeScreen(
                vm = vm,
                onLogout = {
                    clearCookies()
                    vm.logout()
                    loggedIn = false
                },
            )
        }
    }
}
