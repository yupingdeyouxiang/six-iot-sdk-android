package com.six.iot.ui.panels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.six.iot.R
import io.flutter.embedding.android.FlutterFragment

class EmbeddedFlutterPanelActivity : AppCompatActivity() {

    private var flutterFragment: FlutterFragment? = null

    companion object {
        private const val TAG = "EmbeddedFlutterPanelActivity"
        const val EXTRA_DEVICE_GUID = "extra_device_guid"
        const val EXTRA_INITIAL_ROUTE = "/device_panel"

        fun newIntent(context: Context, deviceGuid: String): Intent {
            return Intent(context, EmbeddedFlutterPanelActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_GUID, deviceGuid)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_embedded_flutter_panel)

        val deviceGuid = intent.getStringExtra(EXTRA_DEVICE_GUID) ?: ""
        val initialRoute = "$EXTRA_INITIAL_ROUTE/$deviceGuid"

        // Find the existing FlutterFragment if we are recreating the activity
        flutterFragment = supportFragmentManager
            .findFragmentByTag("flutter_fragment") as? FlutterFragment

        // Add the fragment if it does not already exist.
        if (flutterFragment == null) {
            flutterFragment = FlutterFragment.withNewEngine()
                .initialRoute(initialRoute)
                .build()

            supportFragmentManager
                .beginTransaction()
                .add(
                    R.id.flutter_fragment_container,
                    flutterFragment!!,
                    "flutter_fragment"
                )
                .commit()
        }
    }
}
