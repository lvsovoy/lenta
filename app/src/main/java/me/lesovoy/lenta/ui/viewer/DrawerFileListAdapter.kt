package me.lesovoy.lenta.ui.viewer

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
import me.lesovoy.lenta.R
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.thumbnail.ThumbnailManager
import me.lesovoy.lenta.databinding.ItemDrawerFileBinding
import java.io.File
import java.util.Locale

class DrawerFileListAdapter(
    private val onItemClick: (item: MediaItem, position: Int) -> Unit
) : RecyclerView.Adapter<DrawerFileListAdapter.DrawerViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private var currentPlayingId: String? = null
    private var currentPlayingPath: String? = null

    fun submitList(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<MediaItem> = items

    fun setCurrentPlaying(id: String?, path: String?) {
        val oldId = currentPlayingId
        val oldPath = currentPlayingPath
        currentPlayingId = id
        currentPlayingPath = path

        items.forEachIndexed { index, item ->
            val wasPlaying = (item.id == oldId || (oldPath != null && item.path.isNotEmpty() && item.path == oldPath))
            val isPlaying = (item.id == id || (path != null && item.path.isNotEmpty() && item.path == path))
            if (wasPlaying != isPlaying) {
                notifyItemChanged(index)
            }
        }
    }

    inner class DrawerViewHolder(private val binding: ItemDrawerFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem, position: Int) {
            binding.tvDrawerFileName.text = item.name

            val isCurrentlyPlaying = !item.isDirectory && (
                item.id == currentPlayingId ||
                (currentPlayingPath != null && item.path.isNotEmpty() && item.path == currentPlayingPath)
            )

            if (isCurrentlyPlaying) {
                binding.drawerItemRoot.setBackgroundResource(R.drawable.bg_drawer_item_selected)
                binding.layoutDrawerPlaying.visibility = View.VISIBLE
            } else {
                binding.drawerItemRoot.setBackgroundResource(R.drawable.bg_drawer_item_ripple)
                binding.layoutDrawerPlaying.visibility = View.GONE
            }

            if (item.isDirectory) {
                binding.ivDrawerFolderArrow.visibility = View.VISIBLE
                binding.tvDrawerBadge.visibility = View.GONE
                binding.tvDrawerFileInfo.text = "Folder"
                binding.ivDrawerThumb.setImageDrawable(null)
                binding.ivDrawerThumb.visibility = View.GONE
                binding.ivDrawerIcon.visibility = View.VISIBLE
                binding.ivDrawerIcon.setImageResource(R.drawable.ic_folder)
            } else {
                binding.ivDrawerFolderArrow.visibility = View.GONE
                binding.tvDrawerBadge.visibility = View.VISIBLE
                binding.tvDrawerFileInfo.text = formatFileSize(item.size)

                when (item.type) {
                    MediaType.IMAGE -> binding.tvDrawerBadge.text = "IMG"
                    MediaType.VIDEO -> binding.tvDrawerBadge.text = "VID"
                    MediaType.GIF -> binding.tvDrawerBadge.text = "GIF"
                    MediaType.CBZ -> binding.tvDrawerBadge.text = "CBZ"
                }

                val defaultIconRes = when (item.type) {
                    MediaType.IMAGE -> R.drawable.ic_image
                    MediaType.VIDEO -> R.drawable.ic_video
                    MediaType.GIF -> R.drawable.ic_gif
                    MediaType.CBZ -> R.drawable.ic_comic
                }

                val context = binding.root.context
                if (item.isRemote) {
                    val client = if (item.sourceId != null) {
                        me.lesovoy.lenta.data.source.SourceClientFactory.getClientForSourceId(context, item.sourceId) ?: NextcloudClient(NextcloudPreferences(context))
                    } else {
                        NextcloudClient(NextcloudPreferences(context))
                    }

                    val cacheSubdir = File(context.cacheDir, if (item.isNextcloud) "nextcloud_cache" else "remote_cache/${item.sourceId ?: ""}")
                    val safeFileName = "${item.id.hashCode()}_${item.name}"
                    val cachedFile = File(cacheSubdir, safeFileName)

                    val loadTarget: Any = if (cachedFile.exists() && cachedFile.length() > 0) {
                        when (item.type) {
                            MediaType.VIDEO -> {
                                val cacheKey = "vid_${cachedFile.name.hashCode()}_${cachedFile.length()}_${cachedFile.lastModified()}.jpg"
                                val cached = File(ThumbnailManager.getVideoThumbnailDir(context.cacheDir), cacheKey)
                                if (cached.exists() && cached.length() > 0L) cached else cachedFile
                            }
                            MediaType.CBZ -> {
                                val cacheKey = "cover_${cachedFile.name.hashCode()}_${cachedFile.length()}_${cachedFile.lastModified()}"
                                val cached = ThumbnailManager.getCbzThumbnailDir(context.cacheDir)
                                    .listFiles { _, name -> name.startsWith(cacheKey) }
                                    ?.firstOrNull { it.length() > 0L }
                                cached ?: cachedFile
                            }
                            else -> cachedFile
                        }
                    } else {
                        when (item.type) {
                            MediaType.VIDEO -> {
                                val remCacheKey = ThumbnailManager.getVideoThumbnailCacheKey(item)
                                val cached = File(ThumbnailManager.getVideoThumbnailDir(context.cacheDir), remCacheKey)
                                if (cached.exists() && cached.length() > 0L) {
                                    cached
                                } else {
                                    client.getPreviewUrl(item) ?: item.uriString
                                }
                            }
                            MediaType.CBZ -> {
                                val remCacheKey = ThumbnailManager.getCbzCoverCacheKey(item)
                                val cached = ThumbnailManager.getCbzThumbnailDir(context.cacheDir)
                                    .listFiles { _, name -> name.startsWith(remCacheKey) }
                                    ?.firstOrNull { it.length() > 0L }
                                if (cached != null && cached.length() > 0L) {
                                    cached
                                } else {
                                    client.getPreviewUrl(item) ?: item.uriString
                                }
                            }
                            MediaType.IMAGE, MediaType.GIF -> {
                                client.getPreviewUrl(item) ?: item.uriString
                            }
                        }
                    }

                    binding.ivDrawerThumb.visibility = View.VISIBLE
                    binding.ivDrawerIcon.visibility = View.GONE

                    binding.ivDrawerThumb.load(loadTarget) {
                        crossfade(true)
                        placeholder(defaultIconRes)
                        error(defaultIconRes)
                        if (item.type == MediaType.VIDEO) {
                            videoFrameMillis(1000L)
                        }
                    }
                } else {
                    // Local file
                    val file = if (item.path.isNotEmpty()) File(item.path) else null
                    val loadTarget: Any? = if (file != null && file.exists()) {
                        when (item.type) {
                            MediaType.VIDEO -> {
                                val cacheKey = "vid_${file.name.hashCode()}_${file.length()}_${file.lastModified()}.jpg"
                                val cached = File(ThumbnailManager.getVideoThumbnailDir(context.cacheDir), cacheKey)
                                if (cached.exists() && cached.length() > 0L) cached else file
                            }
                            MediaType.CBZ -> {
                                val cacheKey = "cover_${file.name.hashCode()}_${file.length()}_${file.lastModified()}"
                                val cached = ThumbnailManager.getCbzThumbnailDir(context.cacheDir)
                                    .listFiles { _, name -> name.startsWith(cacheKey) }
                                    ?.firstOrNull { it.length() > 0L }
                                cached ?: file
                            }
                            else -> file
                        }
                    } else if (item.uriString.isNotEmpty()) {
                        Uri.parse(item.uriString)
                    } else {
                        null
                    }

                    if (loadTarget != null) {
                        binding.ivDrawerThumb.visibility = View.VISIBLE
                        binding.ivDrawerIcon.visibility = View.GONE
                        binding.ivDrawerThumb.load(loadTarget) {
                            crossfade(true)
                            placeholder(defaultIconRes)
                            error(defaultIconRes)
                            if (item.type == MediaType.VIDEO && loadTarget is File) {
                                videoFrameMillis(1000L)
                            }
                        }
                    } else {
                        binding.ivDrawerThumb.setImageDrawable(null)
                        binding.ivDrawerThumb.visibility = View.GONE
                        binding.ivDrawerIcon.visibility = View.VISIBLE
                        binding.ivDrawerIcon.setImageResource(defaultIconRes)
                    }
                }
            }

            binding.root.setOnClickListener {
                onItemClick(item, position)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DrawerViewHolder {
        val binding = ItemDrawerFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DrawerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DrawerViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    private fun formatFileSize(sizeInBytes: Long): String {
        if (sizeInBytes <= 0) return ""
        val kb = sizeInBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$sizeInBytes B"
        }
    }
}
