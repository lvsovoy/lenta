package me.lesovoy.lenta.ui.viewer

import android.graphics.drawable.Animatable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import me.lesovoy.lenta.data.cbz.CbzReader
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.databinding.ItemMediaCbzBinding
import me.lesovoy.lenta.databinding.ItemMediaGifBinding
import me.lesovoy.lenta.databinding.ItemMediaImageBinding
import me.lesovoy.lenta.databinding.ItemMediaVideoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaViewerAdapter(
    private var items: List<MediaItem>,
    private val nextcloudClient: NextcloudClient,
    private val preferences: NextcloudPreferences,
    private val scope: CoroutineScope,
    private val onItemTapped: (position: Int) -> Unit,
    private val onVideoSkipped: (position: Int, isForward: Boolean) -> Unit,
    private val onPlaybackProgress: (position: Int, positionMs: Long, durationMs: Long, progressFraction: Float, isPlaying: Boolean) -> Unit,
    private val onComicProgress: (position: Int, currentPage: Int, totalPages: Int) -> Unit,
    private val onSwipeRight: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_IMAGE = 0
        private const val TYPE_VIDEO = 1
        private const val TYPE_GIF = 2
        private const val TYPE_CBZ = 3
    }

    private var currentMuted = preferences.muteByDefault
    private var activePosition: Int = -1
    private val activeHolders = mutableMapOf<Int, BaseMediaViewHolder>()

    fun updateItems(newItems: List<MediaItem>) {
        items = newItems
        activeHolders.clear()
        notifyDataSetChanged()
    }

    fun getItems(): List<MediaItem> = items

    abstract class BaseMediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var isActive: Boolean = false

        abstract fun bind(item: MediaItem, position: Int)
        abstract fun onActive()
        abstract fun onInactive()
        abstract fun togglePlayPause(): Boolean // returns true if now playing, false if paused
        abstract fun isPlaying(): Boolean
        abstract fun seekTo(fraction: Float)
        open fun skipBy(offsetMs: Long): Long = 0L
        open fun previousPage(): Boolean = false
        open fun nextPage(): Boolean = false
        abstract fun setMuted(muted: Boolean)
        open fun cleanup() {}
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position].type) {
            MediaType.IMAGE -> TYPE_IMAGE
            MediaType.VIDEO -> TYPE_VIDEO
            MediaType.GIF -> TYPE_GIF
            MediaType.CBZ -> TYPE_CBZ
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_IMAGE -> ImageViewHolder(ItemMediaImageBinding.inflate(inflater, parent, false))
            TYPE_VIDEO -> VideoViewHolder(ItemMediaVideoBinding.inflate(inflater, parent, false))
            TYPE_GIF -> GifViewHolder(ItemMediaGifBinding.inflate(inflater, parent, false))
            TYPE_CBZ -> CbzViewHolder(ItemMediaCbzBinding.inflate(inflater, parent, false))
            else -> ImageViewHolder(ItemMediaImageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is BaseMediaViewHolder) {
            activeHolders[position] = holder
            holder.bind(items[position], position)
            holder.setMuted(currentMuted)
            if (position == activePosition) {
                holder.onActive()
            } else {
                holder.onInactive()
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            activeHolders.remove(pos)
        }
        if (holder is BaseMediaViewHolder) {
            holder.onInactive()
            holder.cleanup()
        }
    }

    override fun getItemCount(): Int = items.size

    fun getViewHolderAt(position: Int): BaseMediaViewHolder? = activeHolders[position]

    fun setActivePosition(position: Int) {
        val prev = activePosition
        activePosition = position
        if (prev != position && prev in activeHolders) {
            activeHolders[prev]?.onInactive()
        }
        activeHolders[position]?.onActive()
    }

    fun setAllMuted(muted: Boolean) {
        currentMuted = muted
        activeHolders.values.forEach { it.setMuted(muted) }
    }

    // ==========================================
    // IMAGE VIEWHOLDER
    // ==========================================
    inner class ImageViewHolder(private val binding: ItemMediaImageBinding) : BaseMediaViewHolder(binding.root) {
        private var job: Job? = null

        override fun bind(item: MediaItem, position: Int) {
            isActive = (position == activePosition)
            binding.progressLoading.visibility = View.VISIBLE
            val pinchListener = PinchToZoomListener(
                targetView = binding.ivImage,
                onSingleTap = {
                    onItemTapped(bindingAdapterPosition)
                },
                onSwipeRight = onSwipeRight
            )
            binding.root.setOnTouchListener(pinchListener)

            job?.cancel()
            job = scope.launch {
                val dataToLoad: Any = if (item.isRemote) {
                    val client = if (item.sourceId != null) {
                        me.lesovoy.lenta.data.source.SourceClientFactory.getClientForSourceId(binding.root.context, item.sourceId) ?: nextcloudClient
                    } else {
                        nextcloudClient
                    }
                    val cachedResult = client.downloadToCache(binding.root.context, item)
                    if (cachedResult.isSuccess) cachedResult.getOrThrow() else item.uriString
                } else if (item.uriString.startsWith("content://")) {
                    Uri.parse(item.uriString)
                } else if (item.path.isNotEmpty() && File(item.path).exists()) {
                    File(item.path)
                } else if (item.uriString.isNotEmpty()) {
                    Uri.parse(item.uriString)
                } else {
                    File(item.path)
                }

                binding.ivImage.load(dataToLoad) {
                    crossfade(true)
                    listener(
                        onSuccess = { _, _ -> binding.progressLoading.visibility = View.GONE },
                        onError = { _, _ -> binding.progressLoading.visibility = View.GONE }
                    )
                }
            }
        }

        override fun onActive() {
            isActive = true
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onPlaybackProgress(pos, 0L, 0L, 0f, false)
            }
        }

        override fun onInactive() {
            isActive = false
            binding.ivImage.animate().cancel()
            binding.ivImage.scaleX = 1f
            binding.ivImage.scaleY = 1f
            binding.ivImage.translationX = 0f
            binding.ivImage.translationY = 0f
        }

        override fun togglePlayPause(): Boolean = false

        override fun isPlaying(): Boolean = false

        override fun seekTo(fraction: Float) {}

        override fun setMuted(muted: Boolean) {}

        override fun cleanup() {
            isActive = false
            job?.cancel()
            binding.ivImage.animate().cancel()
            binding.ivImage.scaleX = 1f
            binding.ivImage.scaleY = 1f
            binding.ivImage.translationX = 0f
            binding.ivImage.translationY = 0f
            binding.ivImage.setImageDrawable(null)
        }
    }

    // ==========================================
    // VIDEO VIEWHOLDER (Media3 ExoPlayer)
    // ==========================================
    inner class VideoViewHolder(private val binding: ItemMediaVideoBinding) : BaseMediaViewHolder(binding.root) {
        private var player: ExoPlayer? = null
        private var job: Job? = null
        private var progressJob: Job? = null
        private var currentItem: MediaItem? = null
        private var isManuallyPaused = false

        @OptIn(UnstableApi::class)
        override fun bind(item: MediaItem, position: Int) {
            currentItem = item
            isManuallyPaused = false
            isActive = (position == activePosition)
            val pinchListener = PinchToZoomListener(
                targetView = binding.playerView,
                onZoneTap = { zone ->
                    when (zone) {
                        TapZone.LEFT -> skipBy(-5000L)
                        TapZone.MIDDLE -> onItemTapped(bindingAdapterPosition)
                        TapZone.RIGHT -> skipBy(5000L)
                    }
                },
                onSwipeRight = onSwipeRight
            )
            binding.videoTouchOverlay.setOnTouchListener(pinchListener)
            binding.root.setOnTouchListener(pinchListener)

            initPlayer()

            job?.cancel()
            job = scope.launch {
                binding.progressVideoBuffering.visibility = View.VISIBLE
                val mediaUri: Uri = if (item.isRemote) {
                    val client = if (item.sourceId != null) {
                        me.lesovoy.lenta.data.source.SourceClientFactory.getClientForSourceId(binding.root.context, item.sourceId) ?: nextcloudClient
                    } else {
                        nextcloudClient
                    }
                    val cached = client.downloadToCache(binding.root.context, item)
                    if (cached.isSuccess) {
                        Uri.fromFile(cached.getOrThrow())
                    } else {
                        Uri.parse(item.uriString)
                    }
                } else if (item.uriString.startsWith("content://")) {
                    Uri.parse(item.uriString)
                } else if (item.path.isNotEmpty() && File(item.path).exists()) {
                    Uri.fromFile(File(item.path))
                } else if (item.uriString.isNotEmpty()) {
                    Uri.parse(item.uriString)
                } else {
                    Uri.fromFile(File(item.path))
                }

                withContext(Dispatchers.Main) {
                    player?.let { p ->
                        val exoItem = ExoMediaItem.fromUri(mediaUri)
                        p.setMediaItem(exoItem)
                        p.prepare()
                        if (isActive && !isManuallyPaused) {
                            p.playWhenReady = true
                        }
                    }
                }
            }
        }

        private fun initPlayer() {
            if (player == null) {
                val context = binding.root.context
                player = ExoPlayer.Builder(context).build().apply {
                    repeatMode = if (preferences.loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    volume = if (currentMuted) 0f else 1f
                    playWhenReady = false

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> binding.progressVideoBuffering.visibility = View.VISIBLE
                                Player.STATE_READY -> {
                                    binding.progressVideoBuffering.visibility = View.GONE
                                    if (isActive && isPlaying) {
                                        startProgressUpdates()
                                    }
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressVideoBuffering.visibility = View.GONE
                                }
                                Player.STATE_IDLE -> {}
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying && isActive) {
                                startProgressUpdates()
                            } else if (!isPlaying) {
                                progressJob?.cancel()
                                if (isActive) {
                                    val pos = bindingAdapterPosition
                                    val dur = duration
                                    if (pos != RecyclerView.NO_POSITION && dur > 0) {
                                        val cur = currentPosition.coerceIn(0L, dur)
                                        val fraction = (cur.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                                        onPlaybackProgress(pos, cur, dur, fraction, false)
                                    }
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            binding.progressVideoBuffering.visibility = View.GONE
                        }
                    })
                }
                binding.playerView.player = player
            }
        }

        private fun startProgressUpdates() {
            progressJob?.cancel()
            progressJob = scope.launch {
                while (isActive) {
                    player?.let { p ->
                        val dur = p.duration
                        val pos = bindingAdapterPosition
                        if (dur > 0 && pos != RecyclerView.NO_POSITION) {
                            val current = p.currentPosition.coerceIn(0L, dur)
                            val fraction = (current.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                            onPlaybackProgress(pos, current, dur, fraction, p.isPlaying)
                        }
                    }
                    kotlinx.coroutines.delay(200)
                }
            }
        }

        override fun onActive() {
            isActive = true
            initPlayer()
            player?.let { p ->
                if (!isManuallyPaused) {
                    p.playWhenReady = true
                    p.play()
                }
                val dur = p.duration
                val pos = bindingAdapterPosition
                if (dur > 0 && pos != RecyclerView.NO_POSITION) {
                    val current = p.currentPosition.coerceIn(0L, dur)
                    val fraction = (current.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    onPlaybackProgress(pos, current, dur, fraction, p.isPlaying)
                }
            }
            startProgressUpdates()
        }

        override fun onInactive() {
            isActive = false
            progressJob?.cancel()
            player?.playWhenReady = false
            player?.pause()
            binding.playerView.animate().cancel()
            binding.playerView.scaleX = 1f
            binding.playerView.scaleY = 1f
            binding.playerView.translationX = 0f
            binding.playerView.translationY = 0f
        }

        override fun togglePlayPause(): Boolean {
            player?.let { p ->
                return if (p.isPlaying) {
                    p.pause()
                    isManuallyPaused = true
                    false
                } else {
                    p.play()
                    isManuallyPaused = false
                    true
                }
            }
            return false
        }

        override fun isPlaying(): Boolean = player?.isPlaying == true

        override fun skipBy(offsetMs: Long): Long {
            player?.let { p ->
                val duration = p.duration
                val maxDuration = if (duration > 0) duration else Long.MAX_VALUE
                val current = p.currentPosition
                val target = (current + offsetMs).coerceIn(0L, maxDuration)
                p.seekTo(target)
                val fraction = if (maxDuration > 0 && maxDuration != Long.MAX_VALUE) {
                    (target.toFloat() / maxDuration.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onPlaybackProgress(pos, target, duration, fraction, p.isPlaying)
                    onVideoSkipped(pos, offsetMs > 0)
                }
                return target
            }
            return 0L
        }

        override fun seekTo(fraction: Float) {
            player?.let { p ->
                val duration = p.duration
                val pos = bindingAdapterPosition
                if (duration > 0 && pos != RecyclerView.NO_POSITION) {
                    val targetMs = (duration * fraction).toLong().coerceIn(0L, duration)
                    p.seekTo(targetMs)
                    onPlaybackProgress(pos, targetMs, duration, fraction, p.isPlaying)
                }
            }
        }

        override fun setMuted(muted: Boolean) {
            player?.volume = if (muted) 0f else 1f
        }

        override fun cleanup() {
            isActive = false
            job?.cancel()
            progressJob?.cancel()
            binding.playerView.animate().cancel()
            binding.playerView.scaleX = 1f
            binding.playerView.scaleY = 1f
            binding.playerView.translationX = 0f
            binding.playerView.translationY = 0f
            player?.release()
            player = null
            binding.playerView.player = null
        }
    }

    // ==========================================
    // GIF VIEWHOLDER
    // ==========================================
    inner class GifViewHolder(private val binding: ItemMediaGifBinding) : BaseMediaViewHolder(binding.root) {
        private var job: Job? = null
        private var progressJob: Job? = null
        private var isPaused = false
        private var animatable: Animatable? = null
        private var simProgress = 0f

        override fun bind(item: MediaItem, position: Int) {
            isActive = (position == activePosition)
            isPaused = false
            simProgress = 0f
            binding.progressLoading.visibility = View.VISIBLE
            val pinchListener = PinchToZoomListener(
                targetView = binding.ivGif,
                onZoneTap = { zone ->
                    when (zone) {
                        TapZone.LEFT -> skipBy(-5000L)
                        TapZone.MIDDLE -> onItemTapped(bindingAdapterPosition)
                        TapZone.RIGHT -> skipBy(5000L)
                    }
                },
                onSwipeRight = onSwipeRight
            )
            binding.root.setOnTouchListener(pinchListener)

            job?.cancel()
            job = scope.launch {
                val dataToLoad: Any = if (item.isRemote) {
                    val client = if (item.sourceId != null) {
                        me.lesovoy.lenta.data.source.SourceClientFactory.getClientForSourceId(binding.root.context, item.sourceId) ?: nextcloudClient
                    } else {
                        nextcloudClient
                    }
                    val cachedResult = client.downloadToCache(binding.root.context, item)
                    if (cachedResult.isSuccess) cachedResult.getOrThrow() else item.uriString
                } else if (item.uriString.startsWith("content://")) {
                    Uri.parse(item.uriString)
                } else if (item.path.isNotEmpty() && File(item.path).exists()) {
                    File(item.path)
                } else if (item.uriString.isNotEmpty()) {
                    Uri.parse(item.uriString)
                } else {
                    File(item.path)
                }

                binding.ivGif.load(dataToLoad) {
                    crossfade(true)
                    listener(
                        onSuccess = { _, result ->
                            binding.progressLoading.visibility = View.GONE
                            val drawable = result.drawable
                            if (drawable is Animatable) {
                                animatable = drawable
                                if (isActive && !isPaused) {
                                    drawable.start()
                                    startProgressUpdates()
                                } else {
                                    drawable.stop()
                                }
                            }
                        },
                        onError = { _, _ -> binding.progressLoading.visibility = View.GONE }
                    )
                }
            }
        }

        private fun startProgressUpdates() {
            progressJob?.cancel()
            progressJob = scope.launch {
                while (isActive && animatable?.isRunning == true) {
                    simProgress = (simProgress + 0.05f) % 1.0f
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onPlaybackProgress(pos, (simProgress * 3000).toLong(), 3000L, simProgress, true)
                    }
                    kotlinx.coroutines.delay(150)
                }
            }
        }

        override fun onActive() {
            isActive = true
            if (!isPaused) {
                animatable?.start()
                startProgressUpdates()
            }
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onPlaybackProgress(pos, (simProgress * 3000).toLong(), 3000L, simProgress, animatable?.isRunning == true)
            }
        }

        override fun onInactive() {
            isActive = false
            progressJob?.cancel()
            animatable?.stop()
            binding.ivGif.animate().cancel()
            binding.ivGif.scaleX = 1f
            binding.ivGif.scaleY = 1f
            binding.ivGif.translationX = 0f
            binding.ivGif.translationY = 0f
        }

        override fun togglePlayPause(): Boolean {
            val a = animatable
            val pos = bindingAdapterPosition
            return if (a != null && a.isRunning) {
                a.stop()
                progressJob?.cancel()
                isPaused = true
                if (pos != RecyclerView.NO_POSITION) {
                    onPlaybackProgress(pos, (simProgress * 3000).toLong(), 3000L, simProgress, false)
                }
                false
            } else {
                a?.start()
                isPaused = false
                startProgressUpdates()
                true
            }
        }

        override fun isPlaying(): Boolean = animatable?.isRunning == true

        override fun skipBy(offsetMs: Long): Long {
            val duration = 3000L
            val currentMs = (simProgress * duration).toLong()
            val targetMs = (currentMs + offsetMs).coerceIn(0L, duration)
            simProgress = (targetMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onPlaybackProgress(pos, targetMs, duration, simProgress, animatable?.isRunning == true)
                onVideoSkipped(pos, offsetMs > 0)
            }
            return targetMs
        }

        override fun seekTo(fraction: Float) {
            simProgress = fraction.coerceIn(0f, 1f)
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onPlaybackProgress(pos, (simProgress * 3000).toLong(), 3000L, simProgress, animatable?.isRunning == true)
            }
        }

        override fun setMuted(muted: Boolean) {}

        override fun cleanup() {
            isActive = false
            job?.cancel()
            progressJob?.cancel()
            animatable?.stop()
            animatable = null
            binding.ivGif.animate().cancel()
            binding.ivGif.scaleX = 1f
            binding.ivGif.scaleY = 1f
            binding.ivGif.translationX = 0f
            binding.ivGif.translationY = 0f
            binding.ivGif.setImageDrawable(null)
        }
    }

    // ==========================================
    // CBZ / COMIC VIEWHOLDER
    // ==========================================
    inner class CbzViewHolder(val binding: ItemMediaCbzBinding) : BaseMediaViewHolder(binding.root) {
        private var job: Job? = null
        private var pageFiles = listOf<File>()

        override fun bind(item: MediaItem, position: Int) {
            isActive = (position == activePosition)
            binding.progressLoading.visibility = View.VISIBLE
            val tapListener = ZoneTapListener { zone ->
                when (zone) {
                    TapZone.LEFT -> {
                        if (binding.cbzViewPager.currentItem == 0) {
                            onSwipeRight?.invoke()
                        } else {
                            previousPage()
                        }
                    }
                    TapZone.MIDDLE -> onItemTapped(bindingAdapterPosition)
                    TapZone.RIGHT -> nextPage()
                }
            }
            binding.root.setOnTouchListener(tapListener)

            job?.cancel()
            job = scope.launch {
                val pages = withContext(Dispatchers.IO) {
                    if (item.isRemote) {
                        val client = if (item.sourceId != null) {
                            me.lesovoy.lenta.data.source.SourceClientFactory.getClientForSourceId(binding.root.context, item.sourceId) ?: nextcloudClient
                        } else {
                            nextcloudClient
                        }
                        val cached = client.downloadToCache(binding.root.context, item)
                        val comicFile = if (cached.isSuccess) cached.getOrThrow() else File(item.path)
                        CbzReader.getComicPages(binding.root.context, comicFile)
                    } else if (item.uriString.startsWith("content://")) {
                        CbzReader.getComicPagesFromUri(binding.root.context, Uri.parse(item.uriString), item.id)
                    } else {
                        val comicFile = File(item.path)
                        if (comicFile.exists()) {
                            CbzReader.getComicPages(binding.root.context, comicFile)
                        } else if (item.uriString.isNotEmpty()) {
                            CbzReader.getComicPagesFromUri(binding.root.context, Uri.parse(item.uriString), item.id)
                        } else {
                            emptyList()
                        }
                    }
                }
                pageFiles = pages

                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    val pos = bindingAdapterPosition
                    if (pages.isNotEmpty()) {
                        val adapter = CbzPageAdapter(
                            pages = pages,
                            onLeftTap = {
                                if (binding.cbzViewPager.currentItem == 0) {
                                    onSwipeRight?.invoke()
                                } else {
                                    previousPage()
                                }
                            },
                            onMiddleTap = { onItemTapped(bindingAdapterPosition) },
                            onRightTap = { nextPage() },
                            onSwipeRight = {
                                if (binding.cbzViewPager.currentItem == 0) {
                                    onSwipeRight?.invoke()
                                }
                            }
                        )
                        binding.cbzViewPager.adapter = adapter
                        binding.cbzViewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL

                        binding.cbzViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                            override fun onPageSelected(pagePos: Int) {
                                val currentPos = bindingAdapterPosition
                                if (isActive && currentPos != RecyclerView.NO_POSITION) {
                                    onComicProgress(currentPos, pagePos + 1, pages.size)
                                    val fraction = (pagePos + 1).toFloat() / pages.size.toFloat()
                                    onPlaybackProgress(currentPos, (pagePos + 1).toLong(), pages.size.toLong(), fraction, false)
                                }
                            }
                        })

                        if (isActive && pos != RecyclerView.NO_POSITION) {
                            onComicProgress(pos, 1, pages.size)
                            val fraction = 1f / pages.size.toFloat()
                            onPlaybackProgress(pos, 1L, pages.size.toLong(), fraction, false)
                        }
                    } else {
                        if (isActive && pos != RecyclerView.NO_POSITION) {
                            onComicProgress(pos, 0, 0)
                        }
                    }
                }
            }
        }

        override fun onActive() {
            isActive = true
            val pos = bindingAdapterPosition
            if (pageFiles.isNotEmpty() && pos != RecyclerView.NO_POSITION) {
                val current = binding.cbzViewPager.currentItem
                onComicProgress(pos, current + 1, pageFiles.size)
                val fraction = (current + 1).toFloat() / pageFiles.size.toFloat()
                onPlaybackProgress(pos, (current + 1).toLong(), pageFiles.size.toLong(), fraction, false)
            }
        }

        override fun onInactive() {
            isActive = false
        }

        override fun togglePlayPause(): Boolean {
            return false
        }

        override fun isPlaying(): Boolean = false

        override fun previousPage(): Boolean {
            if (pageFiles.isEmpty()) return false
            val current = binding.cbzViewPager.currentItem
            if (current > 0) {
                binding.cbzViewPager.setCurrentItem(current - 1, true)
                return true
            }
            return false
        }

        override fun nextPage(): Boolean {
            if (pageFiles.isEmpty()) return false
            val current = binding.cbzViewPager.currentItem
            if (current < pageFiles.size - 1) {
                binding.cbzViewPager.setCurrentItem(current + 1, true)
                return true
            }
            return false
        }

        override fun seekTo(fraction: Float) {
            val pos = bindingAdapterPosition
            if (pageFiles.isNotEmpty() && pos != RecyclerView.NO_POSITION) {
                val targetPage = ((pageFiles.size - 1) * fraction).toInt().coerceIn(0, pageFiles.size - 1)
                binding.cbzViewPager.setCurrentItem(targetPage, true)
                onComicProgress(pos, targetPage + 1, pageFiles.size)
            }
        }

        override fun setMuted(muted: Boolean) {}

        override fun cleanup() {
            isActive = false
            job?.cancel()
            binding.cbzViewPager.adapter = null
        }
    }
}
