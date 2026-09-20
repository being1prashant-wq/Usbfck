package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.media.AudioCacheManager
import com.example.media.BackgroundPlayService
import com.example.media.DirectVideoView
import com.example.media.EqualizerView
import com.example.media.MediaContextMenuHelper
import com.example.media.MediaThumbnailLoader
import com.example.media.PhotoThumbnailLoader
import com.example.media.PlaybackEngine
import com.example.media.PtpMediaItem
import com.example.media.PtpMediaRepository
import com.example.media.TvFocusAnimator
import com.example.media.VideoPlaybackManager
import com.example.theme.AppTheme
import com.example.theme.ThemeManager
import com.example.theme.ThemeSelectionDialog
import com.example.usb.PtpClient
import com.example.usb.PtpConstants
import com.example.usb.UsbConnectionState
import com.example.usb.UsbHostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen {
    START,
    HOME,
    PHOTO_BROWSER,
    PHOTO_VIEWER,
    VIDEO_BROWSER,
    VIDEO_PLAYER,
    AUDIO_BROWSER,
    AUDIO_PLAYER
}

enum class LoopMode {
    OFF,
    SINGLE,
    ALL
}

enum class ViewMode {
    GRID,
    LIST
}

enum class SortMode {
    DEFAULT,
    NAME_ASC,
    SIZE_DESC,
    DATE_DESC
}

class MainActivity : AppCompatActivity() {

    private lateinit var usbHostManager: UsbHostManager
    private lateinit var repository: PtpMediaRepository
    private lateinit var legacyPhotoThumbLoader: PhotoThumbnailLoader
    private lateinit var mediaThumbnailLoader: MediaThumbnailLoader
    private lateinit var videoPlaybackManager: VideoPlaybackManager
    private lateinit var audioCacheManager: AudioCacheManager
    private lateinit var contextMenuHelper: MediaContextMenuHelper

    // Media Session for hardware TV controls
    private var mediaSession: MediaSessionCompat? = null

    // UI Screen containers
    private lateinit var screenStart: View
    private lateinit var screenHome: View
    private lateinit var screenPhotoBrowser: View
    private lateinit var screenPhotoViewer: View
    private lateinit var screenVideoBrowser: View
    private lateinit var screenVideoPlayer: View
    private lateinit var screenAudioBrowser: View
    private lateinit var screenAudioPlayer: View

    // Overlays
    private lateinit var overlayQueue: FrameLayout
    private lateinit var tvQueueTitle: TextView
    private lateinit var btnCloseQueue: Button
    private lateinit var rvQueue: RecyclerView
    private lateinit var queueAdapter: QueueAdapter

    private lateinit var overlayCopy: FrameLayout
    private lateinit var tvCopyStatus: TextView
    private lateinit var tvCopyFileName: TextView
    private lateinit var btnCancelCopy: Button

    // Start Screen Views
    private lateinit var tvStartStatus: TextView

    // Home Screen Views
    private lateinit var tvHomeDeviceName: TextView
    private lateinit var btnHomeTheme: Button
    private lateinit var btnPhotos: Button
    private lateinit var btnVideos: Button
    private lateinit var btnAudio: Button

    // Photo Browser Views
    private lateinit var rvPhotos: RecyclerView
    private lateinit var tvPhotosCount: TextView
    private lateinit var tvEmptyPhotos: TextView
    private lateinit var btnPhotosSort: Button
    private lateinit var btnPhotosViewMode: Button
    private lateinit var btnPhotosTheme: Button
    private lateinit var photoAdapter: PhotoAdapter
    private var photoViewMode = ViewMode.GRID
    private var photoSortMode = SortMode.DEFAULT
    private var displayedPhotoList: MutableList<PtpMediaItem> = mutableListOf()

    // Photo Viewer Views
    private lateinit var ivFullPhoto: ImageView
    private lateinit var progressPhoto: ProgressBar
    private lateinit var tvPhotoError: TextView
    private var currentPhotoIndex = 0
    private var photoLoadJob: Job? = null

    // Video Browser Views
    private lateinit var rvVideos: RecyclerView
    private lateinit var tvVideosCount: TextView
    private lateinit var tvEmptyVideos: TextView
    private lateinit var btnVideosSort: Button
    private lateinit var btnVideosViewMode: Button
    private lateinit var btnVideosTheme: Button
    private lateinit var videoAdapter: VideoAdapter
    private var videoViewMode = ViewMode.GRID
    private var videoSortMode = SortMode.DEFAULT
    private var displayedVideoList: MutableList<PtpMediaItem> = mutableListOf()

    // Video Player Views
    private lateinit var videoView: DirectVideoView
    private lateinit var layoutVideoBuffering: View
    private lateinit var tvBuffering: TextView
    private lateinit var tvVideoError: TextView
    private lateinit var layoutVideoTopBar: View
    private lateinit var tvVideoBadgeFormat: TextView
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvVideoHudSpeed: TextView
    private lateinit var tvVideoHudLoop: TextView
    private lateinit var layoutVideoControls: View
    private lateinit var tvVideoTimeCurrent: TextView
    private lateinit var tvVideoTimeTotal: TextView
    private lateinit var sbVideoSeek: SeekBar

    // Video Player Buttons
    private lateinit var btnVideoPrev: Button
    private lateinit var btnVideoRewind10: Button
    private lateinit var btnVideoPlayPause: Button
    private lateinit var btnVideoForward10: Button
    private lateinit var btnVideoNext: Button
    private lateinit var btnVideoSubs: Button
    private lateinit var btnVideoAudioTrack: Button
    private lateinit var btnVideoSpeed: Button
    private lateinit var btnVideoLoop: Button
    private lateinit var btnVideoBgPlay: Button
    private lateinit var btnVideoQueue: Button
    private lateinit var btnVideoTheme: Button

    private var videoProgressJob: Job? = null
    private var currentVideoIndex = -1
    private var isVideoTracking = false
    private var currentPlaybackSpeed: Float = 1.0f
    private var currentVideoSessionId: Long = 0L

    // Storage Permission Handling for TV copy
    private var storagePermissionCallback: ((Boolean) -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        storagePermissionCallback?.invoke(isGranted)
        storagePermissionCallback = null
    }

    // Video HUD Auto-hide Handler
    private val hudHandler = Handler(Looper.getMainLooper())
    private val hideVideoHudRunnable = Runnable { hideVideoHud() }
    private val hideAudioHudRunnable = Runnable { hideAudioHud() }

    // Audio Browser Views
    private lateinit var rvAudio: RecyclerView
    private lateinit var tvAudioCount: TextView
    private lateinit var tvEmptyAudio: TextView
    private lateinit var btnAudioSort: Button
    private lateinit var btnAudioViewMode: Button
    private lateinit var btnAudioTheme: Button
    private lateinit var audioAdapter: AudioAdapter
    private var audioViewMode = ViewMode.GRID
    private var audioSortMode = SortMode.DEFAULT
    private var displayedAudioList: MutableList<PtpMediaItem> = mutableListOf()

    // Audio Player Views
    private lateinit var ivAudioAmbientBg: ImageView
    private lateinit var ivAudioPlayerArt: ImageView
    private lateinit var tvAudioPlayerTitle: TextView
    private lateinit var audioEqualizerView: EqualizerView
    private lateinit var tvAudioPlayerStatus: TextView
    private lateinit var tvAudioTimeCurrent: TextView
    private lateinit var tvAudioTimeTotal: TextView
    private lateinit var progressAudioSeek: SeekBar
    private lateinit var layoutAudioBuffering: View
    private lateinit var tvAudioBuffering: TextView
    private lateinit var tvAudioError: TextView
    private lateinit var layoutAudioControls: View

    // Audio Player Buttons
    private lateinit var btnAudioPrev: Button
    private lateinit var btnAudioRewind10: Button
    private lateinit var btnAudioPlayPause: Button
    private lateinit var btnAudioForward10: Button
    private lateinit var btnAudioNext: Button
    private lateinit var btnAudioLoop: Button
    private lateinit var btnAudioShuffle: Button
    private lateinit var btnAudioBgPlay: Button
    private lateinit var btnAudioQueue: Button
    private lateinit var btnAudioPlayerTheme: Button

    private var audioPlayer: MediaPlayer? = null
    private var audioCachingJob: Job? = null
    private var audioProgressJob: Job? = null
    private var currentAudioIndex = -1
    private var isAudioTracking = false
    private var currentAudioSessionId: Long = 0L

    // Playback state configurations
    private var videoLoopMode: LoopMode = LoopMode.OFF
    private var audioLoopMode: LoopMode = LoopMode.OFF
    private var isAudioShuffleEnabled: Boolean = false
    private var isBackgroundPlayEnabled: Boolean = false

    private var currentScreen: Screen = Screen.START

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ThemeManager.init(this)
        initViews()
        initServices()
        initMediaSession()

        ThemeManager.addListener { theme ->
            applyAppTheme(theme)
        }
        applyAppTheme(ThemeManager.getTheme())

