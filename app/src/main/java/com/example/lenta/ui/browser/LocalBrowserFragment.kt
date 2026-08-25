package com.example.lenta.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.example.lenta.R
import com.example.lenta.data.local.LocalMediaRepository
import com.example.lenta.data.model.MediaItem
import com.example.lenta.data.nextcloud.NextcloudPreferences
import com.example.lenta.data.thumbnail.ThumbnailManager
import com.example.lenta.databinding.FragmentLocalBrowserBinding
import com.example.lenta.ui.viewer.MediaViewerActivity
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class LocalBrowserFragment : Fragment() {

    private var _binding: FragmentLocalBrowserBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: LocalMediaRepository
    private lateinit var preferences: NextcloudPreferences
    private lateinit var adapter: FileListAdapter

    private var currentDirectory: File = Environment.getExternalStorageDirectory() ?: File("/")
    private var lastShowHiddenState: Boolean? = null
    private var pregenerationJob: Job? = null

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigateUp()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            binding.permissionContainer.visibility = View.GONE
            loadDirectory(currentDirectory)
        } else {
            binding.permissionContainer.visibility = View.VISIBLE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocalBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = LocalMediaRepository(requireContext())
        preferences = NextcloudPreferences(requireContext())

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        setupRecyclerView()
        setupStorageChips()
        setupNavigationControls()
        updateHiddenFilesToggleIcon()
        checkPermissionsAndLoad()
    }

    private fun setupRecyclerView() {
        adapter = FileListAdapter { item, position ->
            if (item.isDirectory) {
                loadDirectory(File(item.path))
            } else {
                openMediaViewer(item)
            }
        }

        binding.recyclerFiles.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerFiles.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            loadDirectory(currentDirectory)
        }
    }

    private fun setupStorageChips() {
        val locations = repository.getCommonStorageLocations()
        binding.chipGroupStorage.removeAllViews()

        locations.forEachIndexed { index, loc ->
            val chip = Chip(requireContext()).apply {
                text = loc.name
                isCheckable = true
                isChecked = index == 0
                setOnClickListener {
                    loadDirectory(loc.file)
                }
            }
            binding.chipGroupStorage.addView(chip)
        }
    }

    private fun setupNavigationControls() {
        binding.btnUp.setOnClickListener {
            if (!navigateUp()) {
                findNavController().navigateUp()
            }
        }

        binding.btnToggleHiddenLocal.setOnClickListener {
            preferences.showHiddenFiles = !preferences.showHiddenFiles
            updateHiddenFilesToggleIcon()
            val msg = if (preferences.showHiddenFiles) R.string.showing_hidden_files else R.string.hiding_hidden_files
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            loadDirectory(currentDirectory)
        }

        binding.btnGrantPermission.setOnClickListener {
            requestStoragePermissions()
        }
    }

    fun canNavigateUp(): Boolean {
        val parent = currentDirectory.parentFile
        return parent != null && parent.exists() && parent.canRead()
    }

    fun navigateUp(): Boolean {
        val parent = currentDirectory.parentFile
        if (parent != null && parent.exists() && parent.canRead()) {
            loadDirectory(parent)
            return true
        }
        return false
    }

    private fun updateNavigationUi() {
        val canUp = canNavigateUp()
        binding.btnUp.isEnabled = true
        binding.btnUp.alpha = 1.0f
        backPressedCallback.isEnabled = canUp
    }

    private fun updateHiddenFilesToggleIcon() {
        if (preferences.showHiddenFiles) {
            binding.btnToggleHiddenLocal.setImageResource(R.drawable.ic_visibility)
            binding.btnToggleHiddenLocal.contentDescription = getString(R.string.hide_hidden_files)
            binding.btnToggleHiddenLocal.alpha = 1.0f
        } else {
            binding.btnToggleHiddenLocal.setImageResource(R.drawable.ic_visibility_off)
            binding.btnToggleHiddenLocal.contentDescription = getString(R.string.show_hidden_files)
            binding.btnToggleHiddenLocal.alpha = 0.6f
        }
    }

    override fun onResume() {
        super.onResume()
        updateHiddenFilesToggleIcon()
        updateNavigationUi()
        if (lastShowHiddenState != null && lastShowHiddenState != preferences.showHiddenFiles) {
            loadDirectory(currentDirectory)
        }
    }

    private fun checkPermissionsAndLoad() {
        val hasPermission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (hasPermission) {
            binding.permissionContainer.visibility = View.GONE
            loadDirectory(currentDirectory)
        } else {
            requestStoragePermissions()
        }
    }

    private fun requestStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    fun loadDirectory(directory: File) {
        lastShowHiddenState = preferences.showHiddenFiles
        currentDirectory = directory
        binding.tvCurrentPath.text = directory.absolutePath
        updateNavigationUi()
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val items = repository.listDirectory(directory, preferences.showHiddenFiles)
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            if (items.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                binding.tvEmpty.visibility = View.GONE
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
        }
    }

    private fun openMediaViewer(selectedItem: MediaItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val mediaItems = repository.getMediaItemsInDirectory(currentDirectory, preferences.showHiddenFiles)
            val startIndex = mediaItems.indexOfFirst { it.id == selectedItem.id }.coerceAtLeast(0)

            val intent = MediaViewerActivity.createIntent(
                requireContext(),
                mediaItems,
                startIndex
            )
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pregenerationJob?.cancel()
        pregenerationJob = null
        _binding = null
    }
}
