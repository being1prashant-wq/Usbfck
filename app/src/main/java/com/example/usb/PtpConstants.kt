package com.example.usb

object PtpConstants {
    const val TAG = "DirectUSB-PTP"

    // Container Types
    const val CONTAINER_TYPE_UNDEFINED: Short = 0
    const val CONTAINER_TYPE_COMMAND: Short = 1
    const val CONTAINER_TYPE_DATA: Short = 2
    const val CONTAINER_TYPE_RESPONSE: Short = 3
    const val CONTAINER_TYPE_EVENT: Short = 4

    // Header size in bytes
    const val HEADER_SIZE = 12

    // Operations
    const val OPERATION_GET_DEVICE_INFO = 0x1001
    const val OPERATION_OPEN_SESSION = 0x1002
    const val OPERATION_CLOSE_SESSION = 0x1003
    const val OPERATION_GET_STORAGE_IDS = 0x1004
    const val OPERATION_GET_STORAGE_INFO = 0x1005
    const val OPERATION_GET_NUM_OBJECTS = 0x1006
    const val OPERATION_GET_OBJECT_HANDLES = 0x1007
    const val OPERATION_GET_OBJECT_INFO = 0x1008
    const val OPERATION_GET_OBJECT = 0x1009
    const val OPERATION_GET_THUMB = 0x100A
    const val OPERATION_GET_PARTIAL_OBJECT = 0x101B
    const val OPERATION_GET_PARTIAL_OBJECT_64 = 0x95C1

    // Response Codes
    const val RESPONSE_OK = 0x2001
    const val RESPONSE_GENERAL_ERROR = 0x2002
    const val RESPONSE_SESSION_NOT_OPEN = 0x2003
    const val RESPONSE_INVALID_TRANSACTION_ID = 0x2004
    const val RESPONSE_OPERATION_NOT_SUPPORTED = 0x2005
    const val RESPONSE_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RESPONSE_INCOMPLETE_TRANSFER = 0x2007
    const val RESPONSE_INVALID_STORAGE_ID = 0x2008
    const val RESPONSE_INVALID_OBJECT_HANDLE = 0x2009
    const val RESPONSE_ACCESS_DENIED = 0x200F
    const val RESPONSE_NO_THUMBNAIL_PRESENT = 0x2010
    const val RESPONSE_SPECIFICATION_BY_FORMAT_UNSUPPORTED = 0x2014
    const val RESPONSE_DEVICE_BUSY = 0x2019
    const val RESPONSE_SESSION_ALREADY_OPEN = 0x201E

    // Special IDs
    const val STORAGE_ALL = -1 // 0xFFFFFFFF
    const val FORMAT_ALL = 0x00000000
    const val PARENT_ROOT = 0x00000000
    const val PARENT_ALL = -1 // 0xFFFFFFFF

    // Formats - Generic
    const val FORMAT_UNDEFINED = 0x3000
    const val FORMAT_ASSOCIATION = 0x3001 // Directory / Folder

    // Formats - Images
    const val FORMAT_EXIF_JPEG = 0x3801
    const val FORMAT_TIFF_EP = 0x3802
    const val FORMAT_BMP = 0x3804
    const val FORMAT_GIF = 0x3807
    const val FORMAT_JFIF = 0x3808
    const val FORMAT_PNG = 0x380B
    const val FORMAT_TIFF = 0x380D
    const val FORMAT_JP2 = 0xB881
    const val FORMAT_HEIF = 0xB802
    const val FORMAT_WEBP = 0xB803

    // Formats - Audio
    const val FORMAT_WAV = 0x3008
    const val FORMAT_MP3 = 0x3009
    const val FORMAT_AIFF = 0x3007
    const val FORMAT_UNDEFINED_AUDIO = 0xB900
    const val FORMAT_WMA = 0xB901
    const val FORMAT_OGG = 0xB902
    const val FORMAT_AAC = 0xB903
    const val FORMAT_AUDIBLE = 0xB904
    const val FORMAT_FLAC = 0xB906
    const val FORMAT_M4A = 0xB907
    const val FORMAT_AMR = 0xB908
    const val FORMAT_OPUS = 0xBA14

    // Formats - Videos
    const val FORMAT_AVI = 0x300A
    const val FORMAT_MPEG = 0x300B
    const val FORMAT_ASF = 0x300C
    const val FORMAT_UNDEFINED_VIDEO = 0xB980
    const val FORMAT_WMV = 0xB981
    const val FORMAT_MP4 = 0xB982
    const val FORMAT_3GP = 0xB983
    const val FORMAT_3G2 = 0xB984
    const val FORMAT_AVCHD = 0xB985
    const val FORMAT_ATSC_TS = 0xB986
    const val FORMAT_DVB_TS = 0xB987
    const val FORMAT_MOV = 0xB988
    const val FORMAT_MKV = 0xBA05
    const val FORMAT_WEBM = 0xBA82

