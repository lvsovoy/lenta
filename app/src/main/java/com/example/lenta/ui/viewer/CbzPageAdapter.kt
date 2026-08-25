package com.example.lenta.ui.viewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.lenta.databinding.ItemComicPageBinding
import java.io.File

class CbzPageAdapter(
    private val pages: List<File>,
    private val onLeftTap: () -> Unit,
    private val onMiddleTap: () -> Unit,
    private val onRightTap: () -> Unit
) : RecyclerView.Adapter<CbzPageAdapter.PageViewHolder>() {

    inner class PageViewHolder(val binding: ItemComicPageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
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
            binding.pageTouchOverlay.setOnTouchListener(
                ZoneTapListener { zone ->
                    when (zone) {
                        TapZone.LEFT -> onLeftTap()
                        TapZone.MIDDLE -> onMiddleTap()
                        TapZone.RIGHT -> onRightTap()
                    }
                }
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemComicPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size
}
