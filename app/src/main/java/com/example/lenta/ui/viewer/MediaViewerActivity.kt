package com.example.lenta.ui.viewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.lenta.R
import com.example.lenta.data.model.MediaItem
import com.example.lenta.data.model.MediaType
import com.example.lenta.data.nextcloud.NextcloudClient
import com.example.lenta.data.nextcloud.NextcloudPreferences
import com.example.lenta.databinding.ActivityMediaViewerBinding

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

    private var mediaList = listOf<MediaItem>()
    private var currentPosition = 0
    private var isMuted = true
    private var isUserScrubbing = false
    private var isLandscape = false

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

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        mediaList = if (Build.VERSION.SDK_INT >= 33) {
            intent.getSerializableExtra(EXTRA_MEDIA_ITEMS, ArrayList::class.java) as? ArrayList<MediaItem> ?: emptyList()
        } else {
            intent.getSerializableExtra(EXTRA_MEDIA_ITEMS) as? ArrayList<MediaItem> ?: emptyList()
        }

        currentPosition = intent.getIntExtra(EXTRA_START_POSITION, 0).coerceIn(0, (mediaList.size - 1).coerceAtLeast(0))

        setupUI()
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
            }
        })

        // Setup Bottom Scrubbing Bar
        setupSeekBar()

        // Initial media type UI config
        updateUiForMediaType(mediaList.getOrNull(currentPosition)?.type)
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
}
