package com.six.iot

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.six.iot.databinding.ActivityMainBinding
import com.six.iot.events.AuthnFailEvent
import com.six.iot.ui.ai.AiChatActivity
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : AuthHandlerActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            view.updatePadding(
                left = systemBars.left,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }

        val toolbar: Toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val navView: BottomNavigationView = binding.navView
        navController = findNavController(R.id.nav_host_fragment_activity_main)
        navView.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.toolbarTitle.text = destination.label
        }

        // --- NEW: AI Chat Button Implementation ---
        binding.aiChatBtn.setOnClickListener {
            val intent = Intent(this, AiChatActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStop() {
        super.onStop()
        // Unregistering here is fine, but we keep the service running
        // because it was started via startService()
        EventBus.getDefault().unregister(this)
    }

    override fun onDestroy() {
        super.onDestroy()
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onAuthnFailEvent(event: AuthnFailEvent) {
        Log.w(TAG, "Authentication failed: " + event.message)
        //startAuth();
    }

    override fun authSucceed() {
        super.authSucceed()
        runOnUiThread {
            val navHostFragment =
                supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as? NavHostFragment
            val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment
            currentFragment?.onResume()
        }
    }

    override fun sessionEnd() {
        super.sessionEnd()
    }
}
