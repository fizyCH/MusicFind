package com.musicfind.app.data.local

import android.content.Context
import android.os.Environment
import com.musicfind.app.data.model.Track
import org.json.JSONObject
import java.io.File

data class OfflinePlaylist(
    val name: String,
    val tracks: List<Track>,
)

object LocalLibrary {

    private const val ROOT_NAME = "MusicFind"
    private const val META_FILE = ".meta.json"
    private const val DEFAULT_PLAYLIST = "Загрузки"

    fun sanitize(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "track" }

    fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), ROOT_NAME)

    fun playlistDir(context: Context, playlistName: String): File {
        val name = playlistName.ifBlank { DEFAULT_PLAYLIST }
        return File(rootDir(context), sanitize(name))
    }

    fun save(
        context: Context,
        playlistName: String,
        track: Track,
        audioBytes: ByteArray,
        coverBytes: ByteArray?,
    ): Track {
        val dir = playlistDir(context, playlistName).apply { mkdirs() }
        val base = sanitize("${track.artist} - ${track.displayTitle}")
        val audioFile = File(dir, "$base.mp3")
        audioFile.writeBytes(audioBytes)

        var coverRef = track.coverOrEmpty
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            val coverFile = File(dir, "$base.cover.jpg")
            coverFile.writeBytes(coverBytes)
            coverRef = "file://${coverFile.absolutePath}"
        }

        val meta = loadMeta(dir)
        meta.put(
            audioFile.name,
            JSONObject()
                .put("title", track.displayTitle)
                .put("artist", track.artist)
                .put("cover", coverRef)
                .put("duration", track.durationValue ?: JSONObject.NULL),
        )
        saveMeta(dir, meta)

        return Track(
            title = track.displayTitle,
            artist = track.artist,
            fullTitle = "${track.artist} - ${track.displayTitle}",
            src = "OFFLINE",
            cover = coverRef,
            duration = track.durationValue,
            durationSeconds = track.durationValue,
            fileName = audioFile.name,
            localPath = audioFile.absolutePath,
        )
    }

    /** Delete every downloaded copy of a track (matched by path, file name or "Artist - Title"). */
    fun deleteTrack(context: Context, track: Track): Boolean {
        val root = rootDir(context)
        val dirs = root.listFiles { file -> file.isDirectory } ?: return false
        val expectedName = "${sanitize("${track.artist} - ${track.displayTitle}")}.mp3"
        var removed = false
        for (dir in dirs) {
            val candidates = buildList {
                track.localPath?.let { add(File(it)) }
                track.fileName?.let { add(File(dir, it)) }
                add(File(dir, expectedName))
            }
            for (candidate in candidates) {
                if (!candidate.exists()) continue
                if (candidate.parentFile?.canonicalPath != dir.canonicalPath) continue
                if (candidate.delete()) {
                    removed = true
                    File(dir, "${candidate.nameWithoutExtension}.cover.jpg")
                        .takeIf { it.exists() }
                        ?.delete()
                    val meta = loadMeta(dir)
                    meta.remove(candidate.name)
                    saveMeta(dir, meta)
                }
            }
        }
        return removed
    }

    /** Delete a whole downloaded playlist folder. */
    fun deletePlaylist(context: Context, playlistName: String): Boolean {
        val dir = playlistDir(context, playlistName)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    private fun normKey(artist: String, title: String): String =
        "${artist.lowercase().trim()}|${title.lowercase().trim()}"

    private fun normTitle(title: String): String =
        title.lowercase().trim()
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Find a downloaded copy of [track] anywhere in the offline library. */
    fun find(context: Context, track: Track): Track? {
        val targetKey = normKey(track.artist, track.title)
        val targetTitle = normTitle(track.title)
        var loose: Track? = null
        for (playlist in scan(context)) {
            for (candidate in playlist.tracks) {
                if (candidate.localPath.isNullOrBlank()) continue
                if (!File(candidate.localPath).exists()) continue
                if (normKey(candidate.artist, candidate.title) == targetKey) return candidate
                if (loose == null && normTitle(candidate.title) == targetTitle) loose = candidate
            }
        }
        return loose
    }

    fun scan(context: Context): List<OfflinePlaylist> {        val root = rootDir(context)
        if (!root.exists()) return emptyList()
        val dirs = root.listFiles { file -> file.isDirectory }?.sortedBy { it.name.lowercase() } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val meta = loadMeta(dir)
            val files = dir.listFiles { file -> file.extension.equals("mp3", true) }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            if (files.isEmpty()) return@mapNotNull null
            val tracks = files.map { file ->
                val info = meta.optJSONObject(file.name)
                val title = info?.optString("title").orEmpty().ifBlank { file.nameWithoutExtension }
                val artist = info?.optString("artist").orEmpty()
                val cover = info?.optString("cover").orEmpty()
                val duration = if (info != null && info.has("duration") && !info.isNull("duration")) {
                    info.optInt("duration")
                } else {
                    null
                }
                Track(
                    title = title,
                    artist = artist,
                    fullTitle = if (artist.isBlank()) title else "$artist - $title",
                    src = "OFFLINE",
                    cover = cover,
                    duration = duration,
                    durationSeconds = duration,
                    fileName = file.name,
                    localPath = file.absolutePath,
                )
            }
            OfflinePlaylist(name = dir.name, tracks = tracks)
        }
    }

    private fun metaFile(dir: File) = File(dir, META_FILE)

    private fun loadMeta(dir: File): JSONObject {
        val file = metaFile(dir)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveMeta(dir: File, meta: JSONObject) {
        try {
            metaFile(dir).writeText(meta.toString())
        } catch (_: Exception) {
        }
    }
}