        showScreen(Screen.START)
    }

    private fun applyAppTheme(theme: AppTheme) {
        ThemeManager.applyToSeekBar(sbVideoSeek, theme)
        ThemeManager.applyToSeekBar(progressAudioSeek, theme)
        ThemeManager.applyToPrimaryButton(btnVideoPlayPause, theme)
        ThemeManager.applyToPrimaryButton(btnAudioPlayPause, theme)
        ThemeManager.applyToBadge(tvVideoBadgeFormat, theme)
        ThemeManager.applyToBadge(tvVideoHudSpeed, theme)
        audioEqualizerView.setThemeColor(theme.accentColor)
    }

    private fun showThemePicker() {
        ThemeSelectionDialog.show(this) { theme ->
            applyAppTheme(theme)
        }
    }

    private fun initViews() {
        screenStart = findViewById(R.id.screen_start)
        screenHome = findViewById(R.id.screen_home)
        screenPhotoBrowser = findViewById(R.id.screen_photo_browser)
        screenPhotoViewer = findViewById(R.id.screen_photo_viewer)
        screenVideoBrowser = findViewById(R.id.screen_video_browser)
        screenVideoPlayer = findViewById(R.id.screen_video_player)
        screenAudioBrowser = findViewById(R.id.screen_audio_browser)
        screenAudioPlayer = findViewById(R.id.screen_audio_player)

        // Overlays
        overlayQueue = findViewById(R.id.overlay_queue)
        tvQueueTitle = findViewById(R.id.tv_queue_title)
        btnCloseQueue = findViewById(R.id.btn_close_queue)
        rvQueue = findViewById(R.id.rv_queue)
        rvQueue.layoutManager = LinearLayoutManager(this)

        overlayCopy = findViewById(R.id.overlay_copy)
        tvCopyStatus = findViewById(R.id.tv_copy_status)
        tvCopyFileName = findViewById(R.id.tv_copy_file_name)
        btnCancelCopy = findViewById(R.id.btn_cancel_copy)

        btnCloseQueue.setOnClickListener { overlayQueue.visibility = View.GONE }
        btnCancelCopy.setOnClickListener {
            contextMenuHelper.cancelCopy()
            overlayCopy.visibility = View.GONE
        }

        tvStartStatus = findViewById(R.id.tv_start_status)
        tvHomeDeviceName = findViewById(R.id.tv_home_device_name)
        btnHomeTheme = findViewById(R.id.btn_home_theme)
        btnPhotos = findViewById(R.id.btn_photos)
        btnVideos = findViewById(R.id.btn_videos)
        btnAudio = findViewById(R.id.btn_audio)

        btnHomeTheme.setOnClickListener { showThemePicker() }

        // Home button actions
        btnPhotos.setOnClickListener {
            showScreen(Screen.PHOTO_BROWSER)
            rvPhotos.requestFocus()
        }
        btnVideos.setOnClickListener {
            showScreen(Screen.VIDEO_BROWSER)
            rvVideos.requestFocus()
        }
        btnAudio.setOnClickListener {
            showScreen(Screen.AUDIO_BROWSER)
            rvAudio.requestFocus()
        }

        // Photo Browser
        rvPhotos = findViewById(R.id.rv_photos)
        tvPhotosCount = findViewById(R.id.tv_photos_count)
        tvEmptyPhotos = findViewById(R.id.tv_empty_photos)
        btnPhotosSort = findViewById(R.id.btn_photos_sort)
        btnPhotosViewMode = findViewById(R.id.btn_photos_view_mode)
        btnPhotosTheme = findViewById(R.id.btn_photos_theme)
        setupPhotosBrowserControls()

        // Photo Viewer
        ivFullPhoto = findViewById(R.id.iv_full_photo)
        progressPhoto = findViewById(R.id.progress_photo)
        tvPhotoError = findViewById(R.id.tv_photo_error)

        // Video Browser
        rvVideos = findViewById(R.id.rv_videos)
        tvVideosCount = findViewById(R.id.tv_videos_count)
        tvEmptyVideos = findViewById(R.id.tv_empty_videos)
        btnVideosSort = findViewById(R.id.btn_videos_sort)
        btnVideosViewMode = findViewById(R.id.btn_videos_view_mode)
        btnVideosTheme = findViewById(R.id.btn_videos_theme)
        setupVideosBrowserControls()

        // Video Player UI
        videoView = findViewById(R.id.video_view)
        layoutVideoBuffering = findViewById(R.id.layout_video_buffering)
        tvBuffering = findViewById(R.id.tv_buffering)
        tvVideoError = findViewById(R.id.tv_video_error)
        layoutVideoTopBar = findViewById(R.id.layout_video_top_bar)
        tvVideoBadgeFormat = findViewById(R.id.tv_video_badge_format)
        tvVideoTitle = findViewById(R.id.tv_video_title)
        tvVideoHudSpeed = findViewById(R.id.tv_video_hud_speed)
        tvVideoHudLoop = findViewById(R.id.tv_video_hud_loop)
        layoutVideoControls = findViewById(R.id.layout_video_controls)
        tvVideoTimeCurrent = findViewById(R.id.tv_video_time_current)
        tvVideoTimeTotal = findViewById(R.id.tv_video_time_total)
        sbVideoSeek = findViewById(R.id.sb_video_seek)

        btnVideoPrev = findViewById(R.id.btn_video_prev)
        btnVideoRewind10 = findViewById(R.id.btn_video_rewind10)
        btnVideoPlayPause = findViewById(R.id.btn_video_play_pause)
        btnVideoForward10 = findViewById(R.id.btn_video_forward10)
        btnVideoNext = findViewById(R.id.btn_video_next)
        btnVideoSubs = findViewById(R.id.btn_video_subs)
        btnVideoAudioTrack = findViewById(R.id.btn_video_audio_track)
        btnVideoSpeed = findViewById(R.id.btn_video_speed)
        btnVideoLoop = findViewById(R.id.btn_video_loop)
        btnVideoBgPlay = findViewById(R.id.btn_video_bg_play)
        btnVideoQueue = findViewById(R.id.btn_video_queue)
        btnVideoTheme = findViewById(R.id.btn_video_theme)

        setupVideoControls()

        // Audio Browser
        rvAudio = findViewById(R.id.rv_audio)
        tvAudioCount = findViewById(R.id.tv_audio_count)
        tvEmptyAudio = findViewById(R.id.tv_empty_audio)
        btnAudioSort = findViewById(R.id.btn_audio_sort)
        btnAudioViewMode = findViewById(R.id.btn_audio_view_mode)
        btnAudioTheme = findViewById(R.id.btn_audio_theme)
        setupAudioBrowserControls()

        // Audio Player
        ivAudioAmbientBg = findViewById(R.id.iv_audio_ambient_bg)
        ivAudioPlayerArt = findViewById(R.id.iv_audio_player_art)
        tvAudioPlayerTitle = findViewById(R.id.tv_audio_player_title)
        audioEqualizerView = findViewById(R.id.audio_equalizer_view)
        tvAudioPlayerStatus = findViewById(R.id.tv_audio_player_status)
        tvAudioTimeCurrent = findViewById(R.id.tv_audio_time_current)
        tvAudioTimeTotal = findViewById(R.id.tv_audio_time_total)
        progressAudioSeek = findViewById(R.id.progress_audio_seek)
        layoutAudioBuffering = findViewById(R.id.layout_audio_buffering)
        tvAudioBuffering = findViewById(R.id.tv_audio_buffering)
        tvAudioError = findViewById(R.id.tv_audio_error)
        layoutAudioControls = findViewById(R.id.layout_audio_controls)

        btnAudioPrev = findViewById(R.id.btn_audio_prev)
        btnAudioRewind10 = findViewById(R.id.btn_audio_rewind10)
        btnAudioPlayPause = findViewById(R.id.btn_audio_play_pause)
        btnAudioForward10 = findViewById(R.id.btn_audio_forward10)
        btnAudioNext = findViewById(R.id.btn_audio_next)
        btnAudioLoop = findViewById(R.id.btn_audio_loop)
        btnAudioShuffle = findViewById(R.id.btn_audio_shuffle)
        btnAudioBgPlay = findViewById(R.id.btn_audio_bg_play)
        btnAudioQueue = findViewById(R.id.btn_audio_queue)
        btnAudioPlayerTheme = findViewById(R.id.btn_audio_player_theme)

        setupAudioControls()
        updateShuffleButtonUi()

        // Attach TV Focus Animation to all interactive views
        val interactiveViews = listOf(
            btnPhotos, btnVideos, btnAudio, btnHomeTheme,
            btnPhotosSort, btnPhotosViewMode, btnPhotosTheme,
            btnVideosSort, btnVideosViewMode, btnVideosTheme,
            btnAudioSort, btnAudioViewMode, btnAudioTheme,
            btnVideoPrev, btnVideoRewind10, btnVideoPlayPause, btnVideoForward10, btnVideoNext,
            btnVideoSubs, btnVideoAudioTrack, btnVideoSpeed, btnVideoLoop, btnVideoBgPlay, btnVideoQueue, btnVideoTheme,
            btnAudioPrev, btnAudioRewind10, btnAudioPlayPause, btnAudioForward10, btnAudioNext,
            btnAudioLoop, btnAudioShuffle, btnAudioBgPlay, btnAudioQueue, btnAudioPlayerTheme,
            btnCloseQueue, btnCancelCopy
        )
        for (v in interactiveViews) {
            TvFocusAnimator.attach(v)
        }
    }

    private fun initServices() {
        repository = PtpMediaRepository(this)
        legacyPhotoThumbLoader = PhotoThumbnailLoader(lifecycleScope) { repository.client }
        mediaThumbnailLoader = MediaThumbnailLoader(this, lifecycleScope) { repository.client }
        videoPlaybackManager = VideoPlaybackManager(this, lifecycleScope)
        audioCacheManager = AudioCacheManager(this)

        contextMenuHelper = MediaContextMenuHelper(
            context = this,
            scope = lifecycleScope,
            ptpClientProvider = { repository.client },
            onRequestStoragePermission = { callback ->
                storagePermissionCallback = callback
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    callback(true)
                }
            },
            onCopyStart = { filename ->
                tvCopyStatus.text = getString(R.string.copying_file)
                tvCopyFileName.text = filename
                overlayCopy.visibility = View.VISIBLE
                btnCancelCopy.requestFocus()
            },
            onCopyProgress = { written, total ->
                val mb = written / (1024.0 * 1024.0)
                if (total > 0) {
                    val totalMb = total / (1024.0 * 1024.0)
                    val pct = ((written * 100) / total).toInt()
                    tvCopyStatus.text = String.format("Copying %.1f / %.1f MB (%d%%)...", mb, totalMb, pct)
                } else {
                    tvCopyStatus.text = String.format("Copying %.1f MB...", mb)
                }
            },
            onCopyComplete = { success, msg ->
                overlayCopy.visibility = View.GONE
                if (success) {
                    Toast.makeText(this, "${getString(R.string.copy_success)}\n$msg", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "${getString(R.string.copy_failed)}: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        )

        photoAdapter = PhotoAdapter(
            items = displayedPhotoList,
            thumbnailLoader = legacyPhotoThumbLoader,
            isListView = { photoViewMode == ViewMode.LIST },
            onItemClicked = { index ->
                currentPhotoIndex = index
                showScreen(Screen.PHOTO_VIEWER)
                loadSelectedPhoto(index)
            },
            onItemMenu = { item ->
                contextMenuHelper.showContextMenu(item)
            }
        )
        rvPhotos.adapter = photoAdapter

        videoAdapter = VideoAdapter(
            items = displayedVideoList,
            thumbnailLoader = mediaThumbnailLoader,
            isListView = { videoViewMode == ViewMode.LIST },
            onItemBound = { item ->
                loadMediaMetadata(item)
            },
            onItemClicked = { item ->
                val idx = displayedVideoList.indexOf(item)
                if (idx != -1) {
                    playVideoAtIndex(idx)
                }
            },
            onItemMenu = { item ->
                contextMenuHelper.showContextMenu(item)
            }
        )
        rvVideos.adapter = videoAdapter

        audioAdapter = AudioAdapter(
            items = displayedAudioList,
            thumbnailLoader = mediaThumbnailLoader,
            isListView = { audioViewMode == ViewMode.LIST },
            onItemBound = { item ->
                loadMediaMetadata(item)
            },
            onItemClicked = { item ->
                val idx = displayedAudioList.indexOf(item)
                if (idx != -1) {
                    playAudioAtIndex(idx)
                }
            },
            onItemMenu = { item ->
                contextMenuHelper.showContextMenu(item)
            }
        )
        rvAudio.adapter = audioAdapter

        queueAdapter = QueueAdapter(
            items = emptyList(),
            currentIndex = -1,
            onItemClicked = { clickedIndex ->
                overlayQueue.visibility = View.GONE
                if (currentScreen == Screen.VIDEO_PLAYER) {
                    playVideoAtIndex(clickedIndex)
                } else if (currentScreen == Screen.AUDIO_PLAYER) {
                    playAudioAtIndex(clickedIndex)
                }
            }
        )
        rvQueue.adapter = queueAdapter

        usbHostManager = UsbHostManager(this) { state ->
            handleUsbState(state)
        }
    }

    private fun setupPhotosBrowserControls() {
        rvPhotos.layoutManager = GridLayoutManager(this, 4)
        btnPhotosViewMode.setOnClickListener {
            val isNowList = (photoViewMode == ViewMode.GRID)
            photoViewMode = if (isNowList) ViewMode.LIST else ViewMode.GRID
            btnPhotosViewMode.text = if (isNowList) "LIST" else "GRID"
            btnPhotosViewMode.setCompoundDrawablesWithIntrinsicBounds(
                if (isNowList) R.drawable.ic_view_list else R.drawable.ic_view_grid, 0, 0, 0
            )
            rvPhotos.recycledViewPool.clear()
            rvPhotos.layoutManager = if (isNowList) LinearLayoutManager(this) else GridLayoutManager(this, 4)
            photoAdapter.notifyDataSetChanged()
        }
        btnPhotosSort.setOnClickListener {
            showSortDialog("Photos", photoSortMode) { selected ->
                photoSortMode = selected
                btnPhotosSort.text = selected.name.replace('_', ' ')
                applySorting()
            }
        }
        btnPhotosTheme.setOnClickListener { showThemePicker() }
    }

    private fun setupVideosBrowserControls() {
        rvVideos.layoutManager = GridLayoutManager(this, 4)
        btnVideosViewMode.setOnClickListener {
            val isNowList = (videoViewMode == ViewMode.GRID)
            videoViewMode = if (isNowList) ViewMode.LIST else ViewMode.GRID
            btnVideosViewMode.text = if (isNowList) "LIST" else "GRID"
            btnVideosViewMode.setCompoundDrawablesWithIntrinsicBounds(
                if (isNowList) R.drawable.ic_view_list else R.drawable.ic_view_grid, 0, 0, 0
            )
            rvVideos.recycledViewPool.clear()
            rvVideos.layoutManager = if (isNowList) LinearLayoutManager(this) else GridLayoutManager(this, 4)
            videoAdapter.notifyDataSetChanged()
        }
        btnVideosSort.setOnClickListener {
            showSortDialog("Videos", videoSortMode) { selected ->
                videoSortMode = selected
                btnVideosSort.text = selected.name.replace('_', ' ')
                applySorting()
            }
        }
        btnVideosTheme.setOnClickListener { showThemePicker() }
    }

    private fun setupAudioBrowserControls() {
        rvAudio.layoutManager = GridLayoutManager(this, 4)
        btnAudioViewMode.setOnClickListener {
            val isNowList = (audioViewMode == ViewMode.GRID)
            audioViewMode = if (isNowList) ViewMode.LIST else ViewMode.GRID
            btnAudioViewMode.text = if (isNowList) "LIST" else "GRID"
            btnAudioViewMode.setCompoundDrawablesWithIntrinsicBounds(
                if (isNowList) R.drawable.ic_view_list else R.drawable.ic_view_grid, 0, 0, 0
            )
            rvAudio.recycledViewPool.clear()
            rvAudio.layoutManager = if (isNowList) LinearLayoutManager(this) else GridLayoutManager(this, 4)
            audioAdapter.notifyDataSetChanged()
        }
        btnAudioSort.setOnClickListener {
            showSortDialog("Audio", audioSortMode) { selected ->
                audioSortMode = selected
                btnAudioSort.text = selected.name.replace('_', ' ')
                applySorting()
            }
        }
        btnAudioTheme.setOnClickListener { showThemePicker() }
    }

    private fun showSortDialog(title: String, current: SortMode, onSelected: (SortMode) -> Unit) {
        val modes = SortMode.values()
        val names = arrayOf("Default", "Name (A-Z)", "Size (Largest First)", "Newest First")
        val currentIdx = modes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Sort $title")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                onSelected(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun applySorting() {
        displayedPhotoList.clear()
        displayedPhotoList.addAll(sortItems(repository.photoItems, photoSortMode))
        photoAdapter.notifyDataSetChanged()

        displayedVideoList.clear()
        displayedVideoList.addAll(sortItems(repository.videoItems, videoSortMode))
        videoAdapter.notifyDataSetChanged()

        displayedAudioList.clear()
        displayedAudioList.addAll(sortItems(repository.audioItems, audioSortMode))
        audioAdapter.notifyDataSetChanged()
    }

    private fun sortItems(items: List<PtpMediaItem>, sortMode: SortMode): List<PtpMediaItem> {
        return when (sortMode) {
            SortMode.DEFAULT -> items.toList()
            SortMode.NAME_ASC -> items.sortedBy { it.displayName.lowercase() }
            SortMode.SIZE_DESC -> items.sortedByDescending { it.sizeBytes }
            SortMode.DATE_DESC -> items.sortedByDescending { it.handle }
        }
    }

    private fun initMediaSession() {
        try {
            mediaSession = MediaSessionCompat(this, "DirectUSB_MediaSession").apply {
                setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() { handlePlayPauseAction() }
                    override fun onPause() { handlePlayPauseAction() }
                    override fun onSkipToNext() { handleNextAction() }
                    override fun onSkipToPrevious() { handlePreviousAction() }
                    override fun onFastForward() { handleForward10Action() }
                    override fun onRewind() { handleRewind10Action() }
                    override fun onStop() {
                        if (currentScreen == Screen.VIDEO_PLAYER) {
                            stopAndClearVideoPlayer()
                        } else if (currentScreen == Screen.AUDIO_PLAYER) {
                            stopAndClearAudioPlayer()
                        }
                    }
                })
                isActive = true
            }
            updateMediaSessionState(PlaybackStateCompat.STATE_NONE, 0L)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Could not initialize MediaSession", e)
        }
    }

    private fun updateMediaSessionState(state: Int, position: Long) {
        try {
            val playbackState = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(state, position, currentPlaybackSpeed)
                .build()
            mediaSession?.setPlaybackState(playbackState)
        } catch (_: Exception) {}
    }

    private fun setupVideoControls() {
        btnVideoPlayPause.setOnClickListener {
            resetVideoHudTimer()
            handlePlayPauseAction()
        }
        btnVideoRewind10.setOnClickListener {
            resetVideoHudTimer()
            handleRewind10Action()
        }
        btnVideoForward10.setOnClickListener {
            resetVideoHudTimer()
            handleForward10Action()
        }
        btnVideoPrev.setOnClickListener {
            resetVideoHudTimer()
            handlePreviousAction()
        }
        btnVideoNext.setOnClickListener {
            resetVideoHudTimer()
            handleNextAction()
        }

        btnVideoSpeed.setOnClickListener {
            resetVideoHudTimer()
            cyclePlaybackSpeed()
        }

        btnVideoLoop.setOnClickListener {
            resetVideoHudTimer()
            videoLoopMode = when (videoLoopMode) {
                LoopMode.OFF -> LoopMode.SINGLE
                LoopMode.SINGLE -> LoopMode.ALL
                LoopMode.ALL -> LoopMode.OFF
            }
            updateLoopButtonUi()
        }

        btnVideoBgPlay.setOnClickListener {
            resetVideoHudTimer()
            isBackgroundPlayEnabled = !isBackgroundPlayEnabled
            updateBgPlayButtonUi()
        }

        btnVideoQueue.setOnClickListener {
            resetVideoHudTimer()
            showQueueOverlay(displayedVideoList, currentVideoIndex)
        }

        btnVideoSubs.setOnClickListener {
            resetVideoHudTimer()
            showSubtitlesDialog()
        }

        btnVideoAudioTrack.setOnClickListener {
            resetVideoHudTimer()
            showAudioTrackDialog()
        }

        btnVideoTheme.setOnClickListener {
            resetVideoHudTimer()
            showThemePicker()
        }

        sbVideoSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && videoView.duration > 0) {
                    resetVideoHudTimer()
                    val targetMs = (progress.toLong() * videoView.duration / 1000L).toInt()
                    tvVideoTimeCurrent.text = formatTime(targetMs)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isVideoTracking = true
                resetVideoHudTimer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isVideoTracking = false
                resetVideoHudTimer()
                seekBar?.let {
                    if (videoView.duration > 0) {
                        val targetMs = (it.progress.toLong() * videoView.duration / 1000L).toInt()
                        videoView.seekTo(targetMs)
                    }
                }
            }
        })
    }

    private fun cyclePlaybackSpeed() {
        val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)
        val currentIdx = speeds.indexOfFirst { kotlin.math.abs(it - currentPlaybackSpeed) < 0.05f }.let { if (it < 0) 0 else it }
        val nextIdx = (currentIdx + 1) % speeds.size
        setPlaybackSpeed(speeds[nextIdx])
    }

    private fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        val label = "${speed}x"
        btnVideoSpeed.text = label
        tvVideoHudSpeed.text = label
        videoView.setPlaybackSpeed(speed)
    }

    private fun setupAudioControls() {
        btnAudioPlayPause.setOnClickListener {
            resetAudioHudTimer()
            handlePlayPauseAction()
        }
        btnAudioRewind10.setOnClickListener {
            resetAudioHudTimer()
            handleRewind10Action()
        }
        btnAudioForward10.setOnClickListener {
            resetAudioHudTimer()
            handleForward10Action()
        }
        btnAudioPrev.setOnClickListener {
            resetAudioHudTimer()
            handlePreviousAction()
        }
        btnAudioNext.setOnClickListener {
            resetAudioHudTimer()
            handleNextAction()
        }

        btnAudioLoop.setOnClickListener {
            resetAudioHudTimer()
            audioLoopMode = when (audioLoopMode) {
                LoopMode.OFF -> LoopMode.SINGLE
                LoopMode.SINGLE -> LoopMode.ALL
                LoopMode.ALL -> LoopMode.OFF
            }
            updateLoopButtonUi()
        }

        btnAudioShuffle.setOnClickListener {
            resetAudioHudTimer()
            isAudioShuffleEnabled = !isAudioShuffleEnabled
            updateShuffleButtonUi()
            Toast.makeText(this, if (isAudioShuffleEnabled) "Shuffle: ON" else "Shuffle: OFF", Toast.LENGTH_SHORT).show()
        }

        btnAudioBgPlay.setOnClickListener {
            resetAudioHudTimer()
            isBackgroundPlayEnabled = !isBackgroundPlayEnabled
            updateBgPlayButtonUi()
        }

        btnAudioQueue.setOnClickListener {
            resetAudioHudTimer()
            showQueueOverlay(displayedAudioList, currentAudioIndex)
        }

        btnAudioPlayerTheme.setOnClickListener {
            resetAudioHudTimer()
            showThemePicker()
        }

        progressAudioSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                audioPlayer?.let { mp ->
                    if (fromUser && mp.duration > 0) {
                        resetAudioHudTimer()
                        val targetMs = (progress.toLong() * mp.duration / 1000L).toInt()
                        tvAudioTimeCurrent.text = formatTime(targetMs)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isAudioTracking = true
                resetAudioHudTimer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isAudioTracking = false
                resetAudioHudTimer()
                audioPlayer?.let { mp ->
                    seekBar?.let { sb ->
                        if (mp.duration > 0) {
                            val targetMs = (sb.progress.toLong() * mp.duration / 1000L).toInt()
                            mp.seekTo(targetMs)
                        }
                    }
                }
            }
        })
    }

    // Auto-hide OSD/HUD methods
    private fun showVideoHud() {
        layoutVideoTopBar.visibility = View.VISIBLE
        layoutVideoControls.visibility = View.VISIBLE
        resetVideoHudTimer()
    }

    private fun hideVideoHud() {
        if (currentScreen == Screen.VIDEO_PLAYER && videoView.isPlaying && !isVideoTracking && overlayQueue.visibility != View.VISIBLE) {
            layoutVideoTopBar.visibility = View.GONE
            layoutVideoControls.visibility = View.GONE
        }
    }

    private fun resetVideoHudTimer() {
        hudHandler.removeCallbacks(hideVideoHudRunnable)
        if (currentScreen == Screen.VIDEO_PLAYER) {
            hudHandler.postDelayed(hideVideoHudRunnable, 4500)
        }
    }

    private fun showAudioHud() {
        layoutAudioControls.visibility = View.VISIBLE
        resetAudioHudTimer()
    }

    private fun hideAudioHud() {
        if (currentScreen == Screen.AUDIO_PLAYER && audioPlayer?.isPlaying == true && !isAudioTracking && overlayQueue.visibility != View.VISIBLE) {
            layoutAudioControls.visibility = View.GONE
        }
    }

    private fun resetAudioHudTimer() {
        hudHandler.removeCallbacks(hideAudioHudRunnable)
        if (currentScreen == Screen.AUDIO_PLAYER) {
            hudHandler.postDelayed(hideAudioHudRunnable, 4000)
        }
    }

    private fun updateLoopButtonUi() {
        val vText = when (videoLoopMode) {
            LoopMode.OFF -> "LOOP"
            LoopMode.SINGLE -> "LOOP: 1"
            LoopMode.ALL -> "LOOP: ALL"
        }
        btnVideoLoop.text = vText
        tvVideoHudLoop.text = when (videoLoopMode) {
            LoopMode.OFF -> "LOOP: OFF"
            LoopMode.SINGLE -> "LOOP: SINGLE"
            LoopMode.ALL -> "LOOP: ALL"
        }

        val aText = when (audioLoopMode) {
            LoopMode.OFF -> "LOOP"
            LoopMode.SINGLE -> "LOOP: 1"
            LoopMode.ALL -> "LOOP: ALL"
        }
        btnAudioLoop.text = aText
    }

    private fun updateShuffleButtonUi() {
        btnAudioShuffle.text = if (isAudioShuffleEnabled) "SHUFFLE: ON" else "SHUFFLE"
    }

    private fun updateBgPlayButtonUi() {
        val label = if (isBackgroundPlayEnabled) "BG: ON" else "BG: OFF"
        btnVideoBgPlay.text = label
        btnAudioBgPlay.text = label
    }

    private fun showQueueOverlay(items: List<PtpMediaItem>, activeIndex: Int) {
        hudHandler.removeCallbacks(hideVideoHudRunnable)
        hudHandler.removeCallbacks(hideAudioHudRunnable)
        queueAdapter.updateItems(items, activeIndex)
        overlayQueue.visibility = View.VISIBLE
        btnCloseQueue.requestFocus()
    }

    private fun showSubtitlesDialog() {
        try {
            val subTracks = videoView.getSubtitleTracks()
            if (subTracks.size <= 1) {
                Toast.makeText(this, "No subtitle tracks embedded in this video", Toast.LENGTH_SHORT).show()
                return
            }

            val names = subTracks.map { track ->
                val prefix = if (track.isSelected) "✓ " else "  "
                "$prefix${track.label}"
            }.toTypedArray()

            val selectedIdx = subTracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.subtitles_track))
                .setSingleChoiceItems(names, selectedIdx) { dialog, which ->
                    val chosen = subTracks[which]
                    try {
                        videoView.selectSubtitleTrack(chosen.index)
                        btnVideoSubs.text = if (chosen.index == -1) "SUBS" else chosen.label
                    } catch (e: Exception) {
                        Log.w(PtpConstants.TAG, "Subtitle track selection failed", e)
                    }
                    dialog.dismiss()
                    btnVideoSubs.requestFocus()
                }
                .setOnDismissListener { btnVideoSubs.requestFocus() }
                .setNegativeButton("CANCEL", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Subtitles not supported for this media", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAudioTrackDialog() {
        try {
            val audioTracks = videoView.getAudioTracks()
            if (audioTracks.isEmpty()) {
                Toast.makeText(this, "Audio tracks not available", Toast.LENGTH_SHORT).show()
                return
            }

            if (audioTracks.size == 1) {
                val track = audioTracks[0]
                val desc = if (track.isAc3OrDolby) "Dolby Digital (${track.label})" else track.label
                Toast.makeText(this, "Active stream: $desc", Toast.LENGTH_SHORT).show()
                return
            }

            val names = audioTracks.map { track ->
                val prefix = if (track.isSelected) "✓ " else "  "
                val ac3Tag = if (track.isAc3OrDolby) " [DOLBY/AC-3]" else ""
                "$prefix${track.label}$ac3Tag"
            }.toTypedArray()

            val selectedIdx = audioTracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.audio_track))
                .setSingleChoiceItems(names, selectedIdx) { dialog, which ->
                    try {
                        videoView.selectAudioTrack(which)
                        val chosen = audioTracks[which]
                        btnVideoAudioTrack.text = if (chosen.isAc3OrDolby) "DOLBY" else (chosen.language.ifBlank { "Track ${which + 1}" })
                    } catch (e: Exception) {
                        Log.w(PtpConstants.TAG, "Audio track switch failed", e)
                    }
                    dialog.dismiss()
                    btnVideoAudioTrack.requestFocus()
                }
                .setOnDismissListener { btnVideoAudioTrack.requestFocus() }
                .setNegativeButton("CANCEL", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Audio tracks query not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePlayPauseAction() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            if (videoView.isPlaying) {
                videoView.pause()
                btnVideoPlayPause.text = "PLAY"
                btnVideoPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play_arrow, 0, 0, 0)
                showVideoHud()
                updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED, videoView.currentPosition.toLong())
            } else {
                videoView.start()
                btnVideoPlayPause.text = "PAUSE"
                btnVideoPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0)
                resetVideoHudTimer()
                updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, videoView.currentPosition.toLong())
            }
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            audioPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        mp.pause()
                        btnAudioPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play_arrow, 0, 0, 0)
                        audioEqualizerView.setPlaying(false)
                        tvAudioPlayerStatus.text = "[ PAUSED ]"
                        showAudioHud()
                        updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED, mp.currentPosition.toLong())
                    } else {
                        mp.start()
                        btnAudioPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0)
                        audioEqualizerView.setPlaying(true)
                        tvAudioPlayerStatus.text = "[ PLAYING ]"
                        resetAudioHudTimer()
                        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, mp.currentPosition.toLong())
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleRewind10Action() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            val cur = videoView.currentPosition
            val dur = videoView.duration
            val target = (cur - 10000).coerceAtLeast(0)
            videoView.seekTo(target)
            if (dur > 0) {
                sbVideoSeek.progress = ((target.toLong() * 1000L) / dur).toInt()
            }
            tvVideoTimeCurrent.text = formatTime(target)
            showVideoHud()
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            audioPlayer?.let { mp ->
                try {
                    val cur = mp.currentPosition
                    val dur = mp.duration
                    val target = (cur - 10000).coerceAtLeast(0)
                    mp.seekTo(target)
                    if (dur > 0) {
                        progressAudioSeek.progress = ((target.toLong() * 1000L) / dur).toInt()
                    }
                    tvAudioTimeCurrent.text = formatTime(target)
                    showAudioHud()
                } catch (_: Exception) {}
            }
        }
    }

    private fun handleForward10Action() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            val cur = videoView.currentPosition
            val dur = videoView.duration
            val target = if (dur > 0) (cur + 10000).coerceAtMost(dur) else cur + 10000
            videoView.seekTo(target)
            if (dur > 0) {
                sbVideoSeek.progress = ((target.toLong() * 1000L) / dur).toInt()
            }
            tvVideoTimeCurrent.text = formatTime(target)
            showVideoHud()
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            audioPlayer?.let { mp ->
                try {
                    val cur = mp.currentPosition
                    val dur = mp.duration
                    val target = if (dur > 0) (cur + 10000).coerceAtMost(dur) else cur + 10000
                    mp.seekTo(target)
                    if (dur > 0) {
                        progressAudioSeek.progress = ((target.toLong() * 1000L) / dur).toInt()
                    }
                    tvAudioTimeCurrent.text = formatTime(target)
                    showAudioHud()
                } catch (_: Exception) {}
            }
        }
    }

    private fun handlePreviousAction() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            if (currentVideoIndex > 0) {
                playVideoAtIndex(currentVideoIndex - 1)
            } else if (videoLoopMode == LoopMode.ALL && displayedVideoList.isNotEmpty()) {
                playVideoAtIndex(displayedVideoList.size - 1)
            }
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            if (isAudioShuffleEnabled && displayedAudioList.size > 1) {
                var nextIdx = kotlin.random.Random.nextInt(displayedAudioList.size)
                if (nextIdx == currentAudioIndex) {
                    nextIdx = (nextIdx + 1) % displayedAudioList.size
                }
                playAudioAtIndex(nextIdx)
            } else if (currentAudioIndex > 0) {
                playAudioAtIndex(currentAudioIndex - 1)
            } else if (audioLoopMode == LoopMode.ALL && displayedAudioList.isNotEmpty()) {
                playAudioAtIndex(displayedAudioList.size - 1)
            }
        }
    }

    private fun handleNextAction() {
        if (currentScreen == Screen.VIDEO_PLAYER) {
            if (currentVideoIndex < displayedVideoList.size - 1) {
                playVideoAtIndex(currentVideoIndex + 1)
            } else if (videoLoopMode == LoopMode.ALL && displayedVideoList.isNotEmpty()) {
                playVideoAtIndex(0)
            }
        } else if (currentScreen == Screen.AUDIO_PLAYER) {
            if (isAudioShuffleEnabled && displayedAudioList.size > 1) {
                var nextIdx = kotlin.random.Random.nextInt(displayedAudioList.size)
                if (nextIdx == currentAudioIndex) {
                    nextIdx = (nextIdx + 1) % displayedAudioList.size
                }
                playAudioAtIndex(nextIdx)
            } else if (currentAudioIndex < displayedAudioList.size - 1) {
                playAudioAtIndex(currentAudioIndex + 1)
            } else if (audioLoopMode == LoopMode.ALL && displayedAudioList.isNotEmpty()) {
                playAudioAtIndex(0)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        usbHostManager.start()
    }

    override fun onStop() {
        super.onStop()
        if (!isBackgroundPlayEnabled) {
            stopAndClearVideoPlayer()
            stopAndClearAudioPlayer()
            stopBackgroundService()
        } else {
            val activeTitle = if (currentScreen == Screen.AUDIO_PLAYER && currentAudioIndex in displayedAudioList.indices) {
                displayedAudioList[currentAudioIndex].displayName
            } else if (currentScreen == Screen.VIDEO_PLAYER && currentVideoIndex in displayedVideoList.indices) {
                displayedVideoList[currentVideoIndex].displayName
            } else {
                "Playing media"
            }
            startBackgroundService(activeTitle)
        }

        photoLoadJob?.cancel()
        legacyPhotoThumbLoader.clear()
        mediaThumbnailLoader.clear()
        usbHostManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        hudHandler.removeCallbacks(hideVideoHudRunnable)
        hudHandler.removeCallbacks(hideAudioHudRunnable)
        stopBackgroundService()
        mediaSession?.release()
        videoPlaybackManager.closeCurrentSession()
        audioCacheManager.clearCache()
        lifecycleScope.launch {
            repository.clear()
        }
    }

    private fun startBackgroundService(title: String) {
        try {
            val serviceIntent = Intent(this, BackgroundPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Could not start background playback service", e)
        }
    }

    private fun stopBackgroundService() {
        try {
            val serviceIntent = Intent(this, BackgroundPlayService::class.java)
            stopService(serviceIntent)
        } catch (e: Exception) {
            Log.w(PtpConstants.TAG, "Could not stop background playback service", e)
        }
    }

    private fun handleUsbState(state: UsbConnectionState) {
        when (state) {
            is UsbConnectionState.Idle -> {
                runOnUiThread {
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.GONE
                    tvStartStatus.visibility = View.VISIBLE
                    tvStartStatus.text = getString(R.string.connect_phone)
                }
            }
            is UsbConnectionState.Disconnected -> {
                runOnUiThread {
                    stopAndClearVideoPlayer()
                    stopAndClearAudioPlayer()
                    stopBackgroundService()
                    showScreen(Screen.START)
                    tvStartStatus.visibility = View.VISIBLE
                    tvStartStatus.text = getString(R.string.phone_disconnected)
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.GONE
                    lifecycleScope.launch { repository.clear() }
                }
            }
            is UsbConnectionState.DeviceAttached -> {
                runOnUiThread {
                    showScreen(Screen.START)
                    tvStartStatus.visibility = View.VISIBLE
                    tvStartStatus.text = getString(R.string.ptp_device_detected)
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.VISIBLE
                }
            }
            is UsbConnectionState.PermissionRequired -> {
                runOnUiThread {
                    showScreen(Screen.START)
                    tvStartStatus.visibility = View.VISIBLE
                    tvStartStatus.text = getString(R.string.permission_required)
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.VISIBLE
                }
            }
            is UsbConnectionState.PermissionDenied -> {
                runOnUiThread {
                    showScreen(Screen.START)
                    tvStartStatus.visibility = View.VISIBLE
                    tvStartStatus.text = getString(R.string.permission_denied)
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.GONE
                }
            }
            is UsbConnectionState.Connected -> {
                handlePtpConnected(state.client, state.deviceName)
            }
            is UsbConnectionState.Error -> {
                runOnUiThread {
                    showScreen(Screen.START)
                    tvStartStatus.visibility = View.VISIBLE
                    tvStartStatus.text = state.message
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.GONE
                }
            }
        }
    }

    private fun handlePtpConnected(client: PtpClient, deviceName: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    tvStartStatus.visibility = View.VISIBLE
                    tvStartStatus.text = "Reading storage..."
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.VISIBLE
                }

                val ready = repository.initialize(client, deviceName)

                withContext(Dispatchers.Main) {
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.GONE

                    if (ready) {
                        tvStartStatus.visibility = View.GONE
                        tvHomeDeviceName.text = repository.deviceName.ifBlank { "PTP Phone" }

                        displayedPhotoList.clear()
                        displayedPhotoList.addAll(repository.photoItems)
                        tvPhotosCount.text = "(${repository.photoItems.size})"
                        tvEmptyPhotos.visibility = if (repository.photoItems.isEmpty()) View.VISIBLE else View.GONE
                        photoAdapter.notifyDataSetChanged()

                        displayedVideoList.clear()
                        displayedVideoList.addAll(repository.videoItems)
                        tvVideosCount.text = "(${repository.videoItems.size})"
                        tvEmptyVideos.visibility = if (repository.videoItems.isEmpty()) View.VISIBLE else View.GONE
                        videoAdapter.notifyDataSetChanged()

                        displayedAudioList.clear()
                        displayedAudioList.addAll(repository.audioItems)
                        tvAudioCount.text = "(${repository.audioItems.size})"
                        tvEmptyAudio.visibility = if (repository.audioItems.isEmpty()) View.VISIBLE else View.GONE
                        audioAdapter.notifyDataSetChanged()

                        showScreen(Screen.HOME)
                        btnPhotos.requestFocus()
                    } else {
                        tvStartStatus.visibility = View.VISIBLE
                        tvStartStatus.text = getString(R.string.session_failed)
                    }
                }
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "Error in handlePtpConnected", e)
                withContext(Dispatchers.Main) {
                    tvStartStatus.visibility = View.VISIBLE
                    tvStartStatus.text = getString(R.string.session_failed)
                    findViewById<ProgressBar>(R.id.progress_start).visibility = View.GONE
                }
            }
        }
    }

    private fun loadMediaMetadata(item: PtpMediaItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.fetchMetadataIfNeeded(item)
            withContext(Dispatchers.Main) {
                if (item.isVideo) {
                    val idx = displayedVideoList.indexOfFirst { it.handle == item.handle }
                    if (idx != -1) videoAdapter.notifyItemChanged(idx)
                } else if (item.isAudio) {
                    val idx = displayedAudioList.indexOfFirst { it.handle == item.handle }
                    if (idx != -1) audioAdapter.notifyItemChanged(idx)
                }
            }
        }
    }

    private fun loadSelectedPhoto(index: Int) {
        if (index < 0 || index >= displayedPhotoList.size) return

        val item = displayedPhotoList[index]
        photoLoadJob?.cancel()

        progressPhoto.visibility = View.VISIBLE
        tvPhotoError.visibility = View.GONE
        ivFullPhoto.setImageBitmap(null)

        photoLoadJob = lifecycleScope.launch {
            try {
                val bmp = repository.loadFullPhoto(item.handle)
                if (bmp != null) {
                    ivFullPhoto.setImageBitmap(bmp)
                    progressPhoto.visibility = View.GONE
                } else {
                    showPhotoError()
                }
            } catch (e: Exception) {
                Log.e(PtpConstants.TAG, "Error loading full photo", e)
                showPhotoError()
            }
        }
    }

    private fun showPhotoError() {
        progressPhoto.visibility = View.GONE
        tvPhotoError.visibility = View.VISIBLE
    }

    fun playVideoAtIndex(index: Int) {
        if (index < 0 || index >= displayedVideoList.size) return
        currentVideoIndex = index
        val item = displayedVideoList[index]
        startVideoPlayback(item)
    }

    private fun startVideoPlayback(item: PtpMediaItem) {
        val client = repository.client ?: return
        val sessionId = ++currentVideoSessionId

        videoProgressJob?.cancel()
        videoPlaybackManager.closeCurrentSession()
        videoView.stopPlayback()

        showScreen(Screen.VIDEO_PLAYER)

        // Reset UI state
        tvVideoError.visibility = View.GONE
        tvVideoError.text = ""
        tvVideoTitle.text = item.displayName
        tvVideoBadgeFormat.text = item.filename.substringAfterLast('.', "VIDEO").uppercase()
        tvVideoTimeCurrent.text = "00:00"
        tvVideoTimeTotal.text = "00:00"
        sbVideoSeek.progress = 0
        btnVideoPlayPause.text = "PAUSE"
        btnVideoPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0)
        btnVideoAudioTrack.text = "AUDIO"
        btnVideoSubs.text = "SUBS"
        currentPlaybackSpeed = 1.0f
        setPlaybackSpeed(1.0f)
        updateLoopButtonUi()

        tvBuffering.text = getString(R.string.buffering)
        layoutVideoBuffering.visibility = View.VISIBLE
        showVideoHud()

        val session = videoPlaybackManager.createSession(
            client = client,
            item = item,
            sessionId = sessionId,
            onBufferingUpdate = { isBuffering, bufferedBytes, totalBytes ->
                lifecycleScope.launch(Dispatchers.Main) {
                    if (sessionId == currentVideoSessionId) {
                        if (isBuffering && !videoView.isPlaying) {
                            layoutVideoBuffering.visibility = View.VISIBLE
                            val mb = bufferedBytes / (1024.0 * 1024.0)
                            if (totalBytes > 0) {
                                val totalMb = totalBytes / (1024.0 * 1024.0)
                                tvBuffering.text = String.format("Buffering %.1f / %.1f MB...", mb, totalMb)
                            } else {
                                tvBuffering.text = String.format("Buffering %.1f MB...", mb)
                            }
                        } else {
                            layoutVideoBuffering.visibility = View.GONE
                        }
                    }
                }
            }
        )

        videoView.isLooping = (videoLoopMode == LoopMode.SINGLE)
        videoView.onPreparedCallback = {
            if (sessionId == currentVideoSessionId) {
                tvVideoError.visibility = View.GONE
                tvVideoError.text = ""
                layoutVideoBuffering.visibility = View.GONE

                videoView.setPlaybackSpeed(currentPlaybackSpeed)
                videoView.start()
                btnVideoPlayPause.text = "PAUSE"
                btnVideoPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0)

                val duration = videoView.duration
                if (duration > 0) {
                    tvVideoTimeTotal.text = formatTime(duration)
                }

                updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, 0L)
                startVideoProgressLoop(sessionId)
                resetVideoHudTimer()
            }
        }

        videoView.onEngineChangedCallback = { engine ->
            if (sessionId == currentVideoSessionId) {
                val ext = item.filename.substringAfterLast('.', "VIDEO").uppercase()
                val engineDesc = if (engine == PlaybackEngine.EXOPLAYER) "Media3" else "Direct"
                tvVideoBadgeFormat.text = "$ext • $engineDesc"
            }
        }

        videoView.onAudioTracksUpdated = { audioTracks ->
            if (sessionId == currentVideoSessionId) {
                val hasAc3 = audioTracks.any { it.isAc3OrDolby }
                val ext = item.filename.substringAfterLast('.', "VIDEO").uppercase()
                if (hasAc3) {
                    tvVideoBadgeFormat.text = "$ext • DOLBY AC-3"
                    btnVideoAudioTrack.text = "DOLBY"
                } else {
                    tvVideoBadgeFormat.text = ext
                }
            }
        }

        videoView.onErrorCallback = { errorMsg ->
            if (sessionId == currentVideoSessionId) {
                Log.w(PtpConstants.TAG, "DirectVideoView playback error: $errorMsg")
                layoutVideoBuffering.visibility = View.GONE
                tvVideoError.text = getString(R.string.video_codec_unsupported)
                tvVideoError.visibility = View.VISIBLE
                showVideoHud()
                btnVideoPlayPause.text = "PLAY"
                btnVideoPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play_arrow, 0, 0, 0)
                updateMediaSessionState(PlaybackStateCompat.STATE_ERROR, 0L)
            }
        }

        videoView.onCompletionCallback = {
            if (sessionId == currentVideoSessionId) {
                updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED, videoView.duration.toLong())
                when (videoLoopMode) {
                    LoopMode.OFF -> {
                        btnVideoPlayPause.text = "PLAY"
                        btnVideoPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play_arrow, 0, 0, 0)
                        showVideoHud()
                        if (currentVideoIndex < displayedVideoList.size - 1) {
                            playVideoAtIndex(currentVideoIndex + 1)
                        }
                    }
                    LoopMode.SINGLE -> {
                        videoView.seekTo(0)
                        videoView.start()
                    }
                    LoopMode.ALL -> {
                        val nextIdx = (currentVideoIndex + 1) % displayedVideoList.size
                        playVideoAtIndex(nextIdx)
                    }
                }
            }
        }

        lifecycleScope.launch {
            session.prewarmInitialChunk()
            if (sessionId == currentVideoSessionId) {
                videoView.setPlaybackSession(session)
            }
        }
    }

    private fun startVideoProgressLoop(sessionId: Long = currentVideoSessionId) {
        videoProgressJob?.cancel()
        videoProgressJob = lifecycleScope.launch {
            while (currentScreen == Screen.VIDEO_PLAYER && sessionId == currentVideoSessionId) {
                try {
                    if (videoView.isPlaying && !isVideoTracking) {
                        if (tvVideoError.visibility == View.VISIBLE) {
                            tvVideoError.visibility = View.GONE
                        }
                        val current = videoView.currentPosition
                        val total = videoView.duration
                        if (total > 0) {
                            val ratio = (current.toLong() * 1000L / total).toInt()
                            sbVideoSeek.progress = ratio
                            tvVideoTimeCurrent.text = formatTime(current)
                            tvVideoTimeTotal.text = formatTime(total)
                        }
                    }
                } catch (_: Exception) {}
                delay(500)
            }
        }
    }

    private fun stopAndClearVideoPlayer() {
        currentVideoSessionId++
        hudHandler.removeCallbacks(hideVideoHudRunnable)
        videoProgressJob?.cancel()
        videoPlaybackManager.closeCurrentSession()
        videoView.stopPlayback()
        layoutVideoBuffering.visibility = View.GONE
        tvVideoError.visibility = View.GONE
        tvVideoError.text = ""
        updateMediaSessionState(PlaybackStateCompat.STATE_NONE, 0L)
    }

    fun playAudioAtIndex(index: Int) {
        if (index < 0 || index >= displayedAudioList.size) return
        currentAudioIndex = index
        val item = displayedAudioList[index]
        startAudioPlayback(item)
    }

    private fun startAudioPlayback(item: PtpMediaItem) {
        val client = repository.client ?: return
        val sessionId = ++currentAudioSessionId

        stopAudioPlaybackOnly()
        audioCachingJob?.cancel()
        audioProgressJob?.cancel()
        audioCacheManager.cancelBuffering()
        audioCacheManager.clearCache()

        showScreen(Screen.AUDIO_PLAYER)

        tvAudioError.visibility = View.GONE
        tvAudioError.text = ""
        tvAudioPlayerTitle.text = item.displayName
        tvAudioPlayerStatus.text = ""
        tvAudioTimeCurrent.text = "00:00"
        tvAudioTimeTotal.text = "00:00"
        progressAudioSeek.progress = 0
        btnAudioPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0)
        updateLoopButtonUi()

        tvAudioBuffering.text = getString(R.string.buffering)
        layoutAudioBuffering.visibility = View.VISIBLE
        showAudioHud()

        // Load artwork into hero & ambient background
        mediaThumbnailLoader.loadThumbnail(item, ivAudioPlayerArt, R.drawable.ic_audio_placeholder)
        mediaThumbnailLoader.loadThumbnail(item, ivAudioAmbientBg, R.drawable.ic_audio_placeholder)

        audioCachingJob = lifecycleScope.launch {
            val file = audioCacheManager.cacheAudio(
                client = client,
                handle = item.handle,
                filename = item.filename,
                sessionId = sessionId
            )
            if (sessionId != currentAudioSessionId) return@launch

            if (file != null && file.exists() && file.length() > 0) {
                try {
                    val mp = MediaPlayer()
                    audioPlayer = mp
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener { player ->
                        if (sessionId != currentAudioSessionId) return@setOnPreparedListener
                        tvAudioError.visibility = View.GONE
                        layoutAudioBuffering.visibility = View.GONE
                        player.isLooping = (audioLoopMode == LoopMode.SINGLE)
                        player.start()
                        btnAudioPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pause, 0, 0, 0)
                        audioEqualizerView.setPlaying(true)
                        tvAudioPlayerStatus.text = "[ PLAYING ]"
                        tvAudioTimeTotal.text = formatTime(player.duration)
                        updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING, 0L)
                        startAudioProgressLoop(player, sessionId)
                        resetAudioHudTimer()
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        if (sessionId != currentAudioSessionId) return@setOnErrorListener true
                        Log.e(PtpConstants.TAG, "Audio playback error: what=$what extra=$extra")
                        layoutAudioBuffering.visibility = View.GONE
                        tvAudioError.text = getString(R.string.audio_codec_unsupported)
                        tvAudioError.visibility = View.VISIBLE
                        tvAudioPlayerStatus.text = "[ ERROR ]"
                        btnAudioPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play_arrow, 0, 0, 0)
                        audioEqualizerView.setPlaying(false)
                        showAudioHud()
                        updateMediaSessionState(PlaybackStateCompat.STATE_ERROR, 0L)
                        true
                    }
                    mp.setOnCompletionListener {
                        if (sessionId != currentAudioSessionId) return@setOnCompletionListener
                        tvAudioPlayerStatus.text = "[ FINISHED ]"
                        audioEqualizerView.setPlaying(false)
                        progressAudioSeek.progress = 1000
                        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED, mp.duration.toLong())

                        when (audioLoopMode) {
                            LoopMode.OFF -> {
                                if (isAudioShuffleEnabled && displayedAudioList.size > 1) {
                                    var nextIdx = kotlin.random.Random.nextInt(displayedAudioList.size)
                                    if (nextIdx == currentAudioIndex) {
                                        nextIdx = (nextIdx + 1) % displayedAudioList.size
                                    }
                                    playAudioAtIndex(nextIdx)
                                } else if (currentAudioIndex < displayedAudioList.size - 1) {
                                    playAudioAtIndex(currentAudioIndex + 1)
                                } else {
                                    btnAudioPlayPause.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play_arrow, 0, 0, 0)
                                    showAudioHud()
                                }
                            }
                            LoopMode.SINGLE -> {
                                mp.seekTo(0)
                                mp.start()
                                audioEqualizerView.setPlaying(true)
                            }
                            LoopMode.ALL -> {
                                if (isAudioShuffleEnabled && displayedAudioList.size > 1) {
                                    var nextIdx = kotlin.random.Random.nextInt(displayedAudioList.size)
                                    if (nextIdx == currentAudioIndex) {
                                        nextIdx = (nextIdx + 1) % displayedAudioList.size
                                    }
                                    playAudioAtIndex(nextIdx)
                                } else {
                                    val nextIdx = (currentAudioIndex + 1) % displayedAudioList.size
                                    playAudioAtIndex(nextIdx)
                                }
                            }
                        }
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    if (sessionId == currentAudioSessionId) {
                        Log.e(PtpConstants.TAG, "Error initializing MediaPlayer for audio", e)
                        layoutAudioBuffering.visibility = View.GONE
                        tvAudioError.text = getString(R.string.audio_codec_unsupported)
                        tvAudioError.visibility = View.VISIBLE
                        tvAudioPlayerStatus.text = "[ ERROR ]"
                        audioEqualizerView.setPlaying(false)
                        showAudioHud()
                    }
                }
            } else {
                if (sessionId == currentAudioSessionId) {
                    layoutAudioBuffering.visibility = View.GONE
                    tvAudioError.text = getString(R.string.audio_load_failed)
                    tvAudioError.visibility = View.VISIBLE
                    tvAudioPlayerStatus.text = "[ FAILED ]"
                    audioEqualizerView.setPlaying(false)
                    showAudioHud()
                }
            }
        }
    }

    private fun startAudioProgressLoop(player: MediaPlayer, sessionId: Long = currentAudioSessionId) {
        audioProgressJob?.cancel()
        audioProgressJob = lifecycleScope.launch {
            while (audioPlayer == player && sessionId == currentAudioSessionId) {
                try {
                    if (player.isPlaying && !isAudioTracking) {
                        if (tvAudioError.visibility == View.VISIBLE) {
                            tvAudioError.visibility = View.GONE
                        }
                        val current = player.currentPosition
                        val total = player.duration
                        if (total > 0) {
                            val ratio = (current.toLong() * 1000L / total).toInt()
                            progressAudioSeek.progress = ratio
                            tvAudioTimeCurrent.text = formatTime(current)
                            tvAudioTimeTotal.text = formatTime(total)
                        }
                    }
                } catch (_: Exception) {}
                delay(500)
            }
        }
    }

    private fun formatTime(millis: Int): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val mins = totalSec / 60
        val secs = totalSec % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun stopAudioPlaybackOnly() {
        hudHandler.removeCallbacks(hideAudioHudRunnable)
        audioProgressJob?.cancel()
        audioEqualizerView.setPlaying(false)
        audioPlayer?.let {
            try {
                it.setOnPreparedListener(null)
                it.setOnErrorListener(null)
                it.setOnCompletionListener(null)
                if (it.isPlaying) it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        audioPlayer = null
        updateMediaSessionState(PlaybackStateCompat.STATE_NONE, 0L)
    }

    private fun stopAndClearAudioPlayer() {
        currentAudioSessionId++
        audioCachingJob?.cancel()
        audioCacheManager.cancelBuffering()
        stopAudioPlaybackOnly()
        layoutAudioBuffering.visibility = View.GONE
        tvAudioError.visibility = View.GONE
        tvAudioError.text = ""
        tvAudioPlayerStatus.text = ""
        audioCacheManager.clearCache(keepSessionId = -1L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If Queue Overlay is open, handle Back to dismiss it
        if (overlayQueue.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                overlayQueue.visibility = View.GONE
                if (currentScreen == Screen.VIDEO_PLAYER) {
                    btnVideoQueue.requestFocus()
                } else if (currentScreen == Screen.AUDIO_PLAYER) {
                    btnAudioQueue.requestFocus()
                }
                return true
            }
        }

        when (currentScreen) {
            Screen.PHOTO_VIEWER -> {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (currentPhotoIndex > 0) {
                            currentPhotoIndex--
                            loadSelectedPhoto(currentPhotoIndex)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (currentPhotoIndex < displayedPhotoList.size - 1) {
                            currentPhotoIndex++
                            loadSelectedPhoto(currentPhotoIndex)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        photoLoadJob?.cancel()
                        showScreen(Screen.PHOTO_BROWSER)
                        rvPhotos.requestFocus()
                        return true
                    }
                }
            }
            Screen.VIDEO_PLAYER -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    showVideoHud()
                    btnVideoPlayPause.requestFocus()
                    return true
                }

                // Any DPAD key interaction shows HUD and resets timer
                if (layoutVideoControls.visibility != View.VISIBLE) {
                    showVideoHud()
                    btnVideoPlayPause.requestFocus()
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    ) {
                        return true
                    }
                } else {
                    resetVideoHudTimer()
                }

                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        handlePlayPauseAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        handleNextAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        handlePreviousAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        handleForward10Action()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        handleRewind10Action()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        stopAndClearVideoPlayer()
                        showScreen(Screen.VIDEO_BROWSER)
                        rvVideos.requestFocus()
                        return true
                    }
                }
            }
            Screen.AUDIO_PLAYER -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    showAudioHud()
                    btnAudioPlayPause.requestFocus()
                    return true
                }

                // Any interaction reveals controls and resets timer
                if (layoutAudioControls.visibility != View.VISIBLE) {
                    showAudioHud()
                    btnAudioPlayPause.requestFocus()
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    ) {
                        return true
                    }
                } else {
                    resetAudioHudTimer()
                }

                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        handlePlayPauseAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        handleNextAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        handlePreviousAction()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        handleForward10Action()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        handleRewind10Action()
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        stopAndClearAudioPlayer()
                        showScreen(Screen.AUDIO_BROWSER)
                        rvAudio.requestFocus()
                        return true
                    }
                }
            }
            Screen.PHOTO_BROWSER -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    showScreen(Screen.HOME)
                    btnPhotos.requestFocus()
                    return true
                }
            }
            Screen.VIDEO_BROWSER -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    showScreen(Screen.HOME)
                    btnVideos.requestFocus()
                    return true
                }
            }
            Screen.AUDIO_BROWSER -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    showScreen(Screen.HOME)
                    btnAudio.requestFocus()
                    return true
                }
            }
            Screen.HOME -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    finish()
                    return true
                }
            }
            Screen.START -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    finish()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        screenStart.visibility = if (screen == Screen.START) View.VISIBLE else View.GONE
        screenHome.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
        screenPhotoBrowser.visibility = if (screen == Screen.PHOTO_BROWSER) View.VISIBLE else View.GONE
        screenPhotoViewer.visibility = if (screen == Screen.PHOTO_VIEWER) View.VISIBLE else View.GONE
        screenVideoBrowser.visibility = if (screen == Screen.VIDEO_BROWSER) View.VISIBLE else View.GONE
        screenVideoPlayer.visibility = if (screen == Screen.VIDEO_PLAYER) View.VISIBLE else View.GONE
        screenAudioBrowser.visibility = if (screen == Screen.AUDIO_BROWSER) View.VISIBLE else View.GONE
        screenAudioPlayer.visibility = if (screen == Screen.AUDIO_PLAYER) View.VISIBLE else View.GONE
    }
}

