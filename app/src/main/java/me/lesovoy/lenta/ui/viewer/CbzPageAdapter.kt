package me.lesovoy.lenta.ui.viewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import me.lesovoy.lenta.databinding.ItemComicPageBinding
import java.io.File

class CbzPageAdapter(
    private val pages: List<File>,
    private val onLeftTap: () -> Unit,
    private val onMiddleTap: () -> Unit,
    private val onRightTap: () -> Unit,
    private val onSwipeRight: (() -> Unit)? = null
) : RecyclerView.Adapter<CbzPageAdapter.PageViewHolder>() {

    inner class PageViewHolder(val binding: ItemComicPageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File, position: Int) {
            binding.progressLoading.visibility = View.VISIBLE
            binding.ivComicPage.load(file) {
                crossfade(true)
                listener(
                    onSuccess = { _, _ ->
                        binding.progressLoading.visibility = View.GONE
                    },
                    onError = { _, _ ->
                        binding.progressLoading.visibility = View.GONE
                    }
                )
            }
            val pinchListener = PinchToZoomListener(
                targetView = binding.ivComicPage,
                onZoneTap = { zone ->
                    when (zone) {
                        TapZone.LEFT -> onLeftTap()
                        TapZone.MIDDLE -> onMiddleTap()
                        TapZone.RIGHT -> onRightTap()
                    }
                },
                onSwipeRight = if (position == 0) onSwipeRight else null
            )
            binding.pageTouchOverlay.setOnTouchListener(pinchListener)
            binding.root.setOnTouchListener(pinchListener)
        }

        fun cleanup() {
            binding.ivComicPage.animate().cancel()
            binding.ivComicPage.scaleX = 1f
            binding.ivComicPage.scaleY = 1f
            binding.ivComicPage.translationX = 0f
            binding.ivComicPage.translationY = 0f
            binding.ivComicPage.setImageDrawable(null)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemComicPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position], position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.cleanup()
    }

    override fun getItemCount(): Int = pages.size
}
