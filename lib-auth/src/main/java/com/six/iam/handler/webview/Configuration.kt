package com.six.iam.handler.webview

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.six.iam.handler.AuthHandlerType
import org.json.JSONException
import org.json.JSONObject
import java.net.MalformedURLException

class Configuration private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "auth_config_prefs"
        private const val KEY_CONFIG_HASH = "config_hash"

        private var instance: Configuration? = null

        fun getInstance(context: Context): Configuration {
            return instance ?: synchronized(this) {
                instance ?: Configuration(context.applicationContext).also { instance = it }
            }
        }

        @VisibleForTesting
        fun clearInstance() {
            instance = null
        }

        // Helper method to get handler type from BuildConfig

    }

    val handlerType: AuthHandlerType = AuthHandlerType.WEBVIEW

    // OIDC/OAuth configuration
    var clientId: String? = null
        private set
    var clientSecret: String? = null
        private set
    var redirectUri: Uri? = null
        private set
    var endSessionRedirectUri: Uri? = null
        private set
    var scope: String = "openid"
        private set
    var responseType: String = "code"
        private set

    // Endpoint URIs
    var discoveryUri: Uri? = null
        private set
    var authEndpointUri: Uri? = null
        private set
    var tokenEndpointUri: Uri? = null
        private set
    var registrationEndpointUri: Uri? = null
        private set
    var endSessionEndpoint: Uri? = null
        private set
    var userInfoEndpointUri: Uri? = null
        private set

    // Additional parameters
    var loginHint: String = ""
        private set
    var prompt: String = ""
        private set
    var idToken: String = ""
        private set

    // Connection settings
    var httpsRequired: Boolean = true

    // State tracking
    private var configHash: Int = 0
    private val prefs: SharedPreferences

    // Error tracking
    var configurationError: String? = null
        private set

    val isValid: Boolean
        get() = configurationError == null

    init {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadConfiguration(context)
    }

    fun loadConfiguration(context: Context): Boolean {
        try {
            val configJson = readConfigurationFile(context)
            configHash = configJson.hashCode()

            // Read OIDC/OAuth configuration
            clientId = configJson.getString("client_id")
            clientSecret = configJson.optString("client_secret", null)

            redirectUri = parseUri(configJson.getString("redirect_uri"))
            endSessionRedirectUri = parseUri(
                configJson.optString("end_session_redirect_uri", configJson.getString("redirect_uri"))
            )

            scope = configJson.optString("authorization_scope", "openid")
            responseType = configJson.optString("response_type", "code")

            // Read endpoints
            authEndpointUri = parseUri(configJson.getString("authorization_endpoint_uri"))
            tokenEndpointUri = parseUri(configJson.getString("token_endpoint_uri"))

            registrationEndpointUri = configJson.optString("registration_endpoint_uri", null)?.let { parseUri(it) }
            endSessionEndpoint = configJson.optString("end_session_endpoint", null)?.let { parseUri(it) }
            userInfoEndpointUri = configJson.optString("user_info_endpoint_uri", null)?.let { parseUri(it) }

            // Read connection settings
            httpsRequired = configJson.optBoolean("https_required", true)

            // Read optional parameters
            loginHint = configJson.optString("login_hint", "")
            prompt = configJson.optString("prompt", "")

            // Additional OpenID Connect parameters
            discoveryUri = configJson.optString("discovery_uri", null)?.let { parseUri(it) }

            // Validate configuration based on handler type from BuildConfig
            validateConfiguration()

            // Save config hash
            saveConfigHash()

            return isValid

        } catch (e: JSONException) {
            configurationError = "Invalid JSON configuration: ${e.message}"
            return false
        } catch (e: MalformedURLException) {
            configurationError = "Invalid URI in configuration: ${e.message}"
            return false
        } catch (e: IllegalArgumentException) {
            configurationError = "Configuration error: ${e.message}"
            return false
        } catch (e: Exception) {
            configurationError = "Failed to load configuration: ${e.message}"
            return false
        }
    }

    private fun readConfigurationFile(context: Context): JSONObject {
        return try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("auth_config", "raw", context.packageName)
            )
            val content = inputStream.bufferedReader().use { it.readText() }
            JSONObject(content)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to read auth_config.json", e)
        }
    }

    private fun parseUri(uriString: String?): Uri? {
        if (uriString.isNullOrEmpty()) return null
        return Uri.parse(uriString)
    }

    private fun validateConfiguration() {
        val errors = mutableListOf<String>()

        if (clientId.isNullOrEmpty()) {
            errors.add("client_id is required")
        }

        if (redirectUri == null) {
            errors.add("redirect_uri is required")
        }

        if (authEndpointUri == null && discoveryUri == null) {
            errors.add("Either authorization_endpoint_uri or discovery_uri is required")
        }

        // WebView-specific validations
        if (handlerType == AuthHandlerType.WEBVIEW) {
            if (tokenEndpointUri == null && responseType == "code") {
                errors.add("token_endpoint_uri is required for authorization code flow")
            }

            // For WebView, we need to ensure the redirect URI is properly configured for deep linking
            if (redirectUri != null && redirectUri?.scheme?.isNotEmpty() != true) {
                errors.add("redirect_uri must have a scheme (e.g., com.six.iot:) for WebView handler")
            }
        }

        if (errors.isNotEmpty()) {
            configurationError = errors.joinToString(", ")
        } else {
            configurationError = null
        }
    }

    private fun saveConfigHash() {
        prefs.edit()
            .putInt(KEY_CONFIG_HASH, configHash)
            .apply()
    }

    fun hasConfigurationChanged(): Boolean {
        val savedHash = prefs.getInt(KEY_CONFIG_HASH, 0)
        return savedHash != configHash
    }

    fun acceptConfiguration() {
        saveConfigHash()
    }

    // Helper methods
    fun getScopes(): Set<String> {
        return scope.split(" ").filter { it.isNotBlank() }.toSet()
    }

    fun getResponseTypes(): List<String> {
        return responseType.split(" ").filter { it.isNotBlank() }
    }

    fun isAuthorizationCodeFlow(): Boolean {
        return responseType.contains("code")
    }

    fun isImplicitFlow(): Boolean {
        return responseType.contains("token") || responseType.contains("id_token")
    }

    fun getAuthorizationUrl(): String {
        return authEndpointUri?.toString() ?: ""
    }

    fun getTokenUrl(): String {
        return tokenEndpointUri?.toString() ?: ""
    }

    fun getUserInfoUrl(): String {
        return userInfoEndpointUri?.toString() ?: ""
    }

    fun getLogoutUrl(): String {
        return endSessionEndpoint?.toString() ?: ""
    }

    fun getRedirectUrl(): String {
        return redirectUri?.toString() ?: ""
    }

    fun getEndSessionRedirectUrl(): String {
        return endSessionRedirectUri?.toString() ?: getRedirectUrl()
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("handler_type", handlerType.name)  // For reference, not used for configuration
            put("client_id", clientId)
            clientSecret?.let { put("client_secret", it) }
            redirectUri?.let { put("redirect_uri", it.toString()) }
            endSessionRedirectUri?.let { put("end_session_redirect_uri", it.toString()) }
            put("authorization_scope", scope)
            put("response_type", responseType)
            authEndpointUri?.let { put("authorization_endpoint_uri", it.toString()) }
            tokenEndpointUri?.let { put("token_endpoint_uri", it.toString()) }
            registrationEndpointUri?.let { put("registration_endpoint_uri", it.toString()) }
            endSessionEndpoint?.let { put("end_session_endpoint", it.toString()) }
            userInfoEndpointUri?.let { put("user_info_endpoint_uri", it.toString()) }
            discoveryUri?.let { put("discovery_uri", it.toString()) }
            put("https_required", httpsRequired)
            put("login_hint", loginHint)
            put("prompt", prompt)
        }
    }

    override fun toString(): String {
        return "Configuration(handlerType=$handlerType, " +
                "clientId=$clientId, " +
                "redirectUri=$redirectUri, " +
                "authEndpoint=$authEndpointUri, " +
                "tokenEndpoint=$tokenEndpointUri, " +
                "isValid=$isValid)"
    }
}