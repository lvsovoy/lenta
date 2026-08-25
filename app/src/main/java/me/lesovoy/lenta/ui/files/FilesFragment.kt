package me.lesovoy.lenta.ui.files

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import me.lesovoy.lenta.R
import me.lesovoy.lenta.data.source.StorageSource
import me.lesovoy.lenta.data.source.StorageSourceManager
import me.lesovoy.lenta.data.source.StorageSourceType
import me.lesovoy.lenta.databinding.FragmentFilesBinding
import me.lesovoy.lenta.databinding.ItemSourceCardBinding
import me.lesovoy.lenta.ui.sources.SourceEditDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private lateinit var sourceManager: StorageSourceManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sourceManager = StorageSourceManager(requireContext())

        binding.cardAddSource.setOnClickListener {
            SourceEditDialog.show(parentFragmentManager) {
                renderSourceCards()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderSourceCards()
    }

    private fun renderSourceCards() {
        binding.llSourcesContainer.removeAllViews()
        val sources = sourceManager.getAllSources()

        for (source in sources) {
            val cardBinding = ItemSourceCardBinding.inflate(layoutInflater, binding.llSourcesContainer, false)

            cardBinding.tvSourceTitle.text = source.name
            cardBinding.tvSourceSubtitle.text = source.getDisplaySubtitle()

            val iconRes = when (source.type) {
                StorageSourceType.LOCAL -> R.drawable.ic_folder
                StorageSourceType.NEXTCLOUD -> R.drawable.ic_cloud
                StorageSourceType.WEBDAV -> R.drawable.ic_webdav
                StorageSourceType.GOOGLE_DRIVE -> R.drawable.ic_google_drive
                StorageSourceType.ONEDRIVE -> R.drawable.ic_onedrive
                StorageSourceType.SMB -> R.drawable.ic_smb
                StorageSourceType.FTP -> R.drawable.ic_ftp
            }
            cardBinding.ivSourceIcon.setImageResource(iconRes)

            if (source.type != StorageSourceType.LOCAL && source.type != StorageSourceType.NEXTCLOUD) {
                cardBinding.tvSourceTypeTag.visibility = View.VISIBLE
                cardBinding.tvSourceTypeTag.text = source.type.name
            } else {
                cardBinding.tvSourceTypeTag.visibility = View.GONE
            }

            if (source.type != StorageSourceType.LOCAL) {
                cardBinding.btnSourceMenu.visibility = View.VISIBLE
                cardBinding.btnSourceMenu.setOnClickListener { v ->
                    showSourceMenu(v, source)
                }
            } else {
                cardBinding.btnSourceMenu.visibility = View.GONE
            }

            cardBinding.cardSourceRoot.setOnClickListener {
                onSourceClicked(source)
            }

            cardBinding.cardSourceRoot.setOnLongClickListener {
                if (source.type != StorageSourceType.LOCAL) {
                    showSourceMenu(cardBinding.btnSourceMenu, source)
                    true
                } else false
            }

            binding.llSourcesContainer.addView(cardBinding.root)
        }
    }

    private fun onSourceClicked(source: StorageSource) {
        when (source.type) {
            StorageSourceType.LOCAL -> {
                findNavController().navigate(R.id.nav_local)
            }
            StorageSourceType.NEXTCLOUD,
            StorageSourceType.WEBDAV,
            StorageSourceType.GOOGLE_DRIVE,
            StorageSourceType.ONEDRIVE,
            StorageSourceType.SMB,
            StorageSourceType.FTP -> {
                if (source.isConfigured()) {
                    val args = Bundle().apply {
                        putString("sourceId", source.id)
                    }
                    findNavController().navigate(R.id.nav_nextcloud, args)
                } else {
                    SourceEditDialog.show(parentFragmentManager, source) {
                        renderSourceCards()
                    }
                }
            }
        }
    }

    private fun showSourceMenu(anchor: View, source: StorageSource) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Edit")
        if (source.type != StorageSourceType.LOCAL) {
            popup.menu.add(0, 2, 1, getString(R.string.remove_source))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    SourceEditDialog.show(parentFragmentManager, source) {
                        renderSourceCards()
                    }
                    true
                }
                2 -> {
                    confirmDeleteSource(source)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDeleteSource(source: StorageSource) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_source_dialog_title)
            .setMessage(getString(R.string.remove_source_dialog_message, source.name))
            .setPositiveButton(R.string.remove_source_confirm) { _, _ ->
                sourceManager.deleteSource(source.id)
                renderSourceCards()
                Snackbar.make(binding.root, getString(R.string.source_removed, source.name), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.remove_source_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
