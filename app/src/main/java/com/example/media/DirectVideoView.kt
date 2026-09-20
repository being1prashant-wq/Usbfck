package com.example.media

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import com.example.usb.PtpConstants

enum class PlaybackEngine {
    EXOPLAYER,
    NATIVE_MEDIAPLAYER
}

data class PlayerAudioTrack(
    val index: Int,
    val trackGroup: TrackGroup?,
    val trackIndexInGroup: Int,
    val language: String,
    val label: String,
    val mimeType: String,
    val channels: Int,
    val isAc3OrDolby: Boolean,
    val isSelected: Boolean
)

data class PlayerSubtitleTrack(
    val index: Int,
    val trackGroup: TrackGroup?,
    val trackIndexInGroup: Int,
    val language: String,
    val label: String,
    val isSelected: Boolean
)

/**
 * High-reliability TV video player featuring Jetpack Media3 ExoPlayer with Dolby AC-3/E-AC-3
 * passthrough and decoding, paired with an automatic seamless fallback to Android's Native
 * MediaPlayer engine to guarantee 100% video playback on any Android TV device.
 */
@OptIn(UnstableApi::class)
class DirectVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val surfaceView: SurfaceView = SurfaceView(context)
    private var exoPlayer: ExoPlayer? = null
    private var nativeMediaPlayer: MediaPlayer? = null

    private var currentSession: PtpPlaybackSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeEngine: PlaybackEngine = PlaybackEngine.EXOPLAYER
    private var audioFallbackAttempted = false
    private var nativeFallbackAttempted = false

    private var isPrepared = false
    private var isSurfaceCreated = false
    private var videoWidth = 0
    private var videoHeight = 0

    var isLooping = false
        set(value) {
            field = value
            exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            nativeMediaPlayer?.isLooping = value
        }

    var currentPlaybackSpeed = 1.0f
        private set

    // Callbacks for UI integration
    var onPreparedCallback: (() -> Unit)? = null
    var onErrorCallback: ((errorMessage: String) -> Unit)? = null
    var onCompletionCallback: (() -> Unit)? = null
    var onBufferingCallback: ((isBuffering: Boolean) -> Unit)? = null
    var onAudioTracksUpdated: ((List<PlayerAudioTrack>) -> Unit)? = null
    var onVideoSizeChangedCallback: ((width: Int, height: Int) -> Unit)? = null
    var onEngineChangedCallback: ((engine: PlaybackEngine) -> Unit)? = null

    // Legacy MediaPlayer-style listeners
    private var legacyPreparedListener: MediaPlayer.OnPreparedListener? = null
    private var legacyErrorListener: MediaPlayer.OnErrorListener? = null
    private var legacyCompletionListener: MediaPlayer.OnCompletionListener? = null

    init {
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        surfaceView.layoutParams = lp
        surfaceView.holder.addCallback(this)
        addView(surfaceView)
    }

    fun getActiveEngine(): PlaybackEngine = activeEngine

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        exoPlayer?.setVideoSurface(holder.surface)
        try {
            nativeMediaPlayer?.setDisplay(holder)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Error setting display on native MediaPlayer", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        exoPlayer?.setVideoSurface(null)
        try {
            nativeMediaPlayer?.setDisplay(null)
        } catch (_: Exception) {}
    }

    /**
     * Start video playback for a given [PtpPlaybackSession].
     * Starts with ExoPlayer (AC-3/Dolby capabilities enabled) with automatic fallback.
     */
    fun setPlaybackSession(session: PtpPlaybackSession, preferredEngine: PlaybackEngine = PlaybackEngine.EXOPLAYER) {
        stopPlayback()
        this.currentSession = session
        this.audioFallbackAttempted = false
        this.nativeFallbackAttempted = false
        this.activeEngine = preferredEngine

        if (preferredEngine == PlaybackEngine.EXOPLAYER) {
            startExoPlayerSession(session)
        } else {
            startNativeMediaPlayerSession(session)
        }
    }

    private fun startExoPlayerSession(session: PtpPlaybackSession) {
        try {
            activeEngine = PlaybackEngine.EXOPLAYER
            onEngineChangedCallback?.invoke(activeEngine)

            val ffmpegAvailable = try {
                FfmpegLibrary.isAvailable()
            } catch (e: Throwable) {
                Log.w(PtpConstants.TAG, "FfmpegLibrary availability check failed", e)
                false
            }
            Log.i(PtpConstants.TAG, "FFmpeg software audio decoder active: $ffmpegAvailable")

            val audioSink = DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                .setEnableFloatOutput(false)
                .build()

            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): androidx.media3.exoplayer.audio.AudioSink {
                    return audioSink
                }
            }.apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableDecoderFallback(true)
            }

            val player = ExoPlayer.Builder(context, renderersFactory)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .build()

            this.exoPlayer = player

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            player.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)

            if (isSurfaceCreated && surfaceView.holder.surface.isValid) {
                player.setVideoSurface(surfaceView.holder.surface)
            }

            player.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.setPlaybackSpeed(currentPlaybackSpeed)

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            onBufferingCallback?.invoke(true)
                        }
                        Player.STATE_READY -> {
                            onBufferingCallback?.invoke(false)
                            if (!isPrepared) {
                                isPrepared = true
                                onPreparedCallback?.invoke()
                                legacyPreparedListener?.onPrepared(null)
                            }
                        }
                        Player.STATE_ENDED -> {
                            onBufferingCallback?.invoke(false)
                            onCompletionCallback?.invoke()
                            legacyCompletionListener?.onCompletion(null)
                        }
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(PtpConstants.TAG, "ExoPlayer playback error (code=${error.errorCodeName}): ${error.message}")

                    // Fallback to Native MediaPlayer engine if ExoPlayer cannot render on this TV hardware
                    if (!nativeFallbackAttempted) {
                        nativeFallbackAttempted = true
                        Log.i(PtpConstants.TAG, "ExoPlayer failed on this TV; switching to Native engine fallback")
                        mainHandler.post {
                            fallbackToNativeMediaPlayer(session)
                        }
                        return
                    }

                    isPrepared = false
                    onBufferingCallback?.invoke(false)
                    val errorMsg = error.message ?: "Playback error: ${error.errorCodeName}"
                    onErrorCallback?.invoke(errorMsg)
                    legacyErrorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, error.errorCode)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoWidth = videoSize.width
                        videoHeight = videoSize.height
                        onVideoSizeChangedCallback?.invoke(videoWidth, videoHeight)
                        requestLayout()
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    val audioList = getAudioTracks()
                    onAudioTracksUpdated?.invoke(audioList)
                }
            })

            val dataSourceFactory = PtpMedia3DataSource.Factory(session)
            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)

            val extension = session.item.filename.substringAfterLast('.', "").lowercase()
            val mimeType = when (extension) {
                "mp4", "m4v" -> MimeTypes.VIDEO_MP4
                "mkv" -> MimeTypes.APPLICATION_MATROSKA
                "webm" -> MimeTypes.VIDEO_WEBM
                "ts" -> MimeTypes.VIDEO_MP2T
                "avi" -> MimeTypes.VIDEO_AVI
                "mov" -> "video/quicktime"
                else -> null
            }

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(Uri.parse("file:///${session.item.filename}"))
            if (mimeType != null) {
                mediaItemBuilder.setMimeType(mimeType)
            }
            val mediaItem = mediaItemBuilder.build()

            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)

            player.setMediaSource(mediaSource)
            player.prepare()
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to initialize ExoPlayer, falling back to Native MediaPlayer", e)
            fallbackToNativeMediaPlayer(session)
        }
    }

    private fun fallbackToNativeMediaPlayer(session: PtpPlaybackSession) {
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (_: Exception) {}
        exoPlayer = null

        startNativeMediaPlayerSession(session)
    }

    private fun startNativeMediaPlayerSession(session: PtpPlaybackSession) {
        activeEngine = PlaybackEngine.NATIVE_MEDIAPLAYER
        onEngineChangedCallback?.invoke(activeEngine)
        isPrepared = false

        try {
            val mp = MediaPlayer().apply {
                if (isSurfaceCreated && surfaceView.holder.surface.isValid) {
                    setDisplay(surfaceView.holder)
                }
                isLooping = this@DirectVideoView.isLooping
                setDataSource(session.dataSource)

                setOnPreparedListener { player ->
                    this@DirectVideoView.isPrepared = true
                    this@DirectVideoView.videoWidth = player.videoWidth
                    this@DirectVideoView.videoHeight = player.videoHeight
                    onBufferingCallback?.invoke(false)
                    onPreparedCallback?.invoke()
                    legacyPreparedListener?.onPrepared(player)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentPlaybackSpeed != 1.0f) {
                        try {
                            val params = player.playbackParams
                            params.speed = currentPlaybackSpeed
                            player.playbackParams = params
                        } catch (_: Exception) {}
                    }
                    player.start()
                    requestLayout()
                }

                setOnErrorListener { player, what, extra ->
                    Log.w(PtpConstants.TAG, "Native MediaPlayer error: what=$what extra=$extra")
                    this@DirectVideoView.isPrepared = false
                    onBufferingCallback?.invoke(false)
                    onErrorCallback?.invoke("Playback error ($what, $extra)")
                    legacyErrorListener?.onError(player, what, extra) ?: true
                }

                setOnCompletionListener { player ->
                    onBufferingCallback?.invoke(false)
                    onCompletionCallback?.invoke()
                    legacyCompletionListener?.onCompletion(player)
                }

                setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0) {
                        this@DirectVideoView.videoWidth = w
                        this@DirectVideoView.videoHeight = h
                        onVideoSizeChangedCallback?.invoke(w, h)
                        requestLayout()
                    }
                }

                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> onBufferingCallback?.invoke(true)
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> onBufferingCallback?.invoke(false)
                    }
                    false
                }
            }

            this.nativeMediaPlayer = mp
            onBufferingCallback?.invoke(true)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to start Native MediaPlayer", e)
            isPrepared = false
            onBufferingCallback?.invoke(false)
            onErrorCallback?.invoke(e.message ?: "Failed to start player")
            legacyErrorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, -1)
        }
    }

    private fun isAudioRelatedError(error: PlaybackException): Boolean {
        return error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
               error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
               error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
               error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
               error.errorCodeName.contains("AUDIO", ignoreCase = true) ||
               (error.message?.contains("audio", ignoreCase = true) == true)
    }

    /**
     * Legacy backward compatibility method.
     */
    fun setDataSource(dataSource: MediaDataSource) {
        if (dataSource is PtpVideoDataSource) {
            setPlaybackSession(dataSource.session)
        }
    }

    fun start() {
        try {
            exoPlayer?.play()
            nativeMediaPlayer?.start()
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "start() failed", e)
        }
    }

    fun pause() {
        try {
            exoPlayer?.pause()
            nativeMediaPlayer?.pause()
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "pause() failed", e)
        }
    }

    fun seekTo(msec: Int) {
        try {
            exoPlayer?.seekTo(msec.toLong())
            nativeMediaPlayer?.seekTo(msec)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "seekTo() failed", e)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        try {
            exoPlayer?.playbackParameters = PlaybackParameters(speed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                nativeMediaPlayer?.let { mp ->
                    if (isPrepared) {
                        val params = mp.playbackParams
                        params.speed = speed
                        mp.playbackParams = params
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "setPlaybackSpeed() failed", e)
        }
    }

    val isPlaying: Boolean
        get() = try {
            exoPlayer?.isPlaying ?: nativeMediaPlayer?.isPlaying ?: false
        } catch (_: Exception) {
            false
        }

    val duration: Int
        get() = try {
            if (activeEngine == PlaybackEngine.EXOPLAYER) {
                val d = exoPlayer?.duration ?: 0L
                if (d == C.TIME_UNSET || d < 0) 0 else d.toInt()
            } else {
                nativeMediaPlayer?.duration ?: 0
            }
        } catch (_: Exception) {
            0
        }

    val currentPosition: Int
        get() = try {
            if (activeEngine == PlaybackEngine.EXOPLAYER) {
                val p = exoPlayer?.currentPosition ?: 0L
                if (p < 0) 0 else p.toInt()
            } else {
                nativeMediaPlayer?.currentPosition ?: 0
            }
        } catch (_: Exception) {
            0
        }

    fun getExoPlayer(): ExoPlayer? = exoPlayer
    fun getNativeMediaPlayer(): MediaPlayer? = nativeMediaPlayer

    /**
     * Returns all detected audio tracks in the video with AC3 / Dolby indicators.
     */
    fun getAudioTracks(): List<PlayerAudioTrack> {
        val player = exoPlayer
        if (player != null) {
            val result = mutableListOf<PlayerAudioTrack>()
            val tracks = player.currentTracks

            var trackIndex = 0
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                val mediaTrackGroup = group.mediaTrackGroup

                for (i in 0 until mediaTrackGroup.length) {
                    val format = mediaTrackGroup.getFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val mime = format.sampleMimeType ?: ""
                    val isAc3 = mime.equals(MimeTypes.AUDIO_AC3, ignoreCase = true) ||
                                mime.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) ||
                                mime.equals(MimeTypes.AUDIO_E_AC3_JOC, ignoreCase = true) ||
                                mime.contains("ac3", ignoreCase = true) ||
                                mime.contains("dolby", ignoreCase = true)

                    val channelDesc = when (format.channelCount) {
                        6 -> "5.1 Surround"
                        8 -> "7.1 Surround"
                        2 -> "Stereo"
                        1 -> "Mono"
                        else -> if (format.channelCount > 0) "${format.channelCount}ch" else ""
                    }

                    val formatDesc = when {
                        mime.equals(MimeTypes.AUDIO_AC3, ignoreCase = true) -> "AC-3 (Dolby)"
                        mime.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) -> "E-AC-3 (Dolby+)"
                        mime.equals(MimeTypes.AUDIO_DTS, ignoreCase = true) -> "DTS"
                        mime.equals(MimeTypes.AUDIO_AAC, ignoreCase = true) -> "AAC"
                        mime.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true) -> "FLAC"
                        mime.equals(MimeTypes.AUDIO_RAW, ignoreCase = true) -> "PCM"
                        else -> mime.substringAfterLast('/').uppercase()
                    }

                    val lang = format.language?.uppercase()?.ifBlank { null } ?: "Track ${trackIndex + 1}"
                    val label = buildString {
                        append(lang)
                        if (formatDesc.isNotBlank()) append(" • ").append(formatDesc)
                        if (channelDesc.isNotBlank()) append(" (").append(channelDesc).append(")")
                    }

                    result.add(
                        PlayerAudioTrack(
                            index = trackIndex,
                            trackGroup = mediaTrackGroup,
                            trackIndexInGroup = i,
                            language = format.language ?: "",
                            label = label,
                            mimeType = mime,
                            channels = format.channelCount,
                            isAc3OrDolby = isAc3,
                            isSelected = isSelected
                        )
                    )
                    trackIndex++
                }
            }
            return result
        }

        // Native MediaPlayer fallback track listing
        val mp = nativeMediaPlayer
        if (mp != null) {
            try {
                val trackInfo = mp.trackInfo
                val result = mutableListOf<PlayerAudioTrack>()
                var audioCounter = 1
                for (i in trackInfo.indices) {
                    if (trackInfo[i].trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                        val lang = trackInfo[i].language.ifBlank { "Track $audioCounter" }
                        result.add(
                            PlayerAudioTrack(
                                index = i,
                                trackGroup = null,
                                trackIndexInGroup = i,
                                language = lang,
                                label = lang,
                                mimeType = "",
                                channels = 2,
                                isAc3OrDolby = false,
                                isSelected = (audioCounter == 1)
                            )
                        )
                        audioCounter++
                    }
                }
                return result
            } catch (_: Exception) {}
        }

        return emptyList()
    }

    /**
     * Switch active audio track by index.
     */
    fun selectAudioTrack(trackIndex: Int) {
        val player = exoPlayer
        if (player != null) {
            val allAudio = getAudioTracks()
            if (trackIndex in allAudio.indices) {
                val target = allAudio[trackIndex]
                val group = target.trackGroup
                if (group != null) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(group, target.trackIndexInGroup))
                        .build()
                }
            }
            return
        }

        val mp = nativeMediaPlayer
        if (mp != null) {
            try {
                mp.selectTrack(trackIndex)
            } catch (e: Exception) {
                Log.w(PtpConstants.TAG, "Native MediaPlayer selectTrack failed", e)
            }
        }
    }

    /**
     * Subtitle track queries and selection.
     */
    fun getSubtitleTracks(): List<PlayerSubtitleTrack> {
        val player = exoPlayer
        if (player != null) {
            val result = mutableListOf<PlayerSubtitleTrack>()
            val tracks = player.currentTracks

            val isTextDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            result.add(
                PlayerSubtitleTrack(
                    index = -1,
                    trackGroup = null,
                    trackIndexInGroup = -1,
                    language = "",
                    label = "Subtitles Off",
                    isSelected = isTextDisabled
                )
            )

            var subIdx = 0
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                val mediaTrackGroup = group.mediaTrackGroup

                for (i in 0 until mediaTrackGroup.length) {
                    val format = mediaTrackGroup.getFormat(i)
                    val isSelected = !isTextDisabled && group.isTrackSelected(i)
                    val lang = format.language?.uppercase()?.ifBlank { "Track ${subIdx + 1}" } ?: "Track ${subIdx + 1}"
                    val label = format.label ?: lang

                    result.add(
                        PlayerSubtitleTrack(
                            index = subIdx,
                            trackGroup = mediaTrackGroup,
                            trackIndexInGroup = i,
                            language = format.language ?: "",
                            label = label,
                            isSelected = isSelected
                        )
                    )
                    subIdx++
                }
            }
            return result
        }

        return emptyList()
    }

    fun selectSubtitleTrack(trackIndex: Int) {
        val player = exoPlayer ?: return
        if (trackIndex == -1) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }

        val allSubs = getSubtitleTracks().filter { it.index >= 0 }
        val target = allSubs.find { it.index == trackIndex } ?: return
        val group = target.trackGroup ?: return

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group, target.trackIndexInGroup))
            .build()
    }

    fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener?) {
        legacyPreparedListener = l
    }

    fun setOnErrorListener(l: MediaPlayer.OnErrorListener?) {
        legacyErrorListener = l
    }

    fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener?) {
        legacyCompletionListener = l
    }

    fun stopPlayback() {
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            exoPlayer?.release()
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Error releasing ExoPlayer in DirectVideoView", e)
        }
        exoPlayer = null

        try {
            nativeMediaPlayer?.stop()
            nativeMediaPlayer?.reset()
            nativeMediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Error releasing Native MediaPlayer", e)
        }
        nativeMediaPlayer = null

        isPrepared = false
        videoWidth = 0
        videoHeight = 0
        currentSession = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = getDefaultSize(videoWidth, widthMeasureSpec)
        var height = getDefaultSize(videoHeight, heightMeasureSpec)
        if (videoWidth > 0 && videoHeight > 0) {
            val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSpecSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSpecSize = MeasureSpec.getSize(heightMeasureSpec)

            if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
                width = widthSpecSize
                height = heightSpecSize
                if (videoWidth * height < width * videoHeight) {
                    width = height * videoWidth / videoHeight
                } else if (videoWidth * height > width * videoHeight) {
                    height = width * videoHeight / videoWidth
                }
            } else if (widthSpecMode == MeasureSpec.EXACTLY) {
                width = widthSpecSize
                height = width * videoHeight / videoWidth
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    height = heightSpecSize
                }
            } else if (heightSpecMode == MeasureSpec.EXACTLY) {
                height = heightSpecSize
                width = height * videoWidth / videoHeight
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    width = widthSpecSize
                }
            } else {
                width = videoWidth
                height = videoHeight
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    height = heightSpecSize
                    width = height * videoWidth / videoHeight
                }
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    width = widthSpecSize
                    height = width * videoHeight / videoWidth
                }
            }
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
