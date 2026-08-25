package me.lesovoy.lenta.ui.browser

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
import me.lesovoy.lenta.R
import me.lesovoy.lenta.data.local.LocalMediaRepository
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.model.MediaType
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.thumbnail.ThumbnailManager
import me.lesovoy.lenta.databinding.ItemFileGridBinding
import java.io.File
import java.util.Locale

class FileListAdapter(
    private val onItemClick: (item: MediaItem, position: Int) -> Unit
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    fun submitList(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<MediaItem> = items

    inner class FileViewHolder(private val binding: ItemFileGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            binding.tvFileName.text = item.name

            if (item.isDirectory) {
                binding.tvBadge.visibility = View.GONE
                binding.tvFileInfo.text = "Folder"

                val context = binding.root.context
                val firstMedia = if (!item.isRemote) {
                    LocalMediaRepository.getFirstMediaFileInDirectory(File(item.path))
                } else null

                if (firstMedia != null) {
                    binding.ivTypeIcon.visibility = View.GONE
                    binding.ivThumbnail.visibility = View.VISIBLE

                    val mediaType = LocalMediaRepository.getMediaType(firstMedia)
                    val loadTarget: Any = when (mediaType) {
                        MediaType.VIDEO -> {
                            val cacheKey = "vid_${firstMedia.name.hashCode()}_${firstMedia.length()}_${firstMedia.lastModified()}.jpg"
                            val cached = File(ThumbnailManager.getVideoThumbnailDir(context.cacheDir), cacheKey)
                            if (cached.exists() && cached.length() > 0L) cached else firstMedia
                        }
                        MediaType.CBZ -> {
                            val cacheKey = "cover_${firstMedia.name.hashCode()}_${firstMedia.length()}_${firstMedia.lastModified()}"
                            val cached = ThumbnailManager.getCbzThumbnailDir(context.cacheDir)
                                .listFiles { _, name -> name.startsWith(cacheKey) }
                                ?.firstOrNull { it.length() > 0L }
                            cached ?: firstMedia
                        }
                        else -> firstMedia
                    }

                    binding.ivThumbnail.load(loadTarget) {
                        crossfade(true)
                        placeholder(R.drawable.ic_folder)
                        error(R.drawable.ic_folder)
                        if (mediaType == MediaType.VIDEO) {
                            videoFrameMillis(1000L)
                        }
                    }
                } else {
                    binding.ivThumbnail.setImageDrawable(null)
                    binding.ivThumbnail.visibility = View.GONE
                    binding.ivTypeIcon.visibility = View.VISIBLE
                    binding.ivTypeIcon.setImageResource(R.drawable.ic_folder)
                }
            } else {
                binding.ivTypeIcon.visibility = View.GONE
                binding.ivThumbnail.visibility = View.VISIBLE
                binding.tvBadge.visibility = View.VISIBLE

                when (item.type) {
                    MediaType.IMAGE -> {
                        binding.tvBadge.text = "IMG"
                    }
                    MediaType.VIDEO -> {
                        binding.tvBadge.text = "VIDEO"
                    }
                    MediaType.GIF -> {
                        binding.tvBadge.text = "GIF"
                    }
                    MediaType.CBZ -> {
                        binding.tvBadge.text = "CBZ"
                    }
                }

                val sizeStr = formatFileSize(item.size)
                binding.tvFileInfo.text = sizeStr

                val defaultIconRes = when (item.type) {
                    MediaType.IMAGE -> R.drawable.ic_image
                    MediaType.VIDEO -> R.drawable.ic_video
                    MediaType.GIF -> R.drawable.ic_gif
                    MediaType.CBZ -> R.drawable.ic_comic
                }

                // Load thumbnail with caching and frame/cover extraction
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
                                if (cached != null) {
                                    cached
                                } else {
                                    client.getPreviewUrl(item) ?: defaultIconRes
                                }
                            }
                            MediaType.IMAGE, MediaType.GIF -> {
                                client.getPreviewUrl(item) ?: item.uriString
                            }
                        }
                    }

                    binding.ivThumbnail.load(loadTarget) {
                        crossfade(true)
                        placeholder(defaultIconRes)
                        error(defaultIconRes)
                        if (item.type == MediaType.VIDEO) {
                            videoFrameMillis(1000L)
                        }
                    }
                } else {
                    val file = File(item.path)
                    val loadTarget: Any = when (item.type) {
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

                    binding.ivThumbnail.load(loadTarget) {
                        crossfade(true)
                        placeholder(defaultIconRes)
                        error(defaultIconRes)
                        if (item.type == MediaType.VIDEO) {
                            videoFrameMillis(1000L)
                        }
                    }
                }
            }

            binding.root.setOnClickListener {
                onItemClick(item, bindingAdapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}
