package com.musicfind.app.ui.screens

import androidx.activity.compose.BackHandler
import com.musicfind.app.R
import com.musicfind.app.util.Loc
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musicfind.app.data.model.Track
import com.musicfind.app.ui.AppViewModel
import com.musicfind.app.ui.components.FullPlayer
import com.musicfind.app.ui.components.GlassRow
import com.musicfind.app.ui.components.GradientBackground
import com.musicfind.app.ui.components.LiquidGlassSurface
import com.musicfind.app.ui.components.MfSnackbarHost
import com.musicfind.app.ui.components.MfSnackbarVisuals
import com.musicfind.app.ui.components.PlayerBar
import com.musicfind.app.ui.components.UserAvatar
import com.musicfind.app.ui.components.backdropSource
import com.musicfind.app.ui.components.rememberBackdropState
import com.musicfind.app.ui.theme.LocalMfColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class HomeTab {
    Search,
    Playlists,
    Statistics,
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onLogout: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val player by vm.playerState.collectAsState()
    val colors = LocalMfColors.current

    var tab by remember { mutableStateOf(HomeTab.Search) }
    var settingsOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var fullPlayerOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val sheetHeightPx = with(density) { config.screenHeightDp.dp.toPx() }.coerceAtLeast(1f)
    val sheetScope = rememberCoroutineScope()
    val sheet = remember { Animatable(0f) }
    var sheetVisible by remember { mutableStateOf(false) }

    fun openPlayer() {
        sheetVisible = true
        sheetScope.launch {
            sheet.animateTo(1f, tween(300))
            fullPlayerOpen = true
        }
    }

    fun closePlayer() {
        sheetScope.launch {
            sheet.animateTo(0f, tween(280))
            fullPlayerOpen = false
            sheetVisible = false
        }
    }
    var pickerTrack by remember { mutableStateOf<Track?>(null) }
    var coverPlaylist by remember { mutableStateOf<String?>(null) }
    var openPlaylist by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val backdrop = rememberBackdropState()
    var refreshing by remember { mutableStateOf(false) }
    val tabHistory = remember { androidx.compose.runtime.mutableStateListOf<HomeTab>() }

    fun selectTab(next: HomeTab) {
        if (next == tab) return
        tabHistory.add(tab)
        openPlaylist = null
        tab = next
    }

    fun goBackTab(): Boolean {
        val previous = tabHistory.removeLastOrNull() ?: return false
        tab = previous
        return true
    }

    fun refreshCurrentTab() {
        refreshing = true
        vm.refreshOffline()
        when (tab) {
            HomeTab.Search -> vm.search()
            HomeTab.Playlists -> vm.load()
            HomeTab.Statistics -> vm.loadStatistics()
        }
    }

    BackHandler(enabled = fullPlayerOpen || openPlaylist != null || tabHistory.isNotEmpty()) {
        when {
            fullPlayerOpen -> closePlayer()
            openPlaylist != null -> openPlaylist = null
            else -> goBackTab()
        }
    }

    androidx.compose.runtime.LaunchedEffect(player.current) {
        if (player.current == null && sheetVisible) {
            sheet.snapTo(0f)
            fullPlayerOpen = false
            sheetVisible = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(ui.loading, ui.statisticsLoading, ui.searching, refreshing) {
        if (refreshing && !ui.loading && !ui.statisticsLoading && !ui.searching) {
            refreshing = false
        }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val name = coverPlaylist
        if (uri != null && name != null) {
            vm.setLocalPlaylistCover(name, uri)
        }
        coverPlaylist = null
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        vm.load()
    }
    // Also load once when HomeScreen enters the composition (e.g. right after login),
    // so a freshly logged-in account never shows the previous account's data.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.load()
    }

    androidx.compose.runtime.LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbarHostState.showSnackbar(MfSnackbarVisuals(it, isError = false))
            vm.setMessage(null)
        }
    }
    androidx.compose.runtime.LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbarHostState.showSnackbar(
                MfSnackbarVisuals(it, isError = true, duration = androidx.compose.material3.SnackbarDuration.Long),
            )
            vm.setError(null)
        }
    }
    androidx.compose.runtime.LaunchedEffect(player.error) {
        player.error?.let {
            snackbarHostState.showSnackbar(
                MfSnackbarVisuals(it, isError = true, duration = androidx.compose.material3.SnackbarDuration.Long),
            )
            com.musicfind.app.player.PlayerController.consumeError()
        }
    }

    GradientBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                HomeTopBar(
                    profileName = listOfNotNull(
                        ui.profile?.displayName?.takeIf { it.isNotBlank() },
                        ui.profile?.firstName?.takeIf { it.isNotBlank() },
                        ui.profile?.username?.takeIf { it.isNotBlank() },
                    ).firstOrNull() ?: Loc.s(R.string.profile),
                    profilePhoto = ui.profile?.photoUrl?.takeIf { it.isNotBlank() }
                        ?: ui.account?.avatarUrl?.takeIf { it.isNotBlank() },
                    onProfile = { profileOpen = true },
                    onSettings = { settingsOpen = true },
                )

                if (!ui.online || !ui.serverReachable) {
                    OfflineBanner(
                        title = if (!ui.online) Loc.s(R.string.no_internet) else Loc.s(R.string.server_connection_error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        onClick = { selectTab(HomeTab.Playlists) },
                    )
                }

                Box(
                    Modifier
                        .weight(1f)
                        .pointerInput(Unit) {
                            val threshold = 80.dp.toPx()
                            var total = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, dragAmount ->
                                    total += dragAmount
                                },
                                onDragEnd = {
                                    if (total > threshold) {
                                        when {
                                            fullPlayerOpen -> closePlayer()
                                            openPlaylist != null -> openPlaylist = null
                                            else -> goBackTab()
                                        }
                                    }
                                    total = 0f
                                },
                                onDragCancel = { total = 0f },
                            )
                        },
                ) {
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { refreshCurrentTab() },
                        modifier = Modifier
                            .fillMaxSize()
                            .backdropSource(backdrop),
                    ) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                val enter = slideInHorizontally(tween(280)) { width ->
                                    if (forward) width / 5 else -width / 5
                                } + fadeIn(tween(240))
                                val exit = slideOutHorizontally(tween(280)) { width ->
                                    if (forward) -width / 5 else width / 5
                                } + fadeOut(tween(160))
                                enter togetherWith exit
                            },
                            modifier = Modifier.fillMaxSize(),
                            label = "tabs",
                        ) { current ->
                            when (current) {
                                HomeTab.Search -> SearchScreen(
                                    vm = vm,
                                    onOpenPlaylistPicker = { pickerTrack = it },
                                )
                                HomeTab.Playlists -> PlaylistsScreen(
                                    vm = vm,
                                    openPlaylist = openPlaylist,
                                    onOpenPlaylist = { openPlaylist = it },
                                    onOpenPlaylistPicker = { pickerTrack = it },
                                    onEditCover = { coverPlaylist = it },
                                )
                                HomeTab.Statistics -> StatisticsScreen(vm)
                            }
                        }
                    }

                    if (player.current != null) {
                        PlayerBar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)
                                .graphicsLayer {
                                    val p = sheet.value
                                    alpha = (1f - p).coerceIn(0f, 1f)
                                    translationY = -p * 24.dp.toPx()
                                },
                            onExpand = { openPlayer() },
                            onDragDelta = { d ->
                                sheetVisible = true
                                sheetScope.launch {
                                    sheet.snapTo((sheet.value - d / sheetHeightPx).coerceIn(0f, 1f))
                                }
                            },
                            onDragEnd = { v ->
                                if (v < -600f || sheet.value > 0.4f) openPlayer() else closePlayer()
                            },
                            backdrop = backdrop,
                        )
                    }
                }

                BottomNavIsland(
                    selected = tab,
                    onSelect = { selectTab(it) },
                    backdrop = backdrop,
                )
            }

            MfSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(12.dp),
            )

            if (player.current != null && sheetVisible) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = (1f - sheet.value) * sheetHeightPx },
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            },
                    )
                    FullPlayer(
                        modifier = Modifier.fillMaxSize(),
                        onClose = { closePlayer() },
                        onAddToPlaylist = { pickerTrack = it },
                        onDragDelta = { d ->
                            sheetScope.launch {
                                sheet.snapTo((sheet.value - d / sheetHeightPx).coerceIn(0f, 1f))
                            }
                        },
                        onDragEnd = { v ->
                            if (v > 600f || sheet.value < 0.6f) closePlayer() else openPlayer()
                        },
                    )
                }
            }
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            onDismiss = { settingsOpen = false },
            onLogout = {
                settingsOpen = false
                onLogout()
            },
            onServerChanged = { vm.load() },
        )
    }

    if (profileOpen) {
        ProfileDialog(
            vm = vm,
            onDismiss = { profileOpen = false },
        )
    }

    pickerTrack?.let { track ->
        PlaylistPickerDialog(
            playlists = ui.playlists.filter { !it.isFavorites },
            track = track,
            onDismiss = { pickerTrack = null },
            onSelect = { name ->
                vm.addToPlaylist(name, track)
                pickerTrack = null
            },
            onCreate = { name ->
                vm.createPlaylist(name) { created ->
                    vm.addToPlaylist(created, track)
                }
                pickerTrack = null
            },
        )
    }

    coverPlaylist?.let { name ->
        CoverDialog(
            onDismiss = { coverPlaylist = null },
            onSubmit = { url ->
                vm.updatePlaylistCover(name, url)
                coverPlaylist = null
            },
            onPickGallery = { coverPicker.launch("image/*") },
        )
    }
}

