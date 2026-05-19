package com.six.iam.handler

import android.app.Activity
import android.content.Intent

interface AuthHandlerHook {
    fun getActivity(): Activity
    fun getIntent(): Intent
    fun authCancelled()
    //fun authFailed(ex: AuthorizationException)
    fun authFailed(ex: String)
    fun authNotFinished(msg:String?)
    fun authSucceed()
    fun sessionEnd()
    fun userOpenIdGetSucceed(openId: String)
    fun userOpenIdGetFail()
    fun displayError(error: String?, recoverable: Boolean)
    fun displayLoading(loadingMessage: String)
}