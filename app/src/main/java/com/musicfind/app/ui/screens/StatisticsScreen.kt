package com.musicfind.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.musicfind.app.data.model.LeaderboardEntry
import com.musicfind.app.data.model.TopArtist
import com.musicfind.app.data.remote.ApiClient
import com.musicfind.app.ui.AppViewModel
import com.musicfind.app.ui.components.GlassSurface
import com.musicfind.app.ui.components.LiquidGlassSurface
import com.musicfind.app.ui.components.SectionTitle
import com.musicfind.app.ui.theme.LocalMfColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val GoldColor = Color(0xFFFFD54A)
private val SilverColor = Color(0xFFE5E7EB)
private val BronzeColor = Color(0xFFCD7F32)

@Composable
fun StatisticsScreen(vm: AppViewModel) {
    val ui by vm.ui.collectAsState()
    val player by vm.playerState.collectAsState()
    val colors = LocalMfColors.current
    var chartOpen by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { vm.loadStatistics() }
    LaunchedEffect(tab) { if (tab == 1) vm.loadLeaderboard() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = if (player.current != null) 150.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatsTabs(selected = tab, onSelect = { tab = it })
        }

        item {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = slideInHorizontally(tween(300)) { w -> if (forward) w else -w } +
                        fadeIn(tween(220))
                    val exit = slideOutHorizontally(tween(300)) { w -> if (forward) -w else w } +
                        fadeOut(tween(180))
                    enter togetherWith exit
                },
                label = "statsTab",
                modifier = Modifier.fillMaxWidth(),
            ) { current ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (current == 0) {
                        val stats = ui.statistics
                        if (stats == null) {
                            Text(
                                if (ui.statisticsLoading) "Загрузка…" else "Пока нет данных",
                                color = colors.muted,
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatCard("Время", "${stats.totalActiveHours} ч", Modifier.weight(1f))
                                StatCard("Дней", "${stats.totalActiveDays}", Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatCard("Плейлистов", "${stats.playlistCount}", Modifier.weight(1f))
                                StatCard("Треков", "${stats.trackCount}", Modifier.weight(1f))
                            }
                            if (stats.topArtists.isNotEmpty()) {
                                SectionTitle(
                                    title = "Топ исполнителей",
                                    modifier = Modifier.fillMaxWidth(),
                                    trailing = {
                                        IconButton(onClick = { chartOpen = true }) {
                                            Icon(
                                                Icons.Filled.MoreVert,
                                                contentDescription = "График",
                                                tint = colors.muted,
                                            )
                                        }
                                    },
                                )
                                stats.topArtists.take(3).forEach { artist ->
                                    ArtistRow(artist.name, artist.hours, artist.avatarUrl)
                                }
                            }
                            if (ui.favoriteArtists.isNotEmpty()) {
                                SectionTitle("Любимые исполнители", modifier = Modifier.fillMaxWidth())
                                ui.favoriteArtists.forEach { artist ->
                                    ArtistRow(artist.name, artist.hours, artist.avatarUrl)
                                }
                            }
                        }
                    } else {
                        SectionTitle(
                            title = "Лучшие пользователи",
                            subtitle = "По прослушанному времени",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        UsersLeaderboard(ui.leaderboard, ui.leaderboardLoading)
                        val rank = ui.leaderboardMe?.rank ?: 0
                        Text(
                            "Ваше место: " + if (rank > 0) "#$rank" else "—",
                            color = colors.text,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        if (ui.leaderboard.size > 3) {
                            ui.leaderboard.drop(3).forEach { entry ->
                                LeaderboardRow(entry)
                            }
                        }
                    }
                }
            }
        }
    }

    if (chartOpen) {
        ArtistChartDialog(
            artists = ui.statistics?.topArtists.orEmpty(),
            onDismiss = { chartOpen = false },
        )
    }
}

