package com.six.iot.ui.auth.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.six.iam.handler.webview.OAuthHandler
import com.six.iot.R
import com.six.iot.databinding.ActivityWebViewAuthBinding
import com.six.iot.wxapi.WeChatWebBridge

class WebViewAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebViewAuthBinding
    private var redirectUri: String = ""

    companion object {
        @SuppressLint("StaticFieldLeak") // Safe because we clear it in onDestroy
        var currentWebView: WebView? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize View Binding using your ConstraintLayout layout
        binding = ActivityWebViewAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(OAuthHandler.EXTRA_URL)
        redirectUri = intent.getStringExtra(OAuthHandler.EXTRA_REDIRECT_URI) ?: ""

        if (url.isNullOrEmpty() || redirectUri.isEmpty()) {
            finishWithError("Missing initialization parameters")
            return
        }

        setToolbar()
        setupWebView(url)
    }

    private fun setToolbar() {
        val toolbar = binding.titleBar.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
        toolbar.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back_ios_24dp)
        binding.titleBar.toolbarTitle.text = getString(R.string.title_activity_login)
        toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun setupWebView(url: String) {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
            }
            addJavascriptInterface(WeChatWebBridge(this@WebViewAuthActivity), "AndroidWeChatBridge")
            webViewClient = AuthWebViewClient()
            loadUrl(url)
        }
        currentWebView = binding.webView
    }

    override fun onDestroy() {
        if (currentWebView == binding.webView) {
            currentWebView = null
        }
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun finishSuccess(code: String, state: String?) {
        val result = Intent().apply {
            putExtra(OAuthHandler.RESULT_CODE, code)
            state?.let { putExtra(OAuthHandler.RESULT_STATE, it) }
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun finishWithError(error: String) {
        val result = Intent().apply {
            putExtra(OAuthHandler.RESULT_ERROR, error)
        }
        setResult(RESULT_CANCELED, result)
        finish()
    }

    private inner class AuthWebViewClient : WebViewClient() {

        // Handle showing a loading indicator if you add a ProgressBar to your XML
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            binding.progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            binding.progressBar.visibility = View.GONE
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            return handleUrl(url)
        }

        @Deprecated("Deprecated in API 24")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            return handleUrl(url ?: "")
        }

        private fun handleUrl(url: String): Boolean {
            if (url.startsWith(redirectUri)) {
                processOAuthRedirect(url)
                return true
            }
            return false
        }

        private fun processOAuthRedirect(url: String) {
            try {
                val uri = Uri.parse(url)
                val error = uri.getQueryParameter("error")
                if (error != null) {
                    finishWithError("Server Error: $error")
                    return
                }
                val code = uri.getQueryParameter("code")
                val state = uri.getQueryParameter("state")
                if (code != null) {
                    finishSuccess(code, state)
                } else {
                    finishWithError("Authorization code not found in redirect")
                }
            } catch (e: Exception) {
                finishWithError("Parse error: ${e.localizedMessage}")
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                finishWithError("Network Error: ${error?.description}")
            }
        }
    }
}