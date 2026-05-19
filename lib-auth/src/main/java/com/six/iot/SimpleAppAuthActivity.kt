package com.six.iot

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.MainThread
import com.six.auth.R

class SimpleAppAuthActivity : AuthHandlerActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_auth)
        startAuth();
    }

    @MainThread
    override fun displayError(error: String?, recoverable: Boolean) {
        runOnUiThread {
            findViewById<View>(R.id.error_container).visibility = View.VISIBLE
            (findViewById<View>(R.id.error_description) as TextView).text =
                error
        }
    }


}