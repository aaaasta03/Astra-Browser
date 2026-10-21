package org.aaaasta03.astrabrowser

import java.io.File
import java.util.Locale

/** Recognizes common direct media resources without mistaking ordinary pages for videos. */
object VideoSource {
    private val extensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

    fun isDirectMediaUrl(raw: String): Boolean {
        val value = raw.trim()
        if (!value.startsWith("https://", true) && !value.startsWith("http://", true)) return false
        val path = runCatching { java.net.URI(value).path.orEmpty() }.getOrDefault("")
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in extensions || value.substringBefore('?').endsWith(".m3u8", true) ||
            value.substringBefore('?').endsWith(".mpd", true)
    }

    fun isLocalMediaFile(file: File): Boolean =
        file.isFile && file.extension.lowercase(Locale.ROOT) in extensions
}
