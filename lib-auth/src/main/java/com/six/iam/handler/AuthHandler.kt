package com.six.iam.handler

import android.content.Intent
import android.os.Bundle

interface AuthHandler {
    fun onStart()
    fun onStop()
    fun onDestroy()
    fun onCreate(savedInstanceState: Bundle?)
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    fun startAuth()
    fun signOut()
    fun endSession()
}