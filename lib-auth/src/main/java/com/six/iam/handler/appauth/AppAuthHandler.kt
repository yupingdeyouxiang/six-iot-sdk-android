package com.six.iam.handler.appauth

import android.annotation.TargetApi
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.ColorRes
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity.RESULT_CANCELED
import androidx.browser.customtabs.CustomTabsIntent
import com.six.auth.R
import com.six.iam.handler.AuthHandler
import com.six.iam.handler.AuthHandlerHook
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthStateManager
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationService.TokenResponseCallback
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ClientAuthentication.UnsupportedAuthenticationMethod
import net.openid.appauth.ClientSecretBasic
import net.openid.appauth.Configuration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.RegistrationRequest
import net.openid.appauth.RegistrationResponse
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import net.openid.appauth.browser.AnyBrowserMatcher
import net.openid.appauth.browser.BrowserMatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AppAuthHandler(hook : AuthHandlerHook) : AuthHandler {

    private val _hook = hook
    private val TAG: String = "AppAuthHandler"
    private val EXTRA_FAILED: String = "failed"
    private val RC_AUTH: Int = 100
    private val END_SESSION_REQUEST_CODE: Int = 911
    private lateinit var mAuthService: AuthorizationService
    private lateinit var mExecutor: ExecutorService
    private lateinit var mAuthStateManager: AuthStateManager
    private lateinit var mConfiguration: Configuration
    private val mClientId = AtomicReference<String>()
    private val mAuthRequest = AtomicReference<AuthorizationRequest>()
    private val mAuthIntent = AtomicReference<CustomTabsIntent>()
    private var mAuthIntentLatch = CountDownLatch(1)
    private var mBrowserMatcher: BrowserMatcher = AnyBrowserMatcher.INSTANCE

    override fun onCreate(savedInstanceState: Bundle?) {
        mConfiguration = Configuration.getInstance(_hook.getActivity())
        if (!mConfiguration.isValid) {
            displayError(mConfiguration.configurationError, false)
            return
        }

        mAuthStateManager = AuthStateManager.getInstance(_hook.getActivity())
        if (mConfiguration.hasConfigurationChanged()) {
            // discard any existing authorization state due to the change of configuration
            Log.i(TAG, "Configuration change detected, discarding old state")
            mAuthStateManager.replace(AuthState())
            mConfiguration.acceptConfiguration()
        }

        //user has canceled the authorization
        if (_hook.getIntent().getBooleanExtra(EXTRA_FAILED, false)) {
            _hook.authCancelled();
            return;
        }
        //displayLoading("Initializing")
        mExecutor = Executors.newSingleThreadExecutor()
        mExecutor.submit {
            this.initializeAppAuth()
        }
    }

    override fun onStart() {
        if (mExecutor.isShutdown) {
            mExecutor = Executors.newSingleThreadExecutor()
        }
    }

    override fun onStop() {
        mExecutor.shutdownNow()
    }

    override fun onDestroy() {
        if (::mAuthService.isInitialized) {
            mAuthService.dispose();
        }
    }

    private fun killTransitions() {
        val activity = _hook.getActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(
                android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0
            )
            activity.overrideActivityTransition(
                android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0
            )
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(R.anim.no_animation, R.anim.no_animation)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        killTransitions()

        when {
            resultCode == RESULT_CANCELED -> _hook.authCancelled()
            requestCode == END_SESSION_REQUEST_CODE -> {
                signOut()
                _hook.sessionEnd()
            }
            else -> {
                val intent = Intent(
                    _hook.getActivity(),
                    _hook.getActivity().javaClass
                )
                intent.putExtras(data?.extras!!)
                // the stored AuthState is incomplete, so check if we are currently receiving the result of
                // the authorization flow from the browser.
                val response = AuthorizationResponse.fromIntent(intent)
                val ex = AuthorizationException.fromIntent(intent)
                // update the StateManager
                if (response != null || ex != null) {
                    mAuthStateManager.updateAfterAuthorization(response, ex)
                }
                if (response?.authorizationCode != null) {
                    // authorization code exchange is required
                    mAuthStateManager.updateAfterAuthorization(response, ex)
                    exchangeAuthorizationCode(response)
                } else if (ex != null) {
                    _hook.authFailed(ex.message!!);
                } else {
                    _hook.authNotFinished("No authorization state retained - reauthorization required");
                }
            }
        }
    }

    @MainThread
    private fun exchangeAuthorizationCode(authorizationResponse: AuthorizationResponse) {
        displayLoading("Exchanging authorization code")
        performTokenRequest(
            authorizationResponse.createTokenExchangeRequest()
        ) { tokenResponse: TokenResponse?, authException: AuthorizationException? ->
            this.handleCodeExchangeResponse(
                tokenResponse,
                authException
            )
        }
    }

    @WorkerThread
    private fun handleCodeExchangeResponse(tokenResponse: TokenResponse?, authException: AuthorizationException?) {
        mAuthStateManager.updateAfterTokenResponse(tokenResponse, authException)

        _hook.getActivity().runOnUiThread {
            // 1. Kill any pending transitions from the browser return
            killTransitions()
            if (mAuthStateManager.getCurrent().isAuthorized) {
                // 2. Explicitly tell the intent system to skip animations for the NEXT step
                _hook.getActivity().intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                // 3. Trigger success
                _hook.authSucceed()
            } else {
                val message = ("Authorization Code exchange failed" + (if (authException != null) authException.error else ""))
                _hook.authNotFinished(message)
            }
        }
    }

    @MainThread
    private fun performTokenRequest(request: TokenRequest, callback: TokenResponseCallback) {
        val clientAuthentication: ClientAuthentication
        try {
            clientAuthentication = mAuthStateManager.getCurrent().getClientAuthentication()
        } catch (ex: UnsupportedAuthenticationMethod) {
            Log.d(TAG, "Token request cannot be made, client authentication for the token "
                    + "endpoint could not be constructed (%s)", ex)
            _hook.authNotFinished("Client authentication method is unsupported")
            return
        }

        mAuthService.performTokenRequest(
            request,
            clientAuthentication,
            callback
        )
    }

    @MainThread
    override fun startAuth() {
        displayLoading("Making authorization request")
        mExecutor.submit { this.doAuth() }
    }

    /**
     * Initializes the authorization service configuration if necessary, either from the local
     * static values or by retrieving an OpenID discovery document.
     */
    @WorkerThread
    private fun initializeAppAuth() {
        Log.i(TAG, "Initializing AppAuth")
        recreateAuthorizationService()

        if (mAuthStateManager.current.authorizationServiceConfiguration != null) {
            // configuration is already created, skip to client initialization
            Log.i(TAG, "auth config already established")
            initializeClient()
            return
        }

        // if we are not using discovery, build the authorization service configuration directly
        // from the static configuration values.
        if (mConfiguration.discoveryUri == null) {
            Log.i(TAG, "Creating auth config from res/raw/auth_config.json")
            val config = AuthorizationServiceConfiguration(
                mConfiguration.authEndpointUri!!,
                mConfiguration.tokenEndpointUri!!,
                mConfiguration.registrationEndpointUri,
                mConfiguration.endSessionEndpoint
            )

            mAuthStateManager.replace(AuthState(config))
            initializeClient()
            return
        }

        // WrongThread inference is incorrect for lambdas
        // noinspection WrongThread
        _hook.getActivity().runOnUiThread { displayLoading("Retrieving discovery document") }
        Log.i(TAG, "Retrieving OpenID discovery doc")
        AuthorizationServiceConfiguration.fetchFromUrl(
            mConfiguration.discoveryUri!!,
            { config: AuthorizationServiceConfiguration?, ex: AuthorizationException? ->
                this.handleConfigurationRetrievalResult(
                    config,
                    ex
                )
            },
            mConfiguration.connectionBuilder
        )
    }

    @MainThread
    private fun handleConfigurationRetrievalResult(config: AuthorizationServiceConfiguration?, ex: AuthorizationException?) {
        if (config == null) {
            Log.i(TAG, "Failed to retrieve discovery document", ex)
            displayError("Failed to retrieve discovery document: " + ex!!.message, true)
            return
        }

        Log.i(TAG, "Discovery document retrieved")
        mAuthStateManager.replace(AuthState(config))
        mExecutor.submit { this.initializeClient() }
    }

    /**
     * Initiates a dynamic registration request if a client ID is not provided by the static
     * configuration.
     */
    @WorkerThread
    private fun initializeClient() {
        if (mConfiguration.clientId != null) {
            Log.i(TAG, "Using static client ID: " + mConfiguration.clientId)
            // use a statically configured client ID
            mClientId.set(mConfiguration.clientId)
            _hook.getActivity().runOnUiThread { this.initializeAuthRequest() }
            return
        }

        val lastResponse =
            mAuthStateManager.current.lastRegistrationResponse
        if (lastResponse != null) {
            Log.i(TAG, "Using dynamic client ID: " + lastResponse.clientId)
            // already dynamically registered a client ID
            mClientId.set(lastResponse.clientId)
            _hook.getActivity().runOnUiThread { this.initializeAuthRequest() }
            return
        }

        // WrongThread inference is incorrect for lambdas
        // noinspection WrongThread
        _hook.getActivity().runOnUiThread { displayLoading("Dynamically registering client") }
        Log.i(TAG, "Dynamically registering client")

        val registrationRequest = RegistrationRequest.Builder(
            mAuthStateManager.current.authorizationServiceConfiguration!!,
            listOf(mConfiguration.redirectUri)
        ).setTokenEndpointAuthenticationMethod(ClientSecretBasic.NAME).build()

        mAuthService.performRegistrationRequest(
            registrationRequest
        ) { response: RegistrationResponse?, ex: AuthorizationException? ->
            this.handleRegistrationResponse(
                response,
                ex
            )
        }
    }

    @MainThread
    private fun handleRegistrationResponse(response: RegistrationResponse?, ex: AuthorizationException?) {
        mAuthStateManager.updateAfterRegistration(response, ex)
        if (response == null) {
            Log.i(TAG, "Failed to dynamically register client", ex)
            displayErrorLater("Failed to register client: " + ex!!.message, true)
            return
        }

        Log.i(TAG, "Dynamically registered client: " + response.clientId)
        mClientId.set(response.clientId)
        initializeAuthRequest()
    }

    /**
     * Performs the authorization request, using the browser selected in the spinner,
     * and a user-provided `login_hint` if available.
     */
    @WorkerThread
    private fun doAuth() {
        try {
            mAuthIntentLatch.await()
        } catch (ex: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for auth intent")
        }
        val intent = mAuthService.getAuthorizationRequestIntent(
            mAuthRequest.get(),
            mAuthIntent.get()
        )
        //intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val options = ActivityOptions.makeCustomAnimation(
            _hook.getActivity(),
            R.anim.no_animation,
            R.anim.no_animation
        ).toBundle()
        _hook.getActivity().startActivityForResult(intent, RC_AUTH, options)
    }

    private fun recreateAuthorizationService() {
        Log.i(TAG, "Discarding existing AuthService instance")
        if (::mAuthService.isInitialized) {
            mAuthService.dispose();
        }
        mAuthService = createAuthorizationService()
        mAuthRequest.set(null)
        mAuthIntent.set(null)
    }

    private fun createAuthorizationService(): AuthorizationService {
        Log.i(TAG, "Creating authorization service")
        val builder = AppAuthConfiguration.Builder()
        builder.setBrowserMatcher(mBrowserMatcher)
        builder.setConnectionBuilder(mConfiguration.connectionBuilder)

        return AuthorizationService(_hook.getActivity(), builder.build())
    }

    @MainThread
    private fun displayLoading(loadingMessage: String) {
        _hook.displayLoading(loadingMessage);
    }

    @MainThread
    private fun displayError(error: String?, recoverable: Boolean) {
        _hook.displayError(error, recoverable);
    }

    // WrongThread inference is incorrect in this case
    @AnyThread
    private fun displayErrorLater(error: String, recoverable: Boolean) {
        _hook.getActivity().runOnUiThread { displayError(error, recoverable) }
    }

    @MainThread
    private fun initializeAuthRequest() {
        createAuthRequest(getLoginHint())
        warmUpBrowser()
    }

    private fun warmUpBrowser() {
        mAuthIntentLatch = CountDownLatch(1)
        mExecutor.execute {
            Log.i(TAG, "Warming up browser instance for auth request")
            val intentBuilder =
                mAuthService.createCustomTabsIntentBuilder(mAuthRequest.get().toUri())
            intentBuilder.setStartAnimations(_hook.getActivity(), R.anim.no_animation, R.anim.no_animation)
            intentBuilder.setExitAnimations(_hook.getActivity(), R.anim.no_animation, R.anim.no_animation)
            //intentBuilder.setToolbarColor(getColorCompat(R.color.colorPrimary));
            mAuthIntent.set(intentBuilder.build())
            mAuthIntentLatch.countDown()
        }
    }

    private fun createAuthRequest(loginHint: String?) {
        Log.i(TAG, "Creating auth request for login hint: $loginHint")
        val authRequestBuilder = AuthorizationRequest.Builder(
            mAuthStateManager.current.authorizationServiceConfiguration!!,
            mClientId.get(),
            ResponseTypeValues.CODE,
            mConfiguration.redirectUri
        ).setScope(mConfiguration.scope)
        if (!TextUtils.isEmpty(loginHint)) {
            authRequestBuilder.setLoginHint(loginHint)
        }
        mAuthRequest.set(authRequestBuilder.build())
    }

    private fun getLoginHint(): String {
        /*return (findViewById<View>(R.id.login_hint_value) as EditText)
            .text
            .toString()
            .trim { it <= ' ' }*/
        return ""
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Suppress("deprecation")
    private fun getColorCompat(@ColorRes color: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            _hook.getActivity().getColor(color)
        } else {
            _hook.getActivity().resources.getColor(color)
        }
    }

    /**
     * Responds to changes in the login hint. After a "debounce" delay, warms up the browser
     * for a request with the new login hint; this avoids constantly re-initializing the
     * browser while the user is typing.
     */
    inner class LoginHintChangeHandler : TextWatcher {
        private val mHandler = Handler(Looper.getMainLooper())
        private var mTask: RecreateAuthRequestTask
        private val DEBOUNCE_DELAY_MS = 500;

        init {
            mTask = RecreateAuthRequestTask()
        }

        override fun beforeTextChanged(cs: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(cs: CharSequence, start: Int, before: Int, count: Int) {
            mTask.cancel()
            mTask = RecreateAuthRequestTask()
            mHandler.postDelayed(mTask, DEBOUNCE_DELAY_MS.toLong())
        }

        override fun afterTextChanged(ed: Editable) {}
    }

    inner class RecreateAuthRequestTask : Runnable {
        private val mCanceled = AtomicBoolean()

        override fun run() {
            if (mCanceled.get()) {
                return
            }
            createAuthRequest(getLoginHint())
            warmUpBrowser()
        }

        fun cancel() {
            mCanceled.set(true)
        }
    }

    @MainThread
    override fun endSession() {
        val currentState: AuthState = mAuthStateManager.getCurrent()
        val config =
            currentState.authorizationServiceConfiguration
        if (config!!.endSessionEndpoint != null) {
            val endSessionIntent = mAuthService.getEndSessionRequestIntent(
                EndSessionRequest.Builder(config!!)
                    .setIdTokenHint(currentState.idToken)
                    .setPostLogoutRedirectUri(mConfiguration.endSessionRedirectUri)
                    .build()
            )
            _hook.getActivity().startActivityForResult(endSessionIntent, END_SESSION_REQUEST_CODE)
        } else {
            signOut()
        }
    }

    @MainThread
    override fun signOut() {
        // discard the authorization and token state, but retain the configuration and
        // dynamic client registration (if applicable), to save from retrieving them again.
        _hook.getActivity().runOnUiThread {
            val currentState: AuthState = mAuthStateManager.getCurrent()
            val clearedState =
                AuthState(currentState.authorizationServiceConfiguration!!)
            if (currentState.lastRegistrationResponse != null) {
                clearedState.update(currentState.lastRegistrationResponse)
            }
            mAuthStateManager.replace(clearedState)
        }
//        val mainIntent = Intent(
//            _hook.getActivity(),
//            _hook.getActivity().javaClass
//        )
//        mainIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
//        _hook.getActivity().startActivity(mainIntent)
    }
}