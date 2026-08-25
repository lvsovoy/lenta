package me.lesovoy.lenta.ui.viewer

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import me.lesovoy.lenta.R
import me.lesovoy.lenta.data.local.LocalMediaRepository
import me.lesovoy.lenta.data.local.MediaIntentResolver
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.thumbnail.ThumbnailManager
import me.lesovoy.lenta.databinding.ActivityMediaViewerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_ITEMS = "extra_media_items"
        const val EXTRA_START_POSITION = "extra_start_position"

        fun createIntent(context: Context, items: List<MediaItem>, startPosition: Int): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_MEDIA_ITEMS, ArrayList(items))
                putExtra(EXTRA_START_POSITION, startPosition)
            }
        }
    }

    private lateinit var binding: ActivityMediaViewerBinding
    private lateinit var preferences: NextcloudPreferences
    private lateinit var nextcloudClient: NextcloudClient
    private lateinit var adapter: MediaViewerAdapter
    private lateinit var drawerAdapter: DrawerFileListAdapter

    private var mediaList = listOf<MediaItem>()
    private var currentPosition = 0
    private var isMuted = true
    private var isUserScrubbing = false
    private var isLandscape = false

    private var currentDrawerPath: String = ""
    private var isDrawerNextcloud: Boolean = false
    private var drawerItems: List<MediaItem> = emptyList()
    private var drawerLoadingJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private var activeViewHolder: MediaViewerAdapter.BaseMediaViewHolder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set vertical screen orientation by default
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Fullscreen immersive mode
        hideSystemBars()

        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = NextcloudPreferences(this)
        nextcloudClient = NextcloudClient(preferences)
        isMuted = preferences.muteByDefault

        if (!processIntent(intent)) {
            finish()
            return
        }

        setupUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent != null && processIntent(intent)) {
            setupUI()
        }
    }

    private fun processIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val (items, startPos) = MediaIntentResolver.resolveMediaFromIntent(this, intent)
        if (items.isEmpty()) return false
        mediaList = items
        currentPosition = startPos.coerceIn(0, (mediaList.size - 1).coerceAtLeast(0))
        return true
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupUI() {
        // Back Button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Rotate Button (Toggle Vertical / Landscape)
        binding.btnRotate.setOnClickListener {
            toggleOrientation()
        }

        // Setup ViewPager2 (Vertical orientation for up/down scrolling)
        adapter = MediaViewerAdapter(
            items = mediaList,
            nextcloudClient = nextcloudClient,
            preferences = preferences,
            scope = lifecycleScope,
            onItemTapped = { pos ->
                handleMiddleTap(pos)
            },
            onVideoSkipped = { pos, isForward ->
                if (pos == currentPosition) {
                    showSkipIndicator(isForward)
                }
            },
            onPlaybackProgress = { itemPos, posMs, durationMs, fraction, isPlaying ->
                if (itemPos == currentPosition && !isUserScrubbing) {
                    updatePlaybackProgress(posMs, durationMs, fraction)
                }
            },
            onComicProgress = { itemPos, currentPg, totalPgs ->
                if (itemPos == currentPosition) {
                    updateComicProgress(currentPg, totalPgs)
                }
            },
            onSwipeRight = {
                openDrawer()
            }
        )

        binding.mediaViewPager.orientation = ViewPager2.ORIENTATION_VERTICAL
        binding.mediaViewPager.adapter = adapter
        binding.mediaViewPager.setCurrentItem(currentPosition, false)
        adapter.setActivePosition(currentPosition)

        binding.mediaViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                adapter.setActivePosition(position)
                activeViewHolder = adapter.getViewHolderAt(position)
                updateUiForMediaType(mediaList.getOrNull(position)?.type)
                val currentItem = mediaList.getOrNull(position)
                if (::drawerAdapter.isInitialized && currentItem != null) {
                    drawerAdapter.setCurrentPlaying(currentItem.id, currentItem.path)
                }
            }
        })

        // Setup Drawer for Folder Navigation
        setupDrawer()

        // Setup Bottom Scrubbing Bar
        setupSeekBar()

        // Initial media type UI config
        updateUiForMediaType(mediaList.getOrNull(currentPosition)?.type)
    }

    fun openDrawer() {
        if (!binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
            val currentItem = mediaList.getOrNull(currentPosition)
            if (::drawerAdapter.isInitialized && currentItem != null) {
                drawerAdapter.setCurrentPlaying(currentItem.id, currentItem.path)
            }
        }
    }

    private fun setupDrawer() {
        drawerAdapter = DrawerFileListAdapter { item, _ ->
            onDrawerItemClicked(item)
        }

        binding.recyclerDrawerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerDrawerFiles.adapter = drawerAdapter

        binding.btnDrawerClose.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.btnDrawerUp.setOnClickListener {
            navigateDrawerUp()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })

        val currentItem = mediaList.getOrNull(currentPosition) ?: mediaList.firstOrNull()
        if (currentItem != null) {
            if (currentItem.isRemote) {
                val folderPath = currentItem.path.trim('/').substringBeforeLast('/', "")
                loadDrawerDirectory(folderPath, isNextcloud = true, sourceId = currentItem.sourceId)
            } else if (currentItem.path.isNotEmpty()) {
                val file = File(currentItem.path)
                val parent = if (file.exists()) file.parentFile else null
                if (parent != null && parent.exists()) {
                    loadDrawerDirectory(parent.absolutePath, isNextcloud = false)
                } else {
                    loadDrawerDirectory(Environment.getExternalStorageDirectory().absolutePath, isNextcloud = false)
                }
            } else {
                drawerItems = mediaList
                drawerAdapter.submitList(mediaList)
                drawerAdapter.setCurrentPlaying(currentItem.id, currentItem.path)
                binding.tvDrawerFolderName.text = getString(R.string.files_drawer)
                binding.tvDrawerFolderPath.text = ""
                binding.btnDrawerUp.isEnabled = false
                binding.btnDrawerUp.alpha = 0.4f
            }
        }
    }

    private var activeSourceId: String? = null

    private fun loadDrawerDirectory(path: String, isNextcloud: Boolean, sourceId: String? = null) {
        currentDrawerPath = path
        isDrawerNextcloud = isNextcloud
        activeSourceId = sourceId

        if (isNextcloud) {
            val folderName = if (path.isEmpty() || path == "/") {
                getString(R.string.nav_files)
            } else {
                path.trim('/').substringAfterLast('/')
            }
            binding.tvDrawerFolderName.text = folderName
            binding.tvDrawerFolderPath.text = if (path.isEmpty()) "/" else "/${path.trim('/')}"
            val canUp = path.trim('/').isNotEmpty()
            binding.btnDrawerUp.isEnabled = canUp
            binding.btnDrawerUp.alpha = if (canUp) 1.0f else 0.4f
        } else {
            val dir = File(path)
            val folderName = if (dir.name.isEmpty()) getString(R.string.source_local_title) else dir.name
            binding.tvDrawerFolderName.text = folderName
            binding.tvDrawerFolderPath.text = dir.absolutePath
            val parent = dir.parentFile
            val canUp = parent != null && parent.exists() && parent.canRead()
            binding.btnDrawerUp.isEnabled = canUp
            binding.btnDrawerUp.alpha = if (canUp) 1.0f else 0.4f
        }

        binding.progressDrawer.visibility = View.VISIBLE
        binding.tvDrawerEmpty.visibility = View.GONE

        drawerLoadingJob?.cancel()
        drawerLoadingJob = lifecycleScope.launch {
            val items = if (isNextcloud) {
                val client = if (sourceId != null) {
                    me.lesovoy.lenta.data.source.SourceClientFactory.getClientForSourceId(this@MediaViewerActivity, sourceId) ?: nextcloudClient
                } else {
                    nextcloudClient
                }
                val result = client.listFolder(path)
                if (result.isSuccess) result.getOrThrow() else emptyList()
            } else {
                val repository = LocalMediaRepository(this@MediaViewerActivity)
                repository.listDirectory(File(path), preferences.showHiddenFiles)
            }

            drawerItems = items
            binding.progressDrawer.visibility = View.GONE
            binding.tvDrawerEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            drawerAdapter.submitList(items)

            val currentPlaying = mediaList.getOrNull(currentPosition)
            drawerAdapter.setCurrentPlaying(currentPlaying?.id, currentPlaying?.path)

            val playingIndex = items.indexOfFirst {
                !it.isDirectory && (
                    it.id == currentPlaying?.id ||
                    (currentPlaying?.path != null && currentPlaying.path.isNotEmpty() && it.path == currentPlaying.path)
                )
            }
            if (playingIndex != -1) {
                binding.recyclerDrawerFiles.scrollToPosition(playingIndex)
            }

            ThumbnailManager.pregenerateThumbnails(this@MediaViewerActivity, items) { generatedItem ->
                val idx = drawerAdapter.getItems().indexOfFirst { it.id == generatedItem.id }
                if (idx != -1) {
                    drawerAdapter.notifyItemChanged(idx)
                }
            }
        }
    }

    private fun navigateDrawerUp() {
        if (isDrawerNextcloud) {
            val trimmed = currentDrawerPath.trim('/')
            if (trimmed.isNotEmpty()) {
                val parentPath = trimmed.substringBeforeLast('/', "")
                loadDrawerDirectory(parentPath, true, activeSourceId)
            }
        } else {
            val currentDir = File(currentDrawerPath)
            val parent = currentDir.parentFile
            if (parent != null && parent.exists() && parent.canRead()) {
                loadDrawerDirectory(parent.absolutePath, false)
            }
        }
    }

    private fun onDrawerItemClicked(item: MediaItem) {
        if (item.isDirectory) {
            val path = if (isDrawerNextcloud) {
                item.effectiveRemotePath
            } else {
                item.path
            }
            loadDrawerDirectory(path, isDrawerNextcloud, activeSourceId)
        } else {
            val existingIndex = mediaList.indexOfFirst {
                it.id == item.id || (it.path.isNotEmpty() && it.path == item.path)
            }

            if (existingIndex != -1) {
                binding.mediaViewPager.setCurrentItem(existingIndex, false)
                adapter.setActivePosition(existingIndex)
                currentPosition = existingIndex
                activeViewHolder = adapter.getViewHolderAt(existingIndex)
                updateUiForMediaType(mediaList.getOrNull(existingIndex)?.type)
            } else {
                val playableItems = drawerItems.filter { !it.isDirectory }
                if (playableItems.isNotEmpty()) {
                    val newIndex = playableItems.indexOfFirst {
                        it.id == item.id || (it.path.isNotEmpty() && it.path == item.path)
                    }.coerceAtLeast(0)

                    mediaList = playableItems
                    currentPosition = newIndex
                    adapter.updateItems(mediaList)
                    binding.mediaViewPager.setCurrentItem(newIndex, false)
                    adapter.setActivePosition(newIndex)
                    activeViewHolder = adapter.getViewHolderAt(newIndex)
                    updateUiForMediaType(mediaList.getOrNull(newIndex)?.type)
                }
            }

            drawerAdapter.setCurrentPlaying(item.id, item.path)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun toggleOrientation() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isLandscape = true
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            isLandscape = false
        }
        hideSystemBars()
    }

    private fun updateUiForMediaType(type: MediaType?) {
        when (type) {
            MediaType.IMAGE -> {
                binding.bottomScrubberContainer.visibility = View.GONE
            }
            MediaType.VIDEO, MediaType.GIF -> {
                binding.bottomScrubberContainer.visibility = View.VISIBLE
                binding.tvScrubInfo.visibility = View.GONE
                binding.bottomSeekBar.progress = 0
            }
            MediaType.CBZ -> {
                binding.bottomScrubberContainer.visibility = View.VISIBLE
                binding.tvScrubInfo.visibility = View.VISIBLE
                binding.bottomSeekBar.progress = 0
            }
            null -> {
                binding.bottomScrubberContainer.visibility = View.GONE
            }
        }
    }

    private fun setupSeekBar() {
        binding.bottomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val fraction = progress / 1000f
                    val currentType = mediaList.getOrNull(currentPosition)?.type
                    val holder = adapter.getViewHolderAt(currentPosition)

                    holder?.seekTo(fraction)

                    if (currentType == MediaType.CBZ) {
                        // Handled via onComicProgress
                    } else {
                        binding.tvScrubInfo.visibility = View.VISIBLE
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserScrubbing = true
                binding.tvScrubInfo.visibility = View.VISIBLE
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserScrubbing = false
                val currentType = mediaList.getOrNull(currentPosition)?.type
                if (currentType != MediaType.CBZ) {
                    handler.postDelayed({
                        if (!isUserScrubbing) {
                            binding.tvScrubInfo.visibility = View.GONE
                        }
                    }, 1500)
                }
            }
        })
    }

    private fun updatePlaybackProgress(posMs: Long, durationMs: Long, fraction: Float) {
        val currentType = mediaList.getOrNull(currentPosition)?.type
        if (currentType == MediaType.VIDEO || currentType == MediaType.GIF) {
            binding.bottomSeekBar.progress = (fraction * 1000).toInt()
            val posStr = formatTime(posMs)
            val durStr = formatTime(durationMs)
            binding.tvScrubInfo.text = "$posStr / $durStr"
        }
    }

    private fun updateComicProgress(currentPage: Int, totalPages: Int) {
        val fraction = if (totalPages > 0) currentPage.toFloat() / totalPages.toFloat() else 0f
        if (!isUserScrubbing) {
            binding.bottomSeekBar.progress = (fraction * 1000).toInt()
        }
        if (totalPages > 0) {
            binding.tvScrubInfo.text = getString(R.string.page_counter, currentPage, totalPages)
            binding.tvScrubInfo.visibility = View.VISIBLE
        } else {
            binding.tvScrubInfo.visibility = View.GONE
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSecs = (millis / 1000).coerceAtLeast(0)
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    /**
     * Tapping once in the middle pauses the media and shows indication
     */
    private fun handleMiddleTap(position: Int) {
        val holder = adapter.getViewHolderAt(position) ?: return
        val currentType = mediaList.getOrNull(position)?.type

        if (currentType == MediaType.VIDEO || currentType == MediaType.GIF) {
            val isNowPlaying = holder.togglePlayPause()
            showCenterIndicator(isNowPlaying)
        } else {
            // For images or comics, toggle visibility of overlays
            toggleOverlayVisibility()
        }
    }

    private fun toggleOverlayVisibility() {
        val visible = binding.topControls.visibility == View.VISIBLE
        if (visible) {
            binding.topControls.animate().alpha(0f).setDuration(200).withEndAction {
                binding.topControls.visibility = View.GONE
            }.start()
            binding.bottomScrubberContainer.animate().alpha(0f).setDuration(200).withEndAction {
                binding.bottomScrubberContainer.visibility = View.GONE
            }.start()
        } else {
            binding.topControls.visibility = View.VISIBLE
            binding.topControls.animate().alpha(1f).setDuration(200).start()
            val currentType = mediaList.getOrNull(currentPosition)?.type
            if (currentType != MediaType.IMAGE) {
                binding.bottomScrubberContainer.visibility = View.VISIBLE
                binding.bottomScrubberContainer.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    private fun showCenterIndicator(isPlaying: Boolean) {
        binding.ivCenterIndicator.setImageResource(
            if (isPlaying) R.drawable.ic_play_circle else R.drawable.ic_pause_circle
        )
        binding.ivCenterIndicator.visibility = View.VISIBLE
        binding.ivCenterIndicator.alpha = 0f
        binding.ivCenterIndicator.scaleX = 0.8f
        binding.ivCenterIndicator.scaleY = 0.8f

        binding.ivCenterIndicator.animate()
            .alpha(1f)
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(180)
            .withEndAction {
                binding.ivCenterIndicator.animate()
                    .alpha(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(350)
                    .setStartDelay(250)
                    .withEndAction {
                        binding.ivCenterIndicator.visibility = View.GONE
                    }
                    .start()
            }
            .start()
    }

    private fun showSkipIndicator(isForward: Boolean) {
        val indicator = if (isForward) binding.indicatorSkipRight else binding.indicatorSkipLeft
        val otherIndicator = if (isForward) binding.indicatorSkipLeft else binding.indicatorSkipRight

        otherIndicator.animate().cancel()
        otherIndicator.visibility = View.GONE

        indicator.animate().cancel()
        indicator.visibility = View.VISIBLE
        indicator.alpha = 0f
        indicator.scaleX = 0.8f
        indicator.scaleY = 0.8f

        indicator.animate()
            .alpha(1f)
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(150)
            .withEndAction {
                indicator.animate()
                    .alpha(0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(300)
                    .setStartDelay(250)
                    .withEndAction {
                        indicator.visibility = View.GONE
                    }
                    .start()
            }
            .start()
    }

    /**
     * Intercept Volume Up to unmute
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (isMuted) {
                unmuteSound()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun unmuteSound() {
        isMuted = false
        adapter.setAllMuted(false)
        showVolumeBadge(false)
    }

    fun muteSound() {
        isMuted = true
        adapter.setAllMuted(true)
        showVolumeBadge(true)
    }

    private fun showVolumeBadge(muted: Boolean) {
        binding.ivVolumeBadgeIcon.setImageResource(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
        binding.tvVolumeBadgeText.setText(
            if (muted) R.string.muted else R.string.unmuted
        )

        binding.volumeIndicatorBadge.visibility = View.VISIBLE
        binding.volumeIndicatorBadge.alpha = 0f
        binding.volumeIndicatorBadge.animate()
            .alpha(1f)
            .setDuration(200)
            .withEndAction {
                binding.volumeIndicatorBadge.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .setStartDelay(1000)
                    .withEndAction {
                        binding.volumeIndicatorBadge.visibility = View.GONE
                    }
                    .start()
            }
            .start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        adapter.getViewHolderAt(currentPosition)?.onActive()
    }

    override fun onPause() {
        super.onPause()
        adapter.getViewHolderAt(currentPosition)?.onInactive()
    }

    override fun onDestroy() {
        super.onDestroy()
        drawerLoadingJob?.cancel()
        drawerLoadingJob = null
    }
}
