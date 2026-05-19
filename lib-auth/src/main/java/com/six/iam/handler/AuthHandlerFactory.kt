package com.six.iam.handler

import com.six.auth.BuildConfig
import com.six.iam.handler.appauth.AppAuthHandler
import com.six.iam.handler.webview.OAuthHandler

object AuthHandlerFactory {

    fun createHandler(hook: AuthHandlerHook): AuthHandler {
        return when (getHandlerTypeFromBuildConfig()) {
            AuthHandlerType.APPAUTH -> AppAuthHandler(hook)
            AuthHandlerType.WEBVIEW -> OAuthHandler(hook)
        }
    }

    fun getHandlerTypeFromBuildConfig(): AuthHandlerType {
        return try {
            val handlerTypeStr = try {
                BuildConfig.AUTH_HANDLER_TYPE
            } catch (e: Exception) {
                "APPAUTH"
            }
            AuthHandlerType.valueOf(handlerTypeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            AuthHandlerType.APPAUTH
        }
    }
}