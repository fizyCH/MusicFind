package com.musicfind.app.ui.components

import androidx.compose.animation.AnimatedContent
import com.musicfind.app.R
import com.musicfind.app.util.Loc
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import com.musicfind.app.AppGraph
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import com.musicfind.app.data.model.LyricsLine
import com.musicfind.app.data.model.Track
import com.musicfind.app.data.remote.ApiClient
import com.musicfind.app.player.PlayerController
import com.musicfind.app.player.RepeatMode
import com.musicfind.app.ui.theme.LocalMfColors
import com.musicfind.app.ui.theme.MfColors
import com.musicfind.app.util.Formatters
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.IntOffset

@Composable
fun PlayerBar(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: BackdropState? = null,
    onDragDelta: ((Float) -> Unit)? = null,
    onDragEnd: ((Float) -> Unit)? = null,
) {
    val state by PlayerController.state.collectAsState()
    val track = state.current ?: return
    val colors = LocalMfColors.current
    val coverAccent = rememberCoverAccent(track.coverOrEmpty) ?: colors.accent
    val playerAccent = readableAccentColor(coverAccent)
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val horizontalThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }

    var seekValue by remember { mutableFloatStateOf(-1f) }
    var miniSlideDirection by remember { mutableIntStateOf(1) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(0L)
    val progress = when {
        seekValue >= 0f -> seekValue
        duration > 0 -> (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }


    LiquidGlassSurface(
        modifier = modifier.draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { delta ->
                if (onDragDelta != null) onDragDelta.invoke(delta) else dragTotal += delta
            },
            onDragStopped = { velocity ->
                if (onDragEnd != null) {
                    onDragEnd.invoke(velocity)
                } else if (dragTotal < -swipeThreshold) {
                    onExpand()
                }
                dragTotal = 0f
            },
        ),
        cornerRadius = 24.dp,
        elevation = 0.dp,
        // Plain theme color (dark/light), no frosted glass.
        backgroundColor = colors.island,
        backdrop = backdrop,
        blurRadius = 46.dp,
        frosted = false,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        AnimatedContent(
            targetState = track,
            transitionSpec = {
                if (miniSlideDirection >= 0) {
                    (slideInHorizontally(tween(260)) { it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(260)) { -it } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260)) { it } + fadeOut(tween(160)))
                }
            },
            label = "miniTrack",
            modifier = Modifier.pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        total += dragAmount
                    },
                    onDragEnd = {
                        if (total < -horizontalThreshold) {
                            miniSlideDirection = 1
                            PlayerController.next()
                        } else if (total > horizontalThreshold) {
                            miniSlideDirection = -1
                            PlayerController.previous()
                        }
                        total = 0f
                    },
                    onDragCancel = { total = 0f },
                )
            },
        ) { shown ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackCover(shown, size = 46)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        shown.displayTitle,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        if (!state.error.isNullOrBlank()) state.error ?: "" else shown.artist.ifBlank { "Unknown" },
                        color = if (!state.error.isNullOrBlank()) MaterialTheme.colorScheme.error else colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                }
                IconButton(onClick = { PlayerController.toggleCurrentFavorite() }) {
                    Icon(
                        if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = Loc.s(R.string.favorite),
                        tint = if (state.isFavorite) playerAccent else colors.muted,
                    )
                }
                if (state.isPreparing) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        ProgressRing(
                            percent = state.preparingProgress.takeIf { it >= 0f }?.toInt(),
                            diameter = 34.dp,
                            color = playerAccent,
                            trackColor = colors.glassBorder,
                            textColor = playerAccent,
                        )
                    }
                } else {
                    IconButton(onClick = { PlayerController.toggle() }) {
                        AnimatedPlayPauseIcon(
                            isPlaying = state.isPlaying,
                            tint = playerAccent,
                            iconSize = 24.dp,
                        )
                    }
                }
                IconButton(onClick = onExpand) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = Loc.s(R.string.expand), tint = colors.muted)
                }
            }
        }
        MfSeekBar(
            value = progress,
            onValueChange = { seekValue = it },
            onValueChangeFinished = {
                if (duration > 0) PlayerController.seekTo((seekValue * duration).toLong())
                seekValue = -1f
            },
            modifier = Modifier.padding(top = 4.dp),
            accentColor = playerAccent,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                Formatters.formatTime((state.positionMs / 1000).toInt()),
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                if (duration > 0) Formatters.formatTime((duration / 1000).toInt()) else "--:--",
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun colorLuminance(color: androidx.compose.ui.graphics.Color): Float =
    0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

private fun readableAccentColor(accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    if (colorLuminance(accent) < 0.22f) androidx.compose.ui.graphics.Color.White else accent

private fun buttonBackgroundColor(accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    if (colorLuminance(accent) < 0.18f) androidx.compose.ui.graphics.Color.White else accent

private fun onAccentColor(background: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    if (colorLuminance(background) > 0.5f) androidx.compose.ui.graphics.Color(0xFF04121B)
    else androidx.compose.ui.graphics.Color.White

@Composable
private fun rememberCoverAccent(coverUrl: String?): androidx.compose.ui.graphics.Color? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var accent by remember(coverUrl) { mutableStateOf<androidx.compose.ui.graphics.Color?>(null) }
    LaunchedEffect(coverUrl) {
        if (coverUrl.isNullOrBlank()) {
            accent = null
            return@LaunchedEffect
        }
        try {
            val absolute = ApiClient.absoluteUrl(coverUrl)
            val request = coil.request.ImageRequest.Builder(context)
                .data(absolute)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            val drawable = (result as? coil.request.SuccessResult)?.drawable
            if (drawable != null) {
                val bitmap = drawable.toBitmap()
                val palette = Palette.from(bitmap).generate()
                val swatch = palette.vibrantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.darkVibrantSwatch
                    ?: palette.dominantSwatch
                val rgb = swatch?.rgb
                accent = if (rgb != null) androidx.compose.ui.graphics.Color(rgb) else null
            }
        } catch (_: Exception) {
            accent = null
        }
    }
    return accent
}

private data class LyricsUi(
    val lines: List<LyricsLine> = emptyList(),
    val plain: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@Composable
private fun rememberLyrics(track: Track?): LyricsUi {
    var state by remember { mutableStateOf(LyricsUi()) }
    LaunchedEffect(track?.url, track?.artist, track?.title) {
        if (track == null) return@LaunchedEffect
        state = LyricsUi(loading = true)
        AppGraph.repository.lyrics(track.artist, track.displayTitle)
            .onSuccess {
                state = LyricsUi(lines = it.synced.orEmpty(), plain = it.plain, loading = false)
            }
            .onFailure {
                state = LyricsUi(loading = false, error = it.message ?: Loc.s(R.string.lyrics_unavailable))
            }
    }
    return state
}

@Composable
fun FullPlayer(
    onClose: () -> Unit,
    onAddToPlaylist: (Track) -> Unit = {},
    modifier: Modifier = Modifier,
    onDragDelta: ((Float) -> Unit)? = null,
    onDragEnd: ((Float) -> Unit)? = null,
) {
    val state by PlayerController.state.collectAsState()
    val track = state.current ?: return
    val colors = LocalMfColors.current
    val lyrics = rememberLyrics(track)
    val coverAccent = rememberCoverAccent(track.coverOrEmpty) ?: colors.accent
    val playerAccent = readableAccentColor(coverAccent)
    val playButtonBg = buttonBackgroundColor(coverAccent)
    val playButtonIcon = onAccentColor(playButtonBg)
    val hasLyrics = lyrics.lines.isNotEmpty() || !lyrics.plain.isNullOrBlank()
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val horizontalThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }

    var seekValue by remember { mutableFloatStateOf(-1f) }
    var showLyrics by remember { mutableStateOf(false) }
    var slideDirection by remember { mutableIntStateOf(1) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val seekStep by com.musicfind.app.ui.SettingsController.seekStep.collectAsState()
    var seekFeedback by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(seekFeedback) {
        if (seekFeedback != 0) {
            delay(700)
            seekFeedback = 0
        }
    }

    androidx.compose.runtime.LaunchedEffect(hasLyrics) {
        if (!hasLyrics) showLyrics = false
    }

    val duration = state.durationMs.coerceAtLeast(0L)
    val progress = when {
        seekValue >= 0f -> seekValue
        duration > 0 -> (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        // Double tap on the left/right half seeks backward/forward. Placed behind
        // the controls so button taps are not intercepted.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(seekStep) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2f) {
                                PlayerController.seekBy(-seekStep)
                                seekFeedback = -seekStep
                            } else {
                                PlayerController.seekBy(seekStep)
                                seekFeedback = seekStep
                            }
                        },
                    )
                },
        )
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxHeight()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Box(
            Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        if (onDragDelta != null) onDragDelta.invoke(delta) else dragTotal += delta
                    },
                    onDragStopped = { velocity ->
                        if (onDragEnd != null) {
                            onDragEnd.invoke(velocity)
                        } else if (dragTotal > swipeThreshold) {
                            onClose()
                        }
                        dragTotal = 0f
                    },
                ),
        ) {
            val contextLabel = state.contextLabel
            Text(
                if (!contextLabel.isNullOrBlank()) {
                    Loc.s(R.string.now_playing_context, contextLabel)
                } else {
                    Loc.s(R.string.now_playing)
                },
                color = colors.muted,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 96.dp)
                    .basicMarquee(),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = Loc.s(R.string.collapse), tint = colors.muted)
            }
            IconButton(
                onClick = { onAddToPlaylist(track) },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = Loc.s(R.string.to_playlist), tint = colors.muted)
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            total += dragAmount
                        },
                        onDragEnd = {
                            if (total < -horizontalThreshold) {
                                slideDirection = 1
                                PlayerController.next()
                            } else if (total > horizontalThreshold) {
                                slideDirection = -1
                                PlayerController.previous()
                            }
                            total = 0f
                        },
                        onDragCancel = {
                            total = 0f
                        },
                    )
                }
                .pointerInput(seekStep) {
                    // Large tap target: the whole artwork area, split into halves.
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (offset.x < size.width / 2f) {
                                PlayerController.seekBy(-seekStep)
                                seekFeedback = -seekStep
                            } else {
                                PlayerController.seekBy(seekStep)
                                seekFeedback = seekStep
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val side = min(min(maxWidth.value, maxHeight.value), 360f).dp
            AnimatedContent(
                targetState = track,
                transitionSpec = {
                    if (slideDirection >= 0) {
                        (slideInHorizontally(tween(260)) { it } + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(260)) { -it } + fadeOut(tween(160)))
                    } else {
                        (slideInHorizontally(tween(260)) { -it } + fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(260)) { it } + fadeOut(tween(160)))
                    }
                },
                label = "cover-track",
                modifier = Modifier.size(side),
            ) { shown ->
                // Cover fades out while the lyrics fade in, instead of swapping instantly.
                val lyricsAlpha by animateFloatAsState(
                    targetValue = if (showLyrics) 1f else 0f,
                    animationSpec = tween(240),
                    label = "lyricsAlpha",
                )
                val coverAlpha by animateFloatAsState(
                    targetValue = if (showLyrics) 0f else 1f,
                    animationSpec = tween(200),
                    label = "coverAlpha",
                )
                Box(Modifier.fillMaxSize()) {
                    if (coverAlpha > 0.01f) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha }) {
                            PlayerCover(shown, Modifier.fillMaxSize())
                        }
                    }
                    if (lyricsAlpha > 0.01f) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = lyricsAlpha }) {
                            LyricsContent(
                                lyrics = lyrics,
                                positionMs = state.positionMs,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(1.dp, colors.glassBorder, RoundedCornerShape(20.dp))
                                    .background(colors.glass),
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasLyrics) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (showLyrics) colors.accentSoft else colors.glass)
                        .clickable { showLyrics = !showLyrics },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.musicfind.app.R.drawable.ic_lyrics_t),
                        contentDescription = Loc.s(R.string.lyrics),
                        tint = if (showLyrics) playerAccent else colors.text,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    track.displayTitle,
                    color = colors.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().basicMarquee(),
                )
                Text(
                    track.artist.ifBlank { "Unknown" },
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().basicMarquee(),
                )
            }
            IconButton(onClick = { PlayerController.toggleCurrentFavorite() }) {
                Icon(
                    if (state.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = Loc.s(R.string.favorite),
                    tint = if (state.isFavorite) playerAccent else colors.muted,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        if (!state.error.isNullOrBlank()) {
            Text(
                state.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
        MfSeekBar(
            value = progress,
            onValueChange = { seekValue = it },
            onValueChangeFinished = {
                if (duration > 0) PlayerController.seekTo((seekValue * duration).toLong())
                seekValue = -1f
            },
            accentColor = playerAccent,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                Formatters.formatTime((state.positionMs / 1000).toInt()),
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                if (duration > 0) Formatters.formatTime((duration / 1000).toInt()) else "--:--",
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        val repeatSpread = remember { Animatable(0f) }
        var repeatInitialized by remember { mutableStateOf(false) }
        LaunchedEffect(state.repeatMode) {
            if (!repeatInitialized) {
                repeatInitialized = true
            } else {
                repeatSpread.snapTo(0f)
                repeatSpread.animateTo(1f, tween(150))
                repeatSpread.animateTo(0f, tween(240))
            }
        }

        val shuffleSpin = remember { Animatable(0f) }
        var shuffleInitialized by remember { mutableStateOf(false) }
        LaunchedEffect(state.shuffle) {
            if (!shuffleInitialized) {
                shuffleInitialized = true
            } else {
                shuffleSpin.snapTo(0f)
                shuffleSpin.animateTo(1f, tween(160))
                shuffleSpin.animateTo(0f, tween(220))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { PlayerController.toggleShuffle() }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = Loc.s(R.string.shuffle),
                    tint = if (state.shuffle) playerAccent else colors.muted,
                    modifier = Modifier.graphicsLayer {
                        rotationZ = shuffleSpin.value * 28f
                        val scale = 1f + shuffleSpin.value * 0.10f
                        scaleX = scale
                        scaleY = scale
                    },
                )
            }
            IconButton(onClick = { PlayerController.previous() }) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = Loc.s(R.string.back),
                    tint = colors.text,
                    modifier = Modifier.size(36.dp),
                )
            }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(playButtonBg),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isPreparing) {
                    ProgressRing(
                        percent = state.preparingProgress.takeIf { it >= 0f }?.toInt(),
                        diameter = 56.dp,
                        color = playButtonIcon,
                        trackColor = playButtonBg.copy(alpha = 0.35f),
                        textColor = playButtonIcon,
                    )
                } else {
                    IconButton(onClick = { PlayerController.toggle() }) {
                        AnimatedPlayPauseIcon(
                            isPlaying = state.isPlaying,
                            tint = playButtonIcon,
                            iconSize = 32.dp,
                        )
                    }
                }
            }
            IconButton(onClick = { PlayerController.next() }) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = Loc.s(R.string.forward),
                    tint = colors.text,
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { PlayerController.cycleRepeat() }) {
                val icon = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat
                Icon(
                    icon,
                    contentDescription = Loc.s(R.string.repeat),
                    tint = if (state.repeatMode == RepeatMode.OFF) colors.muted else playerAccent,
                    modifier = Modifier.graphicsLayer {
                        val spread = repeatSpread.value
                        scaleX = 1f + spread * 0.45f
                        scaleY = 1f - spread * 0.12f
                    },
                )
            }
        }
        }
        if (seekFeedback != 0) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(colors.glassStrong)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(
                    (if (seekFeedback > 0) "+" else "−") + Loc.s(R.string.seconds_count, kotlin.math.abs(seekFeedback)),
                    color = colors.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ProgressRing(
    percent: Int?,
    diameter: Dp,
    color: Color,
    trackColor: Color,
    textColor: Color,
) {
    val value = percent?.coerceIn(0, 100)
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        if (value != null) {
            CircularProgressIndicator(
                progress = { value / 100f },
                modifier = Modifier.fillMaxSize(),
                color = color,
                trackColor = trackColor,
                strokeWidth = (diameter.value * 0.10f).dp,
            )
            Text(
                "$value%",
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = color,
                trackColor = trackColor,
                strokeWidth = (diameter.value * 0.10f).dp,
            )
        }
    }
}

