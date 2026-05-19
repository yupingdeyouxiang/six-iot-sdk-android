package com.six.iot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import com.six.auth.R
import com.six.iam.handler.AuthHandler
import com.six.iam.handler.AuthHandlerFactory
import com.six.iam.handler.AuthHandlerHook

abstract class AuthHandlerActivity : AppCompatActivity(), AuthHandlerHook {

    private lateinit var authHandler: AuthHandler;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authHandler = AuthHandlerFactory.createHandler(this)
        authHandler.onCreate(savedInstanceState);
    }

    open fun startAuth() {
        authHandler.startAuth()
    }

    override fun onStart() {
        super.onStart()
        authHandler.onStart();
    }

    override fun onStop() {
        super.onStop()
        authHandler.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        authHandler.onDestroy();
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        authHandler.onActivityResult(requestCode, resultCode, data);
    }

    override fun getActivity(): Activity {
        return this;
    }

    override fun authCancelled() {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.auth_msg_cancelled), Toast.LENGTH_SHORT).show()
        }
    }

    override fun authFailed(ex: String) {
        runOnUiThread {
            // Using string formatting to inject the error message into the localized string
            val message = getString(R.string.auth_msg_failed, ex)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun authNotFinished(msg: String?) {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.auth_msg_not_finished), Toast.LENGTH_SHORT).show()
        }
    }

    override fun authSucceed() {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.auth_msg_success), Toast.LENGTH_SHORT).show()
        }
    }

    override fun userOpenIdGetSucceed(openId: String) {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.auth_msg_openid_success), Toast.LENGTH_SHORT).show()
        }
    }

    override fun userOpenIdGetFail() {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.auth_msg_openid_fail), Toast.LENGTH_SHORT).show()
        }
    }

    override fun sessionEnd() {
        runOnUiThread {
            Toast.makeText(this, getString(R.string.auth_msg_signed_out), Toast.LENGTH_SHORT).show()
        }
    }

    @MainThread
    override fun displayError(error: String?, recoverable: Boolean) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        }
    }

    override fun displayLoading(loadingMessage: String) {
    }


    open fun signOut() {
        authHandler.endSession()
    }
}
