package me.lesovoy.lenta.ui.sources

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import me.lesovoy.lenta.R
import me.lesovoy.lenta.data.source.StorageSourceType
import me.lesovoy.lenta.data.source.oauth.OAuthConfig
import me.lesovoy.lenta.data.source.oauth.OAuthHelper
import me.lesovoy.lenta.databinding.DialogOauthWebLoginBinding
import kotlinx.coroutines.launch

class OAuthWebLoginDialog(
    private val sourceType: StorageSourceType = StorageSourceType.GOOGLE_DRIVE,
    private val onAuthSuccess: ((token: String, accountName: String) -> Unit)? = null
) : DialogFragment() {

    private var _binding: DialogOauthWebLoginBinding? = null
    private val binding get() = _binding!!

    private var isAuthCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Lenta_FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogOauthWebLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = when (sourceType) {
            StorageSourceType.GOOGLE_DRIVE -> "Google Drive Sign In"
            StorageSourceType.ONEDRIVE -> "Microsoft OneDrive Sign In"
            else -> "Web Sign In"
        }
        val subtitle = when (sourceType) {
            StorageSourceType.GOOGLE_DRIVE -> "Log in with your Google Account"
            StorageSourceType.ONEDRIVE -> "Log in with your Microsoft Account"
            else -> "Web Authentication"
        }

        binding.tvWebLoginTitle.text = title
        binding.tvWebLoginSubtitle.text = subtitle

        binding.btnCloseWebLogin.setOnClickListener {
            dismiss()
        }

        binding.btnReloadWebLogin.setOnClickListener {
            loadAuthUrl()
        }

        setupWebView()
        loadAuthUrl()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webViewOauth
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Set developer configured user agent to prevent Google/OAuth embedded webview blocks
        settings.userAgentString = OAuthConfig.webLogin.userAgent

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressWebLogin.visibility = View.VISIBLE
                if (url != null && handlePotentialRedirect(url)) {
                    view?.stopLoading()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (handlePotentialRedirect(url)) {
                    view?.stopLoading()
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressWebLogin.visibility = View.GONE
                if (url != null) {
                    handlePotentialRedirect(url)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val failingUrl = request?.url?.toString().orEmpty()
                if (!OAuthHelper.isRedirectUrl(sourceType, failingUrl)) {
                    binding.progressWebLogin.visibility = View.GONE
                }
            }
        }
    }

    private fun loadAuthUrl() {
        isAuthCompleted = false
        binding.progressWebLogin.visibility = View.VISIBLE
        binding.llWebLoadingState.visibility = View.GONE
        binding.webViewOauth.visibility = View.VISIBLE

        val authUrl = OAuthHelper.buildAuthUrl(sourceType)
        binding.webViewOauth.loadUrl(authUrl)
    }

    private fun handlePotentialRedirect(url: String): Boolean {
        if (isAuthCompleted) return true

        if (OAuthHelper.isRedirectUrl(sourceType, url)) {
            val result = OAuthHelper.parseOAuthResult(url)
            if (result != null) {
                if (result.accessToken.isNotBlank()) {
                    isAuthCompleted = true
                    onTokenReceived(result.accessToken)
                    return true
                } else if (result.error != null) {
                    isAuthCompleted = true
                    Toast.makeText(requireContext(), "Authentication error: ${result.error}", Toast.LENGTH_LONG).show()
                    dismiss()
                    return true
                }
            }
        }
        return false
    }

    private fun onTokenReceived(token: String) {
        binding.webViewOauth.visibility = View.GONE
        binding.llWebLoadingState.visibility = View.VISIBLE
        binding.tvLoadingMessage.text = "Fetching account profile…"

        lifecycleScope.launch {
            val profileResult = OAuthHelper.fetchUserProfile(sourceType, token)
            val accountName = profileResult.getOrNull() ?: when (sourceType) {
                StorageSourceType.GOOGLE_DRIVE -> "Google Drive Account"
                StorageSourceType.ONEDRIVE -> "OneDrive Account"
                else -> "Cloud Account"
            }

            onAuthSuccess?.invoke(token, accountName)
            Toast.makeText(requireContext(), "Signed in as $accountName", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onDestroyView() {
        binding.webViewOauth.stopLoading()
        binding.webViewOauth.clearHistory()
        binding.webViewOauth.removeAllViews()
        binding.webViewOauth.destroy()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OAuthWebLoginDialog"

        fun show(
            fragmentManager: FragmentManager,
            sourceType: StorageSourceType,
            onAuthSuccess: (token: String, accountName: String) -> Unit
        ) {
            OAuthWebLoginDialog(sourceType, onAuthSuccess).show(fragmentManager, TAG)
        }
    }
}
