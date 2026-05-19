package com.six.iam.handler.webview

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.six.auth.BuildConfig
import com.six.auth.R
import com.six.iam.handler.AuthHandler
import com.six.iam.handler.AuthHandlerHook
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class OAuthHandler(private val hook: AuthHandlerHook) : AuthHandler {

    companion object {
        private const val TAG = "OAuthHandler"
        private const val AUTH_REQUEST_CODE = 1001
        private const val KEY_CODE_VERIFIER = "pkce_code_verifier"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_REDIRECT_URI = "extra_redirect_uri"
        const val PREFS_AUTH = "auth_prefs"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ID_TOKEN = "id_token"
        const val RESULT_CODE = "result_code"
        const val RESULT_STATE = "result_state"
        const val RESULT_ERROR = "result_error"
    }

    private val _hook = hook
    private lateinit var configuration: Configuration
    private var currentState: String? = null
    private var isRequestingAuth = false

    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val codeVerifier = ByteArray(32)
        secureRandom.nextBytes(codeVerifier)
        return Base64.encodeToString(
            codeVerifier,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val digest = messageDigest.digest(bytes)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        configuration = Configuration.getInstance(_hook.getActivity())
        if (!configuration.isValid) {
            _hook.displayError(configuration.configurationError ?: "Invalid Configuration", false)
            return
        }
        /*if (isAuthenticated()) {
            _hook.authSucceed()
        } else {
            startAuth()
        }*/
    }

    private fun isAuthenticated(): Boolean {
        val prefs = _hook.getActivity().getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrEmpty()
    }

    override fun startAuth() {
        if (isRequestingAuth) {
            Log.d(TAG, "Authorization request already in progress, ignoring duplicate call.")
            return
        }

        try {
            isRequestingAuth = true
            val verifier = generateCodeVerifier()
            val challenge = generateCodeChallenge(verifier)

            val prefs = _hook.getActivity().getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CODE_VERIFIER, verifier).apply()

            val authUrl = buildAuthorizationUrl(challenge)

            val intent = Intent().apply {
                setClassName(
                    BuildConfig.WEBVIEW_AUTH_ACTIVITY_PKG,
                    BuildConfig.WEBVIEW_AUTH_ACTIVITY_CLASS
                )
                putExtra(EXTRA_URL, authUrl)
                putExtra(EXTRA_REDIRECT_URI, configuration.getRedirectUrl())
            }

            val options = ActivityOptions.makeCustomAnimation(
                _hook.getActivity(),
                R.anim.no_animation,
                R.anim.no_animation
            ).toBundle()

            Log.i(TAG, "Opening WebViewAuthActivity...")
            _hook.getActivity().startActivityForResult(intent, AUTH_REQUEST_CODE, options)
        } catch (e: Exception) {
            isRequestingAuth = false
            _hook.displayError("Auth Initiation Failed: ${e.message}", false)
        }
    }

    private fun killTransitions() {
        val activity = _hook.getActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN, 0, 0
            )
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(R.anim.no_animation, R.anim.no_animation)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == AUTH_REQUEST_CODE) {
            killTransitions()
            when (resultCode) {
                Activity.RESULT_OK -> {
                    val code = data?.getStringExtra(RESULT_CODE)
                    val returnedState = data?.getStringExtra(RESULT_STATE)
                    if (currentState != null && currentState != returnedState) {
                        _hook.authFailed("CSRF mismatch")
                        isRequestingAuth = false
                        return
                    }
                    code?.let { exchangeAuthorizationCode(it) }
                }
                Activity.RESULT_CANCELED -> {
                    val error = data?.getStringExtra(RESULT_ERROR) ?: "Cancelled"
                    _hook.authFailed(error)
                    isRequestingAuth = false
                }
            }
        }
    }

    private fun exchangeAuthorizationCode(code: String) {
        val prefs = _hook.getActivity().getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
        val codeVerifier = prefs.getString(KEY_CODE_VERIFIER, null)
        if (codeVerifier == null) {
            _hook.authFailed("Code verifier missing")
            isRequestingAuth = false
            return
        }
        _hook.displayLoading("Completing Login...")

        Thread {
            try {
                val connection =
                    URL(configuration.getTokenUrl()).openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    doOutput = true
                }

                val params = mutableMapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to configuration.getRedirectUrl(),
                    "client_id" to (configuration.clientId ?: ""),
                    "code_verifier" to codeVerifier
                )

                configuration.clientSecret?.let { params["client_secret"] = it }

                val postData = params.map {
                    "${
                        URLEncoder.encode(
                            it.key,
                            "UTF-8"
                        )
                    }=${URLEncoder.encode(it.value, "UTF-8")}"
                }.joinToString("&")
                connection.outputStream.use { it.write(postData.toByteArray()) }

                val responseCode = connection.responseCode
                val responseBody = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Error"
                }

                prefs.edit().remove(KEY_CODE_VERIFIER).apply()
                _hook.getActivity().runOnUiThread {
                    if (responseCode in 200..299 && handleTokenResponse(responseBody)) {
                        _hook.authSucceed()
                    } else {
                        _hook.authFailed("Token exchange failed")
                    }
                    isRequestingAuth = false
                }
            } catch (e: Exception) {
                _hook.getActivity().runOnUiThread {
                    isRequestingAuth = false
                    _hook.authFailed("Network error during exchange")
                }
            }
        }.start()
    }

    private fun handleTokenResponse(jsonBody: String): Boolean {
        return try {
            val json = JSONObject(jsonBody)
            saveTokens(
                json.optString("access_token"),
                json.optString("refresh_token"),
                json.optString("id_token")
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveTokens(access: String?, refresh: String?, id: String?) {
        _hook.getActivity().getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE).edit().apply {
            if (!access.isNullOrEmpty()) putString(KEY_ACCESS_TOKEN, access)
            if (!refresh.isNullOrEmpty()) putString(KEY_REFRESH_TOKEN, refresh)
            if (!id.isNullOrEmpty()) putString(KEY_ID_TOKEN, id)
            apply()
        }
    }

    private fun buildAuthorizationUrl(codeChallenge: String): String {
        currentState = UUID.randomUUID().toString().replace("-", "")
        return Uri.parse(configuration.getAuthorizationUrl()).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", configuration.clientId)
            .appendQueryParameter("redirect_uri", configuration.getRedirectUrl())
            .appendQueryParameter("scope", configuration.scope)
            .appendQueryParameter("state", currentState)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build().toString()
    }

    override fun signOut() {
        _hook.getActivity().getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE).edit().clear()
            .apply()
        CookieManager.getInstance().removeAllCookies(null)
        _hook.sessionEnd()
    }

    override fun endSession() = signOut()
    override fun onStart() {}
    override fun onStop() {}
    override fun onDestroy() {}
}