// =================== RECYCLERVIEW ADAPTERS ===================

class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val ivThumb: ImageView = view.findViewById(R.id.iv_thumb)
    val tvName: TextView? = view.findViewById(R.id.tv_photo_name)
    val tvSize: TextView? = view.findViewById(R.id.tv_photo_size)
}

class PhotoAdapter(
    private val items: List<PtpMediaItem>,
    private val thumbnailLoader: PhotoThumbnailLoader,
    private val isListView: () -> Boolean,
    private val onItemClicked: (Int) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<PhotoViewHolder>() {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (isListView()) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_LIST) R.layout.item_photo_list else R.layout.item_photo
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        TvFocusAnimator.attach(view)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.tvName?.text = item.displayName
        holder.tvSize?.text = item.formattedSize
        holder.itemView.setOnClickListener { onItemClicked(position) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onItemMenu(item)
                true
            } else {
                false
            }
        }
        thumbnailLoader.loadThumbnail(item.handle, holder.ivThumb, R.drawable.ic_photo_placeholder)
    }

    override fun getItemCount(): Int = items.size
}

class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val ivThumb: ImageView = view.findViewById(R.id.iv_video_thumb)
    val tvName: TextView = view.findViewById(R.id.tv_video_name)
    val tvSize: TextView = view.findViewById(R.id.tv_video_size)
}

class VideoAdapter(
    private val items: List<PtpMediaItem>,
    private val thumbnailLoader: MediaThumbnailLoader,
    private val isListView: () -> Boolean,
    private val onItemBound: (PtpMediaItem) -> Unit,
    private val onItemClicked: (PtpMediaItem) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<VideoViewHolder>() {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (isListView()) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_LIST) R.layout.item_video_list else R.layout.item_video
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        TvFocusAnimator.attach(view)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.tvName.text = item.displayName
        holder.tvSize.text = item.formattedSize

        holder.itemView.setOnClickListener { onItemClicked(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onItemMenu(item)
                true
            } else {
                false
            }
        }

        thumbnailLoader.loadThumbnail(item, holder.ivThumb, R.drawable.ic_video_placeholder)

        if (!item.isMetadataLoaded) {
            onItemBound(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

class AudioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val ivThumb: ImageView = view.findViewById(R.id.iv_audio_thumb)
    val tvName: TextView = view.findViewById(R.id.tv_audio_name)
    val tvSize: TextView = view.findViewById(R.id.tv_audio_size)
}

class AudioAdapter(
    private val items: List<PtpMediaItem>,
    private val thumbnailLoader: MediaThumbnailLoader,
    private val isListView: () -> Boolean,
    private val onItemBound: (PtpMediaItem) -> Unit,
    private val onItemClicked: (PtpMediaItem) -> Unit,
    private val onItemMenu: (PtpMediaItem) -> Unit
) : RecyclerView.Adapter<AudioViewHolder>() {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_LIST = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (isListView()) VIEW_TYPE_LIST else VIEW_TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AudioViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_LIST) R.layout.item_audio_list else R.layout.item_audio
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        TvFocusAnimator.attach(view)
        return AudioViewHolder(view)
    }

    override fun onBindViewHolder(holder: AudioViewHolder, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.handle
        holder.tvName.text = item.displayName
        holder.tvSize.text = item.formattedSize

        holder.itemView.setOnClickListener { onItemClicked(item) }
        holder.itemView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                onItemMenu(item)
                true
            } else {
                false
            }
        }

        thumbnailLoader.loadThumbnail(item, holder.ivThumb, R.drawable.ic_audio_placeholder)

        if (!item.isMetadataLoaded) {
            onItemBound(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val tvIndex: TextView = view.findViewById(R.id.tv_queue_index)
    val tvTitle: TextView = view.findViewById(R.id.tv_queue_item_title)
    val tvPlaying: TextView = view.findViewById(R.id.tv_queue_item_playing)
}

class QueueAdapter(
    private var items: List<PtpMediaItem>,
    private var currentIndex: Int,
    private val onItemClicked: (Int) -> Unit
) : RecyclerView.Adapter<QueueViewHolder>() {

    fun updateItems(newItems: List<PtpMediaItem>, newCurrentIndex: Int) {
        this.items = newItems
        this.currentIndex = newCurrentIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        TvFocusAnimator.attach(view)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val item = items[position]
        holder.tvIndex.text = "${position + 1}."
        holder.tvTitle.text = item.displayName
        holder.tvPlaying.visibility = if (position == currentIndex) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onItemClicked(position) }
    }

    override fun getItemCount(): Int = items.size
}