@Composable
private fun AnimatedPlayPauseIcon(
    isPlaying: Boolean,
    tint: Color,
    iconSize: androidx.compose.ui.unit.Dp,
) {
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            (scaleIn(tween(180), initialScale = 0.55f) + fadeIn(tween(180))) togetherWith
                (scaleOut(tween(180), targetScale = 0.55f) + fadeOut(tween(140)))
        },
        label = "playPause",
    ) { playing ->
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (playing) Loc.s(R.string.pause) else Loc.s(R.string.play),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun PlayerCover(track: Track, modifier: Modifier = Modifier) {
    val colors = LocalMfColors.current
    val cover = ApiClient.absoluteUrl(track.coverOrEmpty)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.glassStrong),
        contentAlignment = Alignment.Center,
    ) {
        if (cover.isNotBlank()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                track.displayTitle.take(1).uppercase(),
                color = colors.text,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun LyricsContent(
    lyrics: LyricsUi,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMfColors.current
    val listState = rememberLazyListState()
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val currentSeconds = positionMs / 1000.0
    // Countdown 3-2-1 only once, right before the first lyric line.
    val startCountdown = lyrics.lines.firstOrNull()?.time?.let { first ->
        val remaining = first - currentSeconds
        if (remaining in 0.001..3.0) kotlin.math.ceil(remaining).toInt().coerceIn(1, 3) else null
    }
    var countdownDetached by remember { mutableStateOf(false) }
    LaunchedEffect(isDragged) {
        if (isDragged) countdownDetached = true
    }
    val countdownActive = startCountdown != null
    LaunchedEffect(countdownActive) {
        if (countdownActive) countdownDetached = false
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            lyrics.loading -> CircularProgressIndicator(color = colors.accent)
            !lyrics.error.isNullOrBlank() -> Text(
                lyrics.error ?: "",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            lyrics.lines.isNotEmpty() -> {
                val activeIndex = lyrics.lines.indexOfLast { currentSeconds >= it.time }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val halfHeight = maxHeight / 2
                    LaunchedEffect(activeIndex, isDragged) {
                        if (!isDragged && activeIndex >= 0) {
                            listState.animateScrollToItem(activeIndex)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = halfHeight,
                            bottom = halfHeight,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        itemsIndexed(lyrics.lines) { index, line ->
                            val active = index == activeIndex
                            val distance = if (activeIndex < 0) 0 else kotlin.math.abs(index - activeIndex)
                            val effectEnabled = !isDragged
                            val targetScale = if (!effectEnabled) {
                                1f
                            } else {
                                when (distance) {
                                    0 -> 1f
                                    1 -> 0.82f
                                    2 -> 0.68f
                                    3 -> 0.58f
                                    else -> 0.52f
                                }
                            }
                            val targetAlpha = if (!effectEnabled) {
                                if (active) 1f else 0.85f
                            } else {
                                when (distance) {
                                    0 -> 1f
                                    1 -> 0.72f
                                    2 -> 0.52f
                                    3 -> 0.40f
                                    else -> 0.30f
                                }
                            }
                            val scale by animateFloatAsState(targetScale, tween(320), label = "lyricScale")
                            val alpha by animateFloatAsState(targetAlpha, tween(320), label = "lyricAlpha")
                            val baseColor = if (active) colors.accent else colors.text
                            val layer = Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                            val seekToLine = Modifier.clickable {
                                PlayerController.seekTo((line.time * 1000.0).toLong())
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (index == 0 && startCountdown != null) {
                                    if (countdownDetached) {
                                        CountdownGlow(startCountdown, colors)
                                    } else {
                                        Spacer(Modifier.height(64.dp))
                                    }
                                }
                                if (line.text.isBlank()) {
                                    Icon(
                                        Icons.Filled.MusicNote,
                                        contentDescription = null,
                                        tint = if (active) colors.accent else colors.muted,
                                        modifier = layer.then(seekToLine).size(18.dp),
                                    )
                                } else {
                                    Text(
                                        line.text,
                                        color = baseColor.copy(alpha = alpha),
                                        fontSize = 20.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        modifier = layer.fillMaxWidth().then(seekToLine),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            !lyrics.plain.isNullOrBlank() -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Text(
                        lyrics.plain ?: "",
                        color = colors.text,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> Text(Loc.s(R.string.lyrics_not_found), color = colors.muted)
        }

        if (startCountdown != null && !countdownDetached) {
            CountdownGlow(
                startCountdown,
                colors,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun CountdownGlow(value: Int, colors: MfColors, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (scaleIn(tween(320), initialScale = 0.4f) + fadeIn(tween(240))) togetherWith
                (scaleOut(tween(240), targetScale = 1.6f) + fadeOut(tween(200)))
        },
        label = "lyricsCountdown",
        modifier = modifier,
    ) { v ->
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(120.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                colors.accent.copy(alpha = 0.30f),
                                colors.accent.copy(alpha = 0.09f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Text(
                "$v",
                color = colors.accent,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Shadow(color = colors.accent, blurRadius = 18f)),
            )
        }
    }
}
