package com.musicfind.app.ui

import androidx.compose.ui.graphics.Color
import com.musicfind.app.AppGraph
import com.musicfind.app.ui.theme.MfPalette
import com.musicfind.app.ui.theme.parseHexColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SettingsController {

    private val _theme = MutableStateFlow(AppGraph.session.themeMode)
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _accent = MutableStateFlow(AppGraph.session.accentColor)
    val accent: StateFlow<String> = _accent.asStateFlow()

    private val _bitrate = MutableStateFlow(AppGraph.session.showBitrate)
    val bitrate: StateFlow<Boolean> = _bitrate.asStateFlow()

    private val _seekStep = MutableStateFlow(AppGraph.session.seekStepSeconds)
    val seekStep: StateFlow<Int> = _seekStep.asStateFlow()

    private val _language = MutableStateFlow(AppGraph.session.language)
    val language: StateFlow<String> = _language.asStateFlow()

    fun setTheme(value: String) {
        AppGraph.session.themeMode = value
        _theme.value = value
    }

    fun setAccent(value: String) {
        AppGraph.session.accentColor = value
        _accent.value = value
    }

    fun setBitrate(value: Boolean) {
        AppGraph.session.showBitrate = value
        _bitrate.value = value
    }

    fun setSeekStep(value: Int) {
        AppGraph.session.seekStepSeconds = value
        _seekStep.value = value
    }

    fun setLanguage(value: String) {
        AppGraph.session.language = value
        _language.value = value
    }

    fun isLight(): Boolean = _theme.value == "light"

    fun accentColor(): Color = parseHexColor(_accent.value, MfPalette.DefaultAccent)
}