@Composable
private fun StatsTabs(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalMfColors.current
    val labels = listOf("Моя статистика", "Статистика пользователей")
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val indicator = remember { Animatable(selected.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(selected, dragging) {
        if (!dragging) {
            indicator.animateTo(selected.toFloat(), tween(260))
        }
    }

    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        elevation = 0.dp,
        backgroundColor = colors.island,
        frosted = false,
        contentPadding = PaddingValues(6.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .pointerInput(labels.size) {
                    detectTapGestures { offset ->
                        val slot = size.width.toFloat() / labels.size
                        val index = (offset.x / slot).toInt().coerceIn(0, labels.size - 1)
                        if (index != currentSelected) currentOnSelect(index)
                    }
                }
                .pointerInput(labels.size) {
                    fun fraction(x: Float): Float =
                        (x / size.width.toFloat() * labels.size - 0.5f).coerceIn(0f, labels.size - 1f)

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
                            val index = indicator.value.roundToInt().coerceIn(0, labels.size - 1)
                            if (index != currentSelected) currentOnSelect(index)
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { indicator.animateTo(currentSelected.toFloat(), tween(200)) }
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .offset { IntOffset((maxWidth.toPx() / labels.size * indicator.value).roundToInt(), 0) }
                    .width(maxWidth / labels.size)
                    .fillMaxHeight()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.accentSoft),
            )
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                labels.forEachIndexed { index, label ->
                    val active = index == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (active) colors.accent else colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistRow(name: String, hours: Double, avatarUrl: String) {
    val colors = LocalMfColors.current
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(colors.glassStrong),
                contentAlignment = Alignment.Center,
            ) {
                val avatar = ApiClient.absoluteUrl(avatarUrl)
                if (avatar.isNotBlank()) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(name.take(1).uppercase(), color = colors.text)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = colors.text, fontWeight = FontWeight.SemiBold)
                if (hours > 0.0) {
                    Text(
                        "$hours ч",
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsersLeaderboard(entries: List<LeaderboardEntry>, loading: Boolean) {
    val colors = LocalMfColors.current
    val first = entries.getOrNull(0)
    val second = entries.getOrNull(1)
    val third = entries.getOrNull(2)
    GlassSurface(Modifier.fillMaxWidth()) {
        if (first == null) {
            Text(if (loading) "Загрузка…" else "Пока нет данных", color = colors.muted)
        } else {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                PodiumColumn(second, place = 2, barHeight = 74.dp, medal = SilverColor)
                PodiumColumn(first, place = 1, barHeight = 104.dp, medal = GoldColor)
                PodiumColumn(third, place = 3, barHeight = 56.dp, medal = BronzeColor)
            }
        }
    }
}

@Composable
private fun PodiumColumn(
    entry: LeaderboardEntry?,
    place: Int,
    barHeight: Dp,
    medal: Color,
) {
    val colors = LocalMfColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(100.dp),
    ) {
        Box(
            Modifier
                .size(if (place == 1) 64.dp else 52.dp)
                .clip(CircleShape)
                .background(colors.glassStrong)
                .border(2.dp, medal, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val avatar = ApiClient.absoluteUrl(entry?.avatarUrl.orEmpty())
            if (avatar.isNotBlank()) {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Text(
                    (entry?.name ?: "—").take(1).uppercase(),
                    color = colors.text,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry?.name ?: "—",
            color = colors.text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${entry?.hours ?: 0.0} ч",
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(medal),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                "$place",
                color = Color(0xFF04121B),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry) {
    val colors = LocalMfColors.current
    val rankColor = when (entry.rank) {
        1 -> GoldColor
        2 -> SilverColor
        3 -> BronzeColor
        else -> colors.muted
    }
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(rankColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${entry.rank}",
                    color = Color(0xFF04121B),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                entry.name,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.hours} ч",
                color = colors.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ArtistChartDialog(
    artists: List<TopArtist>,
    onDismiss: () -> Unit,
) {
    val colors = LocalMfColors.current
    val maxHours = artists.maxOfOrNull { it.hours }?.takeIf { it > 0.0 } ?: 1.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Топ исполнителей") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                artists.forEachIndexed { index, artist ->
                    val rankColor = when (index) {
                        0 -> GoldColor
                        1 -> SilverColor
                        2 -> BronzeColor
                        else -> colors.accent
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(rankColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${index + 1}",
                                    color = Color(0xFF04121B),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                artist.name,
                                color = colors.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${artist.hours} ч",
                                color = colors.muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.glass),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth((artist.hours / maxHours).coerceIn(0.0, 1.0).toFloat())
                                    .fillMaxHeight()
                                    .background(rankColor),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        },
    )
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalMfColors.current
    GlassSurface(modifier) {
        Text(
            label.uppercase(),
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = colors.text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
