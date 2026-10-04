package com.musicfind.app.util

import android.content.Context
import com.musicfind.app.R
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.musicfind.app.AppGraph
import com.musicfind.app.BuildConfig
import com.musicfind.app.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val size: Long,
    val downloadUrl: String,
)

/** Over-the-air updates: check the server version and install a downloaded APK. */
object Updater {

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE

    fun currentVersionName(): String = BuildConfig.VERSION_NAME

    suspend fun check(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val response = AppGraph.repository.appVersion().getOrThrow()
            if (!response.ok) throw IllegalStateException(response.message ?: Loc.s(R.string.server_no_version))
            UpdateInfo(
                versionCode = response.versionCode,
                versionName = response.versionName.ifBlank { "—" },
                notes = response.notes,
                size = response.size,
                downloadUrl = ApiClient.absoluteUrl(response.downloadUrl),
            )
        }
    }

    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (info.downloadUrl.isBlank()) throw IllegalStateException(Loc.s(R.string.update_url_missing))
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val target = File(dir, "MusicFind-${info.versionName}.apk")
        val request = Request.Builder().url(info.downloadUrl).build()
        ApiClient.okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException(Loc.s(R.string.empty_response))
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            val progress = (read.toFloat() / total).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) { onProgress(progress) }
                        }
                    }
                }
            }
        }
        if (target.length() < 100_000) {
            target.delete()
            throw IllegalStateException(Loc.s(R.string.update_file_corrupt))
        }
        target
    }

    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
