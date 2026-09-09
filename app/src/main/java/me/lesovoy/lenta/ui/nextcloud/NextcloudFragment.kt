package me.lesovoy.lenta.ui.nextcloud

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import me.lesovoy.lenta.R
import me.lesovoy.lenta.data.model.MediaItem
import me.lesovoy.lenta.data.nextcloud.NextcloudPreferences
import me.lesovoy.lenta.data.nextcloud.NextcloudQrParser
import me.lesovoy.lenta.data.source.RemoteFileClient
import me.lesovoy.lenta.data.source.SourceClientFactory
import me.lesovoy.lenta.data.source.StorageSource
import me.lesovoy.lenta.data.source.StorageSourceManager
import me.lesovoy.lenta.data.source.StorageSourceType
import me.lesovoy.lenta.data.thumbnail.ThumbnailManager
import me.lesovoy.lenta.databinding.FragmentNextcloudBinding
import me.lesovoy.lenta.ui.browser.FileListAdapter
import me.lesovoy.lenta.ui.scanner.QrScannerActivity
import me.lesovoy.lenta.ui.viewer.MediaViewerActivity
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NextcloudFragment : Fragment() {

    private var _binding: FragmentNextcloudBinding? = null
    private val binding get() = _binding!!

    private lateinit var sourceManager: StorageSourceManager
    private lateinit var currentSource: StorageSource
    private lateinit var client: RemoteFileClient
    private lateinit var adapter: FileListAdapter

    private var currentRemotePath: String = ""
    private var currentItems: List<MediaItem> = emptyList()
    private var lastShowHiddenState: Boolean? = null
    private var pregenerationJob: Job? = null

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!navigateUp()) {
                findNavController().navigateUp()
            }
        }
    }

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
        _binding = FragmentNextcloudBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sourceManager = StorageSourceManager(requireContext())
        val sourceId = arguments?.getString("sourceId") ?: StorageSource.NEXTCLOUD_SOURCE_ID
        currentSource = sourceManager.getSource(sourceId) ?: StorageSource(
            id = StorageSource.NEXTCLOUD_SOURCE_ID,
            name = "Nextcloud",
            type = StorageSourceType.NEXTCLOUD,
            serverUrl = NextcloudPreferences(requireContext()).serverUrl,
            username = NextcloudPreferences(requireContext()).username,
            password = NextcloudPreferences(requireContext()).password,
            isDefault = true
        )
        client = SourceClientFactory.getClient(requireContext(), currentSource)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        setupRecyclerView()
        setupControls()
        updateHiddenFilesToggleIcon()
        checkConfigurationAndLoad()
    }

    private fun setupRecyclerView() {
        adapter = FileListAdapter { item, _ ->
            if (item.isDirectory) {
                loadFolder(item.effectiveRemotePath)
            } else {
                openMediaViewer(item)
            }
        }

        binding.recyclerNextcloud.layoutManager = GridLayoutManager(requireContext(), getOptimalSpanCount())
        binding.recyclerNextcloud.adapter = adapter

        binding.swipeRefreshNextcloud.setOnRefreshListener {
            loadFolder(currentRemotePath)
        }
    }

    private fun getOptimalSpanCount(): Int {
        val resourceColumns = resources.getInteger(R.integer.grid_columns)
        val screenWidthDp = resources.configuration.screenWidthDp
        val computedColumns = (screenWidthDp / 160).coerceAtLeast(2)
        return maxOf(resourceColumns, computedColumns)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (binding.recyclerNextcloud.layoutManager as? GridLayoutManager)?.spanCount = getOptimalSpanCount()
    }

    private fun setupControls() {
        binding.btnConfigureNextcloud.setOnClickListener {
            findNavController().navigate(R.id.nav_settings)
        }

        binding.btnScanQrNextcloud.visibility = if (currentSource.type == StorageSourceType.NEXTCLOUD) View.VISIBLE else View.GONE
        binding.btnScanQrNextcloud.setOnClickListener {
            launchQrScanner()
        }

        binding.btnUpNextcloud.setOnClickListener {
            if (!navigateUp()) {
                findNavController().navigateUp()
            }
        }

        binding.btnRefreshNextcloud.setOnClickListener {
            loadFolder(currentRemotePath)
        }

        binding.btnToggleHiddenNextcloud.setOnClickListener {
            val nextState = !currentSource.showHidden
            currentSource = currentSource.copy(showHidden = nextState)
            sourceManager.saveSource(currentSource)
            updateHiddenFilesToggleIcon()
            val msg = if (currentSource.showHidden) R.string.showing_hidden_files else R.string.hiding_hidden_files
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            loadFolder(currentRemotePath)
        }
    }

    fun canNavigateUp(): Boolean {
        return currentRemotePath.trim('/').isNotEmpty()
    }

    fun navigateUp(): Boolean {
        val clean = currentRemotePath.trim('/')
        if (clean.isNotEmpty()) {
            val parent = if (clean.contains('/')) clean.substringBeforeLast('/') else ""
            loadFolder(parent)
            return true
        }
        return false
    }

    private fun updateNavigationUi() {
        val canUp = canNavigateUp()
        binding.btnUpNextcloud.isEnabled = true
        binding.btnUpNextcloud.alpha = 1.0f
        backPressedCallback.isEnabled = canUp
    }

    private fun updateHiddenFilesToggleIcon() {
        if (currentSource.showHidden) {
            binding.btnToggleHiddenNextcloud.setImageResource(R.drawable.ic_visibility)
            binding.btnToggleHiddenNextcloud.contentDescription = getString(R.string.hide_hidden_files)
            binding.btnToggleHiddenNextcloud.alpha = 1.0f
        } else {
            binding.btnToggleHiddenNextcloud.setImageResource(R.drawable.ic_visibility_off)
            binding.btnToggleHiddenNextcloud.contentDescription = getString(R.string.show_hidden_files)
            binding.btnToggleHiddenNextcloud.alpha = 0.6f
        }
    }

    private fun launchQrScanner() {
        val options = QrScannerActivity.createScanOptions(getString(R.string.scan_qr_prompt))
        qrScannerLauncher.launch(options)
    }

    private fun handleScannedQr(rawContent: String) {
        val creds = NextcloudQrParser.parse(rawContent)
        if (creds != null) {
            currentSource = currentSource.copy(
                serverUrl = creds.serverUrl,
                username = creds.username,
                password = creds.password
            )
            sourceManager.saveSource(currentSource)
            client = SourceClientFactory.getClient(requireContext(), currentSource)
            currentItems = emptyList()
            currentRemotePath = ""
            Snackbar.make(binding.root, R.string.qr_scan_success, Snackbar.LENGTH_LONG).show()
            checkConfigurationAndLoad()
            loadFolder("")
        } else {
            Snackbar.make(binding.root, R.string.qr_scan_invalid, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val reloadedSource = sourceManager.getSource(currentSource.id)
        if (reloadedSource != null) {
            currentSource = reloadedSource
            client = SourceClientFactory.getClient(requireContext(), currentSource)
        }
        updateHiddenFilesToggleIcon()
        updateNavigationUi()
        if (currentSource.isConfigured() && lastShowHiddenState != null && lastShowHiddenState != currentSource.showHidden) {
            loadFolder(currentRemotePath)
        } else {
            checkConfigurationAndLoad()
        }
    }

    private fun checkConfigurationAndLoad() {
        updateNavigationUi()
        if (!currentSource.isConfigured()) {
            binding.cardNotConfigured.visibility = View.VISIBLE
            binding.cardNavBar.visibility = View.GONE
            binding.recyclerNextcloud.visibility = View.GONE
            binding.tvEmptyNextcloud.visibility = View.GONE
            binding.tvErrorNextcloud.visibility = View.GONE
        } else {
            binding.cardNotConfigured.visibility = View.GONE
            binding.cardNavBar.visibility = View.VISIBLE
            binding.recyclerNextcloud.visibility = View.VISIBLE
            if (currentItems.isEmpty()) {
                loadFolder(currentRemotePath)
            }
        }
    }

    fun loadFolder(remotePath: String) {
        lastShowHiddenState = currentSource.showHidden
        currentRemotePath = remotePath.trim('/')
        binding.tvNextcloudPath.text = if (currentRemotePath.isEmpty()) "/" else "/$currentRemotePath"
        updateNavigationUi()
        binding.progressNextcloud.visibility = View.VISIBLE
        binding.tvEmptyNextcloud.visibility = View.GONE
        binding.tvErrorNextcloud.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = client.listFolder(currentRemotePath, currentSource.showHidden)
            binding.progressNextcloud.visibility = View.GONE
            binding.swipeRefreshNextcloud.isRefreshing = false

            if (result.isSuccess) {
                val items = result.getOrThrow()
                currentItems = items
                if (items.isEmpty()) {
                    binding.tvEmptyNextcloud.visibility = View.VISIBLE
                } else {
                    binding.tvEmptyNextcloud.visibility = View.GONE
                }
                adapter.submitList(items)

                pregenerationJob?.cancel()
                pregenerationJob = viewLifecycleOwner.lifecycleScope.launch {
                    ThumbnailManager.pregenerateThumbnails(requireContext(), items) { generatedItem ->
                        val index = adapter.getItems().indexOfFirst { it.id == generatedItem.id }
                        if (index != -1) {
                            adapter.notifyItemChanged(index)
                        }
                    }
                }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                binding.tvErrorNextcloud.text = getString(R.string.nextcloud_connection_failed, error)
                binding.tvErrorNextcloud.visibility = View.VISIBLE
            }
        }
    }

    private fun openMediaViewer(selectedItem: MediaItem) {
        val playableItems = currentItems.filter { !it.isDirectory }
        val startIndex = playableItems.indexOfFirst { it.id == selectedItem.id }.coerceAtLeast(0)

        val intent = MediaViewerActivity.createIntent(
            requireContext(),
            playableItems,
            startIndex
        )
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pregenerationJob?.cancel()
        pregenerationJob = null
        _binding = null
    }
}
