package com.example.media

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.example.usb.PtpConstants

/**
 * High-performance Video Display View built on AndroidX Media3 ExoPlayer with full
 * Dolby AC-3 / E-AC-3 / DTS / AAC / Opus multi-codec audio decoding and container support.
 * Integrates directly with [PtpPlaybackSession] chunk-streaming engine.
 */
@UnstableApi
class DirectVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    data class TrackOption(
        val groupIndex: Int,
        val trackIndex: Int,
        val title: String,
        val language: String,
        val isSelected: Boolean
    )

    private var exoPlayer: ExoPlayer? = null
    private var isPrepared = false
    private var isSurfaceCreated = false

    private var videoWidth = 0
    private var videoHeight = 0

    private var onPreparedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((errorMessage: String) -> Unit)? = null
    private var onCompletionCallback: (() -> Unit)? = null
    private var onBufferingCallback: ((Boolean) -> Unit)? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        try {
            exoPlayer?.setVideoSurfaceHolder(holder)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Error attaching surface holder to ExoPlayer", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Handled via onVideoSizeChanged
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        try {
            exoPlayer?.setVideoSurfaceHolder(null)
        } catch (_: Exception) {}
    }

    /**
     * Prepares and starts playback from a [PtpPlaybackSession] with AC-3 / EAC3 audio codec support.
     */
    fun setSession(session: PtpPlaybackSession) {
        stopPlayback()
        setupPlayer(PtpMedia3DataSource.Factory(session), Uri.parse("ptp://video/${session.item.handle}/${session.item.filename}"))
    }

    /**
     * Backward-compatible overload accepting [PtpVideoDataSource] or session.
     */
    fun setDataSource(dataSource: android.media.MediaDataSource) {
        if (dataSource is PtpVideoDataSource) {
            setSession(dataSource.session)
        }
    }

    private fun setupPlayer(dataSourceFactory: DataSource.Factory, mediaUri: Uri) {
        try {
            // Configure AudioSink for AC-3 / E-AC-3 Dolby Audio decoding and passthrough
            val audioSink = DefaultAudioSink.Builder(context)
                .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                .setEnableFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(true)
                .build()

            val renderersFactory = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    return audioSink
                }
            }.apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                setEnableDecoderFallback(true)
            }

            // Extractors for MKV, MP4, TS, AVI, AC-3 raw, etc.
            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)

            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

            val player = ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
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
                            }
                        }
                        Player.STATE_ENDED -> {
                            onCompletionCallback?.invoke()
                        }
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(PtpConstants.TAG, "DirectVideoView ExoPlayer playback error: ${error.errorCodeName}", error)
                    isPrepared = false
                    onBufferingCallback?.invoke(false)
                    onErrorCallback?.invoke(error.message ?: error.errorCodeName)
                }
            })

            val mediaItem = MediaItem.fromUri(mediaUri)
            player.setMediaItem(mediaItem)
            player.prepare()
        } catch (e: Exception) {
            Log.e(PtpConstants.TAG, "Failed to initialize ExoPlayer for video playback", e)
            onErrorCallback?.invoke(e.message ?: "Failed to initialize video player")
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
        try {
            exoPlayer?.playbackParameters = PlaybackParameters(speed)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "setPlaybackSpeed() failed", e)
        }
    }

    fun setLooping(isLooping: Boolean) {
        try {
            exoPlayer?.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "setLooping() failed", e)
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
            val dur = exoPlayer?.duration ?: 0L
            if (dur > 0 && dur != C.TIME_UNSET) dur.toInt() else 0
        } catch (_: Exception) {
            0
        }

    val currentPosition: Int
        get() = try {
            val pos = exoPlayer?.currentPosition ?: 0L
            pos.toInt().coerceAtLeast(0)
        } catch (_: Exception) {
            0
        }

    fun setOnPreparedListener(callback: () -> Unit) {
        onPreparedCallback = callback
    }

    fun setOnErrorListener(callback: (errorMessage: String) -> Unit) {
        onErrorCallback = callback
    }

    fun setOnCompletionListener(callback: () -> Unit) {
        onCompletionCallback = callback
    }

    fun setOnBufferingListener(callback: (Boolean) -> Unit) {
        onBufferingCallback = callback
    }

    /**
     * Retrieve available audio tracks (e.g. AC-3 5.1, AAC, etc.)
     */
    fun getAudioTracks(): List<TrackOption> {
        val player = exoPlayer ?: return emptyList()
        val tracks = player.currentTracks
        val result = mutableListOf<TrackOption>()

        var index = 1
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val mime = format.sampleMimeType ?: ""
                    val codecLabel = when {
                        mime.contains("ac3", ignoreCase = true) || mime.contains("eac3", ignoreCase = true) -> "AC-3 / Dolby"
                        mime.contains("dts", ignoreCase = true) -> "DTS"
                        mime.contains("aac", ignoreCase = true) -> "AAC"
                        mime.contains("opus", ignoreCase = true) -> "Opus"
                        mime.contains("flac", ignoreCase = true) -> "FLAC"
                        mime.contains("mpeg", ignoreCase = true) || mime.contains("mp3", ignoreCase = true) -> "MP3"
                        else -> format.codecs ?: ""
                    }
                    val lang = format.language?.ifBlank { "Track $index" } ?: "Track $index"
                    val channels = if (format.channelCount > 2) " (${format.channelCount} ch)" else ""
                    val title = if (codecLabel.isNotBlank()) "$lang [$codecLabel]$channels" else "$lang$channels"
                    result.add(
                        TrackOption(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            title = title,
                            language = lang,
                            isSelected = group.isTrackSelected(trackIndex)
                        )
                    )
                    index++
                }
            }
        }
        return result
    }

    /**
     * Switch active audio track.
     */
    fun selectAudioTrack(groupIndex: Int, trackIndex: Int) {
        val player = exoPlayer ?: return
        val tracks = player.currentTracks
        if (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                )
                .build()
        }
    }

    /**
     * Retrieve available subtitle tracks.
     */
    fun getSubtitleTracks(): List<TrackOption> {
        val player = exoPlayer ?: return emptyList()
        val tracks = player.currentTracks
        val result = mutableListOf<TrackOption>()

        var index = 1
        for (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val lang = format.language?.ifBlank { "Subtitle $index" } ?: "Subtitle $index"
                    val label = format.label ?: lang
                    result.add(
                        TrackOption(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            title = label,
                            language = lang,
                            isSelected = group.isTrackSelected(trackIndex)
                        )
                    )
                    index++
                }
            }
        }
        return result
    }

    /**
     * Switch active subtitle track.
     */
    fun selectSubtitleTrack(groupIndex: Int, trackIndex: Int) {
        val player = exoPlayer ?: return
        val tracks = player.currentTracks
        if (groupIndex in 0 until tracks.groups.size) {
            val group = tracks.groups[groupIndex]
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
                )
                .build()
        }
    }

    /**
     * Turn subtitles off.
     */
    fun disableSubtitles() {
        val player = exoPlayer ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun stopPlayback() {
        try {
            exoPlayer?.let { player ->
                player.stop()
                player.release()
            }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Error releasing ExoPlayer", e)
        }
        exoPlayer = null
        isPrepared = false
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
