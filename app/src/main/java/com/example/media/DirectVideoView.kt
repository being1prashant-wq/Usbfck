package com.example.media

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.usb.PtpConstants

data class PlayerAudioTrack(
    val index: Int,
    val trackGroup: TrackGroup,
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
 * Modern video display component powered by Media3 ExoPlayer with full AC-3 (Dolby Digital),
 * E-AC-3, DTS, and multi-track audio decoding/passthrough support over PTP/MTP USB streams.
 */
@OptIn(UnstableApi::class)
class DirectVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val surfaceView: SurfaceView = SurfaceView(context)
    private var exoPlayer: ExoPlayer? = null
    private var currentSession: PtpPlaybackSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isPrepared = false
    private var isSurfaceCreated = false
    private var videoWidth = 0
    private var videoHeight = 0

    var isLooping = false
        set(value) {
            field = value
            exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
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

    // Legacy MediaPlayer-style listeners for seamless compatibility
    private var legacyPreparedListener: MediaPlayer.OnPreparedListener? = null
    private var legacyErrorListener: MediaPlayer.OnErrorListener? = null
    private var legacyCompletionListener: MediaPlayer.OnCompletionListener? = null

    init {
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        surfaceView.layoutParams = lp
        surfaceView.holder.addCallback(this)
        addView(surfaceView)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        exoPlayer?.setVideoSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        exoPlayer?.setVideoSurface(null)
    }

    /**
     * Initialize and configure playback using [PtpPlaybackSession] with AC-3 passthrough & decoding.
     */
    fun setPlaybackSession(session: PtpPlaybackSession) {
        stopPlayback()
        this.currentSession = session

        try {
            val audioSink = DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                .setEnableFloatOutput(false)
                .build()

            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)

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
                    Log.e(PtpConstants.TAG, "ExoPlayer error in DirectVideoView: ${error.errorCodeName}", error)
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
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse("ptp://video/${session.item.handle}/${session.item.filename}"))
                .build()

            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)

            player.setMediaSource(mediaSource)
            player.prepare()
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to initialize ExoPlayer for session ${session.sessionId}", e)
            onErrorCallback?.invoke(e.message ?: "Failed to start player")
            legacyErrorListener?.onError(null, MediaPlayer.MEDIA_ERROR_UNKNOWN, -1)
        }
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
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "start() failed", e)
        }
    }

    fun pause() {
        try {
            exoPlayer?.pause()
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "pause() failed", e)
        }
    }

    fun seekTo(msec: Int) {
        try {
            exoPlayer?.seekTo(msec.toLong())
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "seekTo() failed", e)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        try {
            exoPlayer?.playbackParameters = PlaybackParameters(speed)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "setPlaybackSpeed() failed", e)
        }
    }

    val isPlaying: Boolean
        get() = try {
            exoPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    val duration: Int
        get() = try {
            val d = exoPlayer?.duration ?: 0L
            if (d == C.TIME_UNSET || d < 0) 0 else d.toInt()
        } catch (_: Exception) {
            0
        }

    val currentPosition: Int
        get() = try {
            val p = exoPlayer?.currentPosition ?: 0L
            if (p < 0) 0 else p.toInt()
        } catch (_: Exception) {
            0
        }

    fun getExoPlayer(): ExoPlayer? = exoPlayer

    /**
     * Returns all detected audio tracks in the video with AC3 / Dolby indicators.
     */
    fun getAudioTracks(): List<PlayerAudioTrack> {
        val player = exoPlayer ?: return emptyList()
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

    /**
     * Switch active audio track by index.
     */
    fun selectAudioTrack(trackIndex: Int) {
        val player = exoPlayer ?: return
        val allAudio = getAudioTracks()
        if (trackIndex !in allAudio.indices) return

        val target = allAudio[trackIndex]
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(target.trackGroup, target.trackIndexInGroup))
            .build()
    }

    /**
     * Subtitle track queries and selection.
     */
    fun getSubtitleTracks(): List<PlayerSubtitleTrack> {
        val player = exoPlayer ?: return emptyList()
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