@Composable
private fun OfflineBanner(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalMfColors.current
    GlassRow(modifier = modifier, onClick = onClick) {
        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = colors.accent)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = colors.text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                Loc.s(R.string.offline_hint),
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HomeTopBar(    profileName: String,
    profilePhoto: String?,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = LocalMfColors.current
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("MusicFind", style = MaterialTheme.typography.titleLarge, color = colors.text)
            Text(Loc.s(R.string.mood_tagline), style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
        androidx.compose.material3.IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = Loc.s(R.string.settings), tint = colors.accent)
        }
        Spacer(Modifier.size(4.dp))
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            UserAvatar(photoUrl = profilePhoto, name = profileName, size = 40.dp)
        }
    }
}

@Composable
private fun BottomNavIsland(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    backdrop: com.musicfind.app.ui.components.BackdropState? = null,
) {
    val colors = LocalMfColors.current
    val tabs = HomeTab.entries
    val currentSelected by androidx.compose.runtime.rememberUpdatedState(selected)
    val currentOnSelect by androidx.compose.runtime.rememberUpdatedState(onSelect)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val indicator = remember { androidx.compose.animation.core.Animatable(selected.ordinal.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(selected, dragging) {
        if (!dragging) {
            indicator.animateTo(selected.ordinal.toFloat(), tween(260))
        }
    }

    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
        cornerRadius = 26.dp,
        elevation = 12.dp,
        backgroundColor = colors.island,
        backdrop = backdrop,
        blurRadius = 22.dp,
        frosted = false,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .pointerInput(tabs.size) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / tabs.size
                        val index = (offset.x / slot).toInt().coerceIn(0, tabs.size - 1)
                        if (tabs[index] != currentSelected) currentOnSelect(tabs[index])
                    }
                }
                .pointerInput(tabs.size) {
                    fun fraction(x: Float): Float =
                        (x / size.width.toFloat() * tabs.size - 0.5f).coerceIn(0f, tabs.size - 1f)

                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            val value = fraction(offset.x)
                            scope.launch { indicator.snapTo(value) }
                        },
                        onHorizontalDrag = { change, _ ->
                            val value = fraction(change.position.x)
                            scope.launch { indicator.snapTo(value) }
                        },
                        onDragEnd = {
                            dragging = false
                            val index = indicator.value.roundToInt().coerceIn(0, tabs.size - 1)
                            if (tabs[index] != currentSelected) currentOnSelect(tabs[index])
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { indicator.animateTo(currentSelected.ordinal.toFloat(), tween(200)) }
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((maxWidth.toPx() / tabs.size * indicator.value).roundToInt(), 0) }
                    .width(maxWidth / tabs.size)
                    .fillMaxHeight()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.accentSoft),
            )
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { item ->
                    val active = item == selected
                    val tint = if (active) colors.accent else colors.muted
                    val label = when (item) {
                        HomeTab.Search -> Loc.s(R.string.search)
                        HomeTab.Playlists -> Loc.s(R.string.playlists)
                        HomeTab.Statistics -> Loc.s(R.string.statistics)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            when (item) {
                                HomeTab.Search -> Icons.Filled.Search
                                HomeTab.Playlists -> Icons.Filled.LibraryMusic
                                HomeTab.Statistics -> Icons.Filled.BarChart
                            },
                            contentDescription = label,
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            label,
                            color = tint,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
