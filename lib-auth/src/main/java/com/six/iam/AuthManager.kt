package com.six.iam

import android.content.Context
import android.util.Log
import com.six.iam.handler.AuthHandlerFactory
import com.six.iam.handler.AuthHandlerType
import com.six.iam.handler.webview.OAuthHandler
import net.openid.appauth.AuthStateManager
import net.openid.appauth.Configuration
import java.util.Date
import org.json.JSONObject
import android.util.Base64
import java.nio.charset.Charset

/**
 * AuthManager acts as the central gateway for authentication state.
 * It dynamically switches between AppAuth (OIDC Standard) and WebView (Custom)
 * implementations based on the project's BuildConfig.
 */
object AuthManager {

    private val TAG: String = AuthManager::class.java.simpleName

    fun authenticated(context: Context): Boolean {
        val type = AuthHandlerFactory.getHandlerTypeFromBuildConfig()
        val result = when (type) {
            AuthHandlerType.APPAUTH -> checkAppAuthAuthenticated(context)
            AuthHandlerType.WEBVIEW -> checkWebViewAuthenticated(context)
        }
        Log.d(TAG, "Authenticated check ($type): $result")
        return result
    }

    /**
     * Retrieves the current access token regardless of the underlying handler.
     */
    fun authenticatedAccessToken(context: Context): String? {
        return when (AuthHandlerFactory.getHandlerTypeFromBuildConfig()) {
            AuthHandlerType.APPAUTH -> getAppAuthAccessToken(context)
            AuthHandlerType.WEBVIEW -> getWebViewAccessToken(context)
        }
    }

    /**
     * NEW: Retrieves the current ID token regardless of the underlying handler.
     */
    fun authenticatedIdToken(context: Context): String? {
        return when (AuthHandlerFactory.getHandlerTypeFromBuildConfig()) {
            AuthHandlerType.APPAUTH -> getAppAuthIdToken(context)
            AuthHandlerType.WEBVIEW -> getWebViewIdToken(context)
        }
    }

    // --- AppAuth Logic Block ---

    private fun checkAppAuthAuthenticated(context: Context): Boolean {
        val mAuthStateManager = AuthStateManager.getInstance(context)
        val mConfiguration = Configuration.getInstance(context)
        if (mAuthStateManager.current.isAuthorized && !mConfiguration.hasConfigurationChanged()) {
            Log.i(TAG, "User is already authenticated and no configuration is changed, to check token expiration...")
            val tokenExpireTime = mAuthStateManager.getCurrent().accessTokenExpirationTime;
            if (null != tokenExpireTime && Date().before(Date(tokenExpireTime))) {
                val tokenExpireTime = mAuthStateManager.current.accessTokenExpirationTime
                if (tokenExpireTime != null && Date().before(Date(tokenExpireTime))) {
                    Log.i(TAG, "User is already authenticated and token is not expired")
                    return true;
                }
            }
        }
        return false;
    }

    private fun getAppAuthAccessToken(context: Context) =
        AuthStateManager.getInstance(context).current.accessToken

    /**
     * Decodes the JWT payload to check the 'exp' claim.
     * @param token The JWT string
     * @param bufferSeconds Number of seconds before actual expiration to consider it expired (default 60s)
     */
    private fun isJwtExpired(token: String, bufferSeconds: Long = 60): Boolean {
        try {
            val parts = token.split(".")
            if (parts.size < 2) return true // Not a valid JWT

            // JWT format is Header.Payload.Signature. We need the Payload (index 1)
            val payloadBase64 = parts[1]
            val decodedBytes = Base64.decode(payloadBase64, Base64.URL_SAFE)
            val payloadString = String(decodedBytes, Charset.defaultCharset())
            val payloadJson = JSONObject(payloadString)

            if (!payloadJson.has("exp")) return false // No expiration claim, assume valid

            val expirationTimeInSeconds = payloadJson.getLong("exp")
            val currentTimeInSeconds = System.currentTimeMillis() / 1000

            // Check if current time + buffer exceeds expiration time
            return (currentTimeInSeconds + bufferSeconds) >= expirationTimeInSeconds

        } catch (e: Exception) {
            Log.e("TokenUtils", "Error parsing JWT for expiration", e)
            return true // Treat malformed tokens as expired
        }
    }

    private fun getAppAuthIdToken(context: Context) =
        AuthStateManager.getInstance(context).current.idToken

    // --- WebView Logic Block ---
    private fun checkWebViewAuthenticated(context: Context): Boolean {
        val token = getWebViewAccessToken(context)
        return !token.isNullOrEmpty() && !isJwtExpired(token)
    }

    private fun getWebViewAccessToken(context: Context) =
        context.getSharedPreferences(OAuthHandler.PREFS_AUTH, Context.MODE_PRIVATE).getString(OAuthHandler.KEY_ACCESS_TOKEN, null)

    private fun getWebViewIdToken(context: Context) =
        context.getSharedPreferences(OAuthHandler.PREFS_AUTH, Context.MODE_PRIVATE).getString(OAuthHandler.KEY_ID_TOKEN, null)
}