    val IMAGE_FORMATS = setOf(
        FORMAT_EXIF_JPEG,
        FORMAT_TIFF_EP,
        FORMAT_BMP,
        FORMAT_GIF,
        FORMAT_JFIF,
        FORMAT_PNG,
        FORMAT_TIFF,
        FORMAT_JP2,
        FORMAT_HEIF,
        FORMAT_WEBP
    )

    val VIDEO_FORMATS = setOf(
        FORMAT_AVI,
        FORMAT_MPEG,
        FORMAT_ASF,
        FORMAT_UNDEFINED_VIDEO,
        FORMAT_WMV,
        FORMAT_MP4,
        FORMAT_3GP,
        FORMAT_3G2,
        FORMAT_AVCHD,
        FORMAT_ATSC_TS,
        FORMAT_DVB_TS,
        FORMAT_MOV,
        FORMAT_MKV,
        FORMAT_WEBM
    )

    val AUDIO_FORMATS = setOf(
        FORMAT_WAV,
        FORMAT_MP3,
        FORMAT_AIFF,
        FORMAT_UNDEFINED_AUDIO,
        FORMAT_WMA,
        FORMAT_OGG,
        FORMAT_AAC,
        FORMAT_AUDIBLE,
        FORMAT_FLAC,
        FORMAT_M4A,
        FORMAT_AMR,
        FORMAT_OPUS
    )

    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff",
        "svg", "ico", "dng", "raw", "cr2", "nef", "arw", "rw2", "orf"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "mov", "avi", "3gp", "3g2", "webm", "ts", "m4v", "wmv", "mpg", "mpeg",
        "flv", "vob", "ogv", "m2ts", "mts", "divx", "asf", "f4v", "rm", "rmvb", "wtv"
    )

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "wav", "m4a", "aac", "flac", "ogg", "oga", "opus", "wma", "mid", "midi",
        "amr", "aif", "aiff", "mka", "ac3", "ra", "ram", "dts", "alac"
    )

    fun isImageFormat(format: Int): Boolean =
        format in IMAGE_FORMATS || (format in 0x3800..0x38FF) || (format in 0xB800..0xB8FF)

    fun isVideoFormat(format: Int): Boolean =
        format in VIDEO_FORMATS || (format in 0xB980..0xB98F) || format == 0xBA05 || format == 0xBA82

    fun isAudioFormat(format: Int): Boolean =
        format in AUDIO_FORMATS || (format in 0xB900..0xB97F) || format == 0xBA14

    fun isImageExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase().trim()
        return ext in IMAGE_EXTENSIONS
    }

    fun isVideoExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase().trim()
        return ext in VIDEO_EXTENSIONS
    }

    fun isAudioExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase().trim()
        return ext in AUDIO_EXTENSIONS
    }

    fun isAudio(format: Int, filename: String): Boolean {
        if (format == FORMAT_ASSOCIATION) return false
        if (isAudioFormat(format)) return true
        if (isAudioExtension(filename)) return true
        val lower = filename.lowercase().trim()
        if (lower.startsWith("aud_") || lower.startsWith("audio_") || lower.startsWith("rec_") ||
            lower.startsWith("voice_") || lower.startsWith("track_") || lower.startsWith("song_") ||
            lower.contains("music") || lower.contains("audio") || lower.contains("recording")) {
            return !isVideoExtension(filename) && !isImageExtension(filename)
        }
        return false
    }

    fun isVideo(format: Int, filename: String): Boolean {
        if (format == FORMAT_ASSOCIATION) return false
        if (isAudio(format, filename)) return false
        if (isVideoFormat(format)) return true
        if (isVideoExtension(filename)) return true
        val lower = filename.lowercase().trim()
        if (lower.startsWith("vid_") || lower.startsWith("mov_") || lower.contains("video")) {
            return !isImageExtension(filename) && !isAudioExtension(filename)
        }
        return false
    }

    fun isPhoto(format: Int, filename: String): Boolean {
        if (format == FORMAT_ASSOCIATION) return false
        if (isAudio(format, filename)) return false
        if (isImageFormat(format)) return true
        if (isImageExtension(filename)) return true
        val lower = filename.lowercase().trim()
        if (lower.startsWith("img_") || lower.startsWith("photo_") || lower.startsWith("pano_")) {
            return !isVideoExtension(filename) && !isAudioExtension(filename)
        }
        return false
    }
}
