package com.example.media

data class PtpMediaItem(
    val handle: Int,
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    var filename: String = "",
    var sizeBytes: Long = 0L,
    var format: Int = 0,
    var isMetadataLoaded: Boolean = false
) {
    val displayName: String
        get() = if (filename.isNotBlank()) {
            filename
        } else if (isVideo) {
            "Video_$handle"
        } else if (isAudio) {
            "Audio_$handle"
        } else {
            "Photo_$handle"
        }

    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return ""
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.1f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                else -> String.format("%.0f KB", kb)
            }
        }
}
