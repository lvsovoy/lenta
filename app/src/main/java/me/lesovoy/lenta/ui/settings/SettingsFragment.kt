package me.lesovoy.lenta.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import me.lesovoy.lenta.R
import me.lesovoy.lenta.data.nextcloud.NextcloudClient
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.nextcloud.NextcloudQrParser
import me.lesovoy.lenta.data.source.StorageSource
import me.lesovoy.lenta.data.source.StorageSourceManager
import me.lesovoy.lenta.data.source.StorageSourceType
import me.lesovoy.lenta.data.thumbnail.ThumbnailManager
import me.lesovoy.lenta.databinding.FragmentSettingsBinding
import me.lesovoy.lenta.databinding.ItemSourceCardBinding
import me.lesovoy.lenta.ui.scanner.QrScannerActivity
import me.lesovoy.lenta.ui.sources.SourceEditDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: NextcloudPreferences
    private lateinit var sourceManager: StorageSourceManager
    private lateinit var client: NextcloudClient

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQr(result.contents)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences = NextcloudPreferences(requireContext())
        sourceManager = StorageSourceManager(requireContext())
        client = NextcloudClient(preferences)

        loadCurrentSettings()
        setupListeners()
        renderSettingsSources()
    }

    private fun loadCurrentSettings() {
        binding.etServerUrl.setText(preferences.serverUrl)
        binding.etUsername.setText(preferences.username)
        binding.etPassword.setText(preferences.password)

        binding.switchShowHiddenFiles.isChecked = preferences.showHiddenFiles
        binding.switchMuteByDefault.isChecked = preferences.muteByDefault
        binding.switchLoopVideo.isChecked = preferences.loopVideo

        updateCacheSizeDisplay()
    }

    private fun renderSettingsSources() {
        binding.llSettingsSourcesContainer.removeAllViews()
        val sources = sourceManager.getAllSources().filter { it.type != StorageSourceType.LOCAL && it.id != StorageSource.NEXTCLOUD_SOURCE_ID }

        if (sources.isEmpty()) {
            val tvEmpty = android.widget.TextView(requireContext()).apply {
                text = "No additional sources configured. Tap 'Add' to connect Google Drive, OneDrive, WebDAV, SMB, or FTP."
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.teal_700))
                setPadding(8, 8, 8, 8)
            }
            binding.llSettingsSourcesContainer.addView(tvEmpty)
            return
        }

        for (source in sources) {
            val cardBinding = ItemSourceCardBinding.inflate(layoutInflater, binding.llSettingsSourcesContainer, false)
            cardBinding.tvSourceTitle.text = source.name
            cardBinding.tvSourceSubtitle.text = source.getDisplaySubtitle()

            val iconRes = when (source.type) {
                StorageSourceType.NEXTCLOUD -> R.drawable.ic_nextcloud
                StorageSourceType.WEBDAV -> R.drawable.ic_webdav
                StorageSourceType.GOOGLE_DRIVE -> R.drawable.ic_google_drive
                StorageSourceType.ONEDRIVE -> R.drawable.ic_onedrive
                StorageSourceType.SMB -> R.drawable.ic_smb
                StorageSourceType.FTP -> R.drawable.ic_ftp
                else -> R.drawable.ic_nextcloud
            }
            cardBinding.ivSourceIcon.setImageResource(iconRes)
            cardBinding.tvSourceTypeTag.visibility = View.VISIBLE
            cardBinding.tvSourceTypeTag.text = source.type.name
            cardBinding.btnSourceMenu.visibility = View.VISIBLE

            cardBinding.btnSourceMenu.setOnClickListener { v ->
                showSourceSettingsMenu(v, source)
            }

            cardBinding.cardSourceRoot.setOnClickListener {
                SourceEditDialog.show(parentFragmentManager, source) {
                    renderSettingsSources()
                }
            }

            binding.llSettingsSourcesContainer.addView(cardBinding.root)
        }
    }

    private fun showSourceSettingsMenu(anchor: View, source: StorageSource) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Edit")
        if (source.type != StorageSourceType.LOCAL) {
            popup.menu.add(0, 2, 1, getString(R.string.remove_source))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    SourceEditDialog.show(parentFragmentManager, source) {
                        renderSettingsSources()
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
                renderSettingsSources()
                Snackbar.make(binding.root, getString(R.string.source_removed, source.name), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.remove_source_cancel, null)
            .show()
    }

    private fun updateCacheSizeDisplay() {
        viewLifecycleOwner.lifecycleScope.launch {
            val size = ThumbnailManager.getCacheSizeBytes(requireContext())
            binding.tvCacheSize.text = ThumbnailManager.formatCacheSize(size)
        }
    }

    private fun setupListeners() {
        binding.btnAddSourceSettings.setOnClickListener {
            SourceEditDialog.show(parentFragmentManager) {
                renderSettingsSources()
            }
        }

        binding.btnScanQr.setOnClickListener {
            launchQrScanner()
        }

        binding.switchShowHiddenFiles.setOnCheckedChangeListener { _, isChecked ->
            preferences.showHiddenFiles = isChecked
        }

        binding.switchMuteByDefault.setOnCheckedChangeListener { _, isChecked ->
            preferences.muteByDefault = isChecked
        }

        binding.switchLoopVideo.setOnCheckedChangeListener { _, isChecked ->
            preferences.loopVideo = isChecked
        }

        binding.btnClearCache.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                ThumbnailManager.clearCache(requireContext())
                updateCacheSizeDisplay()
                Snackbar.make(binding.root, R.string.cache_cleared, Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveCredentials.setOnClickListener {
            saveCredentials()
            Snackbar.make(binding.root, "Settings saved successfully", Snackbar.LENGTH_SHORT).show()
        }

        binding.btnTestConnection.setOnClickListener {
            saveCredentials()
            testConnection()
        }
    }

    private fun launchQrScanner() {
        val options = QrScannerActivity.createScanOptions(getString(R.string.scan_qr_prompt))
        qrScannerLauncher.launch(options)
    }

    private fun handleScannedQr(rawContent: String) {
        val creds = NextcloudQrParser.parse(rawContent)
        if (creds != null) {
            binding.etServerUrl.setText(creds.serverUrl)
            binding.etUsername.setText(creds.username)
            binding.etPassword.setText(creds.password)
            saveCredentials()
            client = NextcloudClient(preferences)
            Snackbar.make(binding.root, R.string.qr_scan_success, Snackbar.LENGTH_LONG).show()
            testConnection()
        } else {
            Snackbar.make(binding.root, R.string.qr_scan_invalid, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun saveCredentials() {
        preferences.serverUrl = binding.etServerUrl.text.toString().trim()
        preferences.username = binding.etUsername.text.toString().trim()
        preferences.password = binding.etPassword.text.toString()

        val nextcloudSource = sourceManager.getSource(StorageSource.NEXTCLOUD_SOURCE_ID)
        if (nextcloudSource != null) {
            sourceManager.saveSource(
                nextcloudSource.copy(
                    serverUrl = preferences.serverUrl,
                    username = preferences.username,
                    password = preferences.password
                )
            )
        }
    }

    private fun testConnection() {
        binding.tvConnectionStatus.visibility = View.VISIBLE
        binding.tvConnectionStatus.text = getString(R.string.nextcloud_connecting)
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_700))

        viewLifecycleOwner.lifecycleScope.launch {
            val result = client.testConnection()
            if (result.isSuccess) {
                binding.tvConnectionStatus.text = getString(R.string.nextcloud_connection_success)
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_700))
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                binding.tvConnectionStatus.text = getString(R.string.nextcloud_connection_failed, error)
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
