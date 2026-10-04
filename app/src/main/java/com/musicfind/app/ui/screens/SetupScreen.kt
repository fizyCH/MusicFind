package com.musicfind.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import com.musicfind.app.R
import com.musicfind.app.util.Loc
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.musicfind.app.ui.components.GlassSurface
import com.musicfind.app.ui.components.GradientBackground
import com.musicfind.app.ui.theme.LocalMfColors

@Composable
fun SetupScreen(
    initialUrl: String,
    onSubmit: (String) -> Unit,
) {
    val colors = LocalMfColors.current
    var value by remember { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    GradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("MusicFind", style = MaterialTheme.typography.headlineMedium, color = colors.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    Loc.s(R.string.setup_server_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Loc.s(R.string.server_address)) },
                    placeholder = { Text(Loc.s(R.string.server_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val normalized = normalizeServer(value)
                        if (normalized == null) {
                            error = Loc.s(R.string.need_valid_url)
                        } else {
                            onSubmit(normalized)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(Loc.s(R.string.continue_label))
                }
            }
        }
    }
}

private fun normalizeServer(input: String): String? {
    var trimmed = input.trim()
    if (trimmed.isBlank()) return null
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        trimmed = "https://$trimmed"
    }
    trimmed = trimmed.removeSuffix("/")
    // Accept a full login link too: the app appends "/login" itself.
    if (trimmed.endsWith("/login")) {
        trimmed = trimmed.removeSuffix("/login").removeSuffix("/")
    }
    return try {
        val uri = java.net.URI(trimmed)
        when {
            uri.host.isNullOrBlank() -> null
            uri.scheme != "http" && uri.scheme != "https" -> null
            else -> trimmed
        }
    } catch (_: Exception) {
        null
    }
}
