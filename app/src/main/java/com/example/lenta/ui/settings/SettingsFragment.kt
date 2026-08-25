package com.example.lenta.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.lenta.R
import com.example.lenta.data.nextcloud.NextcloudClient
import com.example.lenta.data.nextcloud.NextcloudPreferences
import com.example.lenta.data.nextcloud.NextcloudQrParser
import com.example.lenta.data.thumbnail.ThumbnailManager
import com.example.lenta.databinding.FragmentSettingsBinding
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: NextcloudPreferences
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
        client = NextcloudClient(preferences)

        loadCurrentSettings()
        setupListeners()
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

    private fun updateCacheSizeDisplay() {
        viewLifecycleOwner.lifecycleScope.launch {
            val size = ThumbnailManager.getCacheSizeBytes(requireContext())
            binding.tvCacheSize.text = ThumbnailManager.formatCacheSize(size)
        }
    }

    private fun setupListeners() {
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
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.scan_qr_prompt))
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        qrScannerLauncher.launch(options)
    }

    private fun handleScannedQr(rawContent: String) {
        val creds = NextcloudQrParser.parse(rawContent)
        if (creds != null) {
            binding.etServerUrl.setText(creds.serverUrl)
            binding.etUsername.setText(creds.username)
            binding.etPassword.setText(creds.password)
            saveCredentials()
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
