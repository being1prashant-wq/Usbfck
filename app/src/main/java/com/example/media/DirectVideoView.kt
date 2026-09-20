package com.example.media

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory

data class MediaTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String,
    val mimeType: String,
    val isSelected: Boolean,
    val isSupported: Boolean
)

/**
 * High-performance video display view using SurfaceView and Media3 ExoPlayer.
 * Features:
 * - Native hardware decoding for 10-bit HEVC/H.264/VP9/AV1 where supported by TV SoC.
 * - Software demuxing for MKV, MP4, WebM, AVI, TS containers via DefaultExtractorsFactory.
 * - Robust audio/video stream separation: if AC3/EAC3 or other audio codec is not decodable
 *   on the TV hardware, audio track is isolated/disabled so the VIDEO STREAM PLAYS SMOOTHLY.
 * - Safe session isolation: opening/closing videos releases decoders and prevents state contamination.
 * - Preserves exact aspect ratio on TV displays.
 */
class DirectVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "DirectVideoView"
    }

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var isPrepared = false
    private var isSurfaceCreated = false
    private var isAudioTrackDisabled = false

    private var videoWidth = 0
    private var videoHeight = 0

    private var onPreparedListener: (() -> Unit)? = null
    private var onErrorListener: ((PlaybackException) -> Unit)? = null
    private var onCompletionListener: (() -> Unit)? = null
    private var onBufferingListener: ((Boolean) -> Unit)? = null
    private var onAudioFallbackNotice: ((String) -> Unit)? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        try {
            exoPlayer?.setVideoSurfaceHolder(holder)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting video surface holder on surfaceCreated", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handled by video size change listener
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        try {
            exoPlayer?.clearVideoSurfaceHolder(holder)
        } catch (_: Exception) {}
    }

    fun setSession(session: PtpPlaybackSession) {
        stopPlayback()
        isAudioTrackDisabled = false

        try {
            val ts = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setAllowAudioMixedMimeTypeAdaptiveness(true)
                        .setAllowAudioMixedSampleRateAdaptiveness(true)
                        .setAllowMultipleAdaptiveSelections(true)
                )
            }
            this.trackSelector = ts

            val renderersFactory = DefaultRenderersFactory(context).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                setEnableDecoderFallback(true)
            }

            val player = ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(ts)
                .build()

            this.exoPlayer = player

            if (isSurfaceCreated && holder.surface.isValid) {
                player.setVideoSurfaceHolder(holder)
            }

            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoWidth = videoSize.width
                        videoHeight = videoSize.height
                        holder.setFixedSize(videoWidth, videoHeight)
                        requestLayout()
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    // Check if audio tracks are present and if all are unsupported by TV
                    if (!isAudioTrackDisabled) {
                        var hasAudio = false
                        var hasSupportedAudio = false
                        for (group in tracks.groups) {
                            if (group.type == C.TRACK_TYPE_AUDIO) {
                                hasAudio = true
                                for (i in 0 until group.length) {
                                    if (group.isTrackSupported(i)) {
                                        hasSupportedAudio = true
                                        break
                                    }
                                }
                            }
                        }

                        // If audio is present but completely unsupported on this TV (e.g. AC3 without HW decoder)
                        if (hasAudio && !hasSupportedAudio) {
                            Log.w(TAG, "All audio tracks unsupported on this TV; disabling audio track so video plays normally")
                            isAudioTrackDisabled = true
                            ts.setParameters(
                                ts.buildUponParameters()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            )
                            onAudioFallbackNotice?.invoke("Audio codec not supported by this TV (playing video)")
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            onBufferingListener?.invoke(false)
                            if (!isPrepared) {
                                isPrepared = true
                                val size = player.videoSize
                                if (size.width > 0 && size.height > 0) {
                                    videoWidth = size.width
                                    videoHeight = size.height
                                    holder.setFixedSize(videoWidth, videoHeight)
                                    requestLayout()
                                }
                                onPreparedListener?.invoke()
                            }
                        }
                        Player.STATE_ENDED -> {
                            onCompletionListener?.invoke()
                        }
                        Player.STATE_BUFFERING -> {
                            onBufferingListener?.invoke(true)
                        }
                        Player.STATE_IDLE -> {
                            // Player idle
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        onBufferingListener?.invoke(false)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "ExoPlayer onPlayerError: ${error.errorCodeName} (${error.errorCode})", error)
                    
                    // Check if the error is audio-related (e.g. AC3/EAC3 decoder failure)
                    val isAudioError = isAudioDecoderOrRendererError(error)
                    val selector = trackSelector
                    if (isAudioError && !isAudioTrackDisabled && selector != null) {
                        Log.w(TAG, "Audio decoder failed on this TV; disabling audio track and resuming video playback")
                        isAudioTrackDisabled = true
                        selector.setParameters(
                            selector.buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        )
                        player.prepare()
                        player.play()
                        onAudioFallbackNotice?.invoke("Audio track not supported by this TV (video playing)")
                        return
                    }

                    isPrepared = false
                    onErrorListener?.invoke(error)
                }
            })

            val dataSourceFactory = PtpExoDataSource.Factory(session)
            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)

            val mediaItem = androidx.media3.common.MediaItem.Builder()
                .setUri(Uri.parse("ptp://video/${session.sessionId}/${session.item.handle}"))
                .build()

            val mediaSource = ProgressiveMediaSource.Factory(
                dataSourceFactory,
                extractorsFactory
            ).createMediaSource(mediaItem)

            player.setMediaSource(mediaSource)
            player.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ExoPlayer for session ${session.sessionId}", e)
            onErrorListener?.invoke(
                PlaybackException("Initialization error", e, PlaybackException.ERROR_CODE_UNSPECIFIED)
            )
        }
    }

    /**
     * Fallback overload if MediaDataSource is passed.
     */
    fun setDataSource(dataSource: MediaDataSource) {
        if (dataSource is PtpVideoDataSource) {
            setSession(dataSource.session)
        } else {
            Log.e(TAG, "Unsupported MediaDataSource type: ${dataSource.javaClass.name}")
        }
    }

    private fun isAudioDecoderOrRendererError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
        ) {
            var cause: Throwable? = error.cause
            while (cause != null) {
                val msg = cause.message?.lowercase() ?: ""
                if (msg.contains("audio") || msg.contains("ac3") || msg.contains("eac3") ||
                    msg.contains("dts") || msg.contains("audiotrack")
                ) {
                    return true
                }
                if (cause is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException) {
                    if (cause.mimeType?.startsWith("audio/") == true) {
                        return true
                    }
                }
                if (cause is androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException) {
                    return true
                }
                if (cause is androidx.media3.exoplayer.audio.AudioSink.InitializationException ||
                    cause is androidx.media3.exoplayer.audio.AudioSink.WriteException ||
                    cause is androidx.media3.exoplayer.audio.AudioSink.ConfigurationException
                ) {
                    return true
                }
                cause = cause.cause
            }
        }

        val errMessage = error.message?.lowercase() ?: ""
        if (errMessage.contains("audio") || errMessage.contains("ac3") ||
            errMessage.contains("eac3") || errMessage.contains("dts")
        ) {
            return true
        }

        return false
    }

    fun start() {
        try {
            exoPlayer?.play()
        } catch (e: Exception) {
            Log.w(TAG, "start() failed", e)
        }
    }

    fun pause() {
        try {
            exoPlayer?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "pause() failed", e)
        }
    }

    fun seekTo(msec: Int) {
        try {
            exoPlayer?.seekTo(msec.toLong().coerceAtLeast(0L))
        } catch (e: Exception) {
            Log.w(TAG, "seekTo() failed", e)
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
            if (d > 0 && d != C.TIME_UNSET) d.toInt() else 0
        } catch (_: Exception) {
            0
        }

    val currentPosition: Int
        get() = try {
            val p = exoPlayer?.currentPosition ?: 0L
            if (p >= 0 && p != C.TIME_UNSET) p.toInt() else 0
        } catch (_: Exception) {
            0
        }

    fun setPlaybackSpeed(speed: Float) {
        try {
            exoPlayer?.setPlaybackSpeed(speed)
        } catch (e: Exception) {
            Log.w(TAG, "setPlaybackSpeed failed", e)
        }
    }

    fun setLooping(isLooping: Boolean) {
        try {
            exoPlayer?.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        } catch (_: Exception) {}
    }

    fun getAudioTracks(): List<MediaTrackOption> {
        val player = exoPlayer ?: return emptyList()
        val tracks = player.currentTracks
        val list = mutableListOf<MediaTrackOption>()

        var audioCounter = 1
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val lang = format.language?.takeIf { it.isNotBlank() } ?: "Track $audioCounter"
                    val codec = format.sampleMimeType?.substringAfterLast('/')?.uppercase() ?: ""
                    val channels = if (format.channelCount > 0) "${format.channelCount}ch" else ""
                    val details = listOf(lang, codec, channels).filter { it.isNotBlank() }.joinToString(" • ")

                    list.add(
                        MediaTrackOption(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            label = details,
                            language = format.language ?: "",
                            mimeType = format.sampleMimeType ?: "",
                            isSelected = group.isTrackSelected(trackIndex),
                            isSupported = group.isTrackSupported(trackIndex)
                        )
                    )
                    audioCounter++
                }
            }
        }
        return list
    }

    fun selectAudioTrack(option: MediaTrackOption) {
        val player = exoPlayer ?: return
        val ts = trackSelector ?: return
        val tracks = player.currentTracks
        if (option.groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[option.groupIndex]
            ts.setParameters(
                ts.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            )
        }
    }

    fun getSubtitleTracks(): List<MediaTrackOption> {
        val player = exoPlayer ?: return emptyList()
        val tracks = player.currentTracks
        val list = mutableListOf<MediaTrackOption>()

        var subCounter = 1
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val lang = format.language?.takeIf { it.isNotBlank() } ?: "Track $subCounter"
                    val label = format.label?.takeIf { it.isNotBlank() } ?: lang

                    list.add(
                        MediaTrackOption(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            label = label,
                            language = format.language ?: "",
                            mimeType = format.sampleMimeType ?: "",
                            isSelected = group.isTrackSelected(trackIndex),
                            isSupported = group.isTrackSupported(trackIndex)
                        )
                    )
                    subCounter++
                }
            }
        }
        return list
    }

    fun selectSubtitleTrack(option: MediaTrackOption?) {
        val player = exoPlayer ?: return
        val ts = trackSelector ?: return
        if (option == null) {
            ts.setParameters(
                ts.buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        } else {
            val tracks = player.currentTracks
            if (option.groupIndex in 0 until tracks.groups.size) {
                val group = tracks.groups[option.groupIndex]
                ts.setParameters(
                    ts.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
                )
            }
        }
    }

    fun setOnPreparedListener(l: (() -> Unit)?) {
        onPreparedListener = l
    }

    fun setOnErrorListener(l: ((PlaybackException) -> Unit)?) {
        onErrorListener = l
    }

    fun setOnCompletionListener(l: (() -> Unit)?) {
        onCompletionListener = l
    }

    fun setOnBufferingListener(l: ((Boolean) -> Unit)?) {
        onBufferingListener = l
    }

    fun setOnAudioFallbackNotice(l: ((String) -> Unit)?) {
        onAudioFallbackNotice = l
    }

    fun stopPlayback() {
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            exoPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ExoPlayer", e)
        }
        exoPlayer = null
        trackSelector = null
        isPrepared = false
        isAudioTrackDisabled = false
        videoWidth = 0
        videoHeight = 0
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
        setMeasuredDimension(width, height)
    }
}
