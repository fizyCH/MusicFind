package com.musicfind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.musicfind.app.data.remote.ApiClient
import com.musicfind.app.ui.theme.LocalMfColors
import com.musicfind.app.util.Formatters

/**
 * Circular user avatar.
 *
 * Shows the uploaded picture when it exists, otherwise the user's initials.
 * The initials are always drawn underneath the picture, so the slot is never
 * empty and never blinks while the image is loading or fails.
 */
@Composable
fun UserAvatar(
    photoUrl: String? = null,
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val colors = LocalMfColors.current
    val url = ApiClient.absoluteUrl(photoUrl.orEmpty())

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            Formatters.initials(name),
            color = colors.text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
