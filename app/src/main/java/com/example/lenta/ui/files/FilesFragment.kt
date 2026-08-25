package com.example.lenta.ui.files

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.lenta.R
import com.example.lenta.data.nextcloud.NextcloudPreferences
import com.example.lenta.databinding.FragmentFilesBinding
import com.google.android.material.snackbar.Snackbar

class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferences: NextcloudPreferences

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
        preferences = NextcloudPreferences(requireContext())

        setupSourceCards()
    }

    private fun setupSourceCards() {
        binding.cardSourceLocal.setOnClickListener {
            findNavController().navigate(R.id.nav_local)
        }

        binding.cardSourceNextcloud.setOnClickListener {
            findNavController().navigate(R.id.nav_nextcloud)
        }

        binding.cardAddSource.setOnClickListener {
            Snackbar.make(binding.root, R.string.add_source_coming_soon, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateNextcloudStatus()
    }

    private fun updateNextcloudStatus() {
        if (preferences.isConfigured()) {
            val server = preferences.serverUrl
            binding.tvNextcloudSubtitle.text = getString(R.string.source_nextcloud_subtitle_configured, server)
        } else {
            binding.tvNextcloudSubtitle.text = getString(R.string.source_nextcloud_subtitle_unconfigured)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
