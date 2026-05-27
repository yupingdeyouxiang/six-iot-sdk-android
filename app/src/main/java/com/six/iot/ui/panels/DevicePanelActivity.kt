package com.six.iot.ui.panels

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.six.iam.AuthManager
import com.six.iot.BuildConfig
import com.six.iot.Device
import com.six.iot.R
import com.six.iot.databinding.ActivityDevicePanelBinding
import com.six.iot.events.MqttConnectedEvent
import com.six.iot.events.ShadowGetAcceptedEvent
import com.six.iot.events.ShadowUpdateAcceptedEvent
import com.six.iot.network.UnbindDeviceUtil
import com.six.iot.network.UnbindHandlerHook
import com.six.iot.services.MqttClientService
import io.flutter.embedding.android.FlutterFragment
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.get

class DevicePanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevicePanelBinding
    private lateinit var channel: MethodChannel
    private var deviceGuid: String? = null
    private var deviceName: String? = null
    private var productId: String? = null
    private var deviceStatus: String? = null

    private var mqttUrl: String? = null
    private var deviceOn: Int? = 0
    private var flutterEngineId: String? = null

    // Properties for binding to the MqttClientService
    private var mqttService: MqttClientService? = null
    private var isMqttServiceBound = false

    private val publishQueue = ConcurrentLinkedQueue<Pair<String, String>>()
    private val subscribeQueue = ConcurrentLinkedQueue<String>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MqttClientService.MqttBinder
            mqttService = binder.getService()
            isMqttServiceBound = true
            Log.d(TAG, "MqttClientService connected")

            if (productId != null && deviceGuid != null) {
                val topics = listOf(
                    "$productId/$deviceGuid/shadow/update/accepted",
                    "$productId/$deviceGuid/shadow/get/accepted"
                )
                topics.forEach { topic-> subscribe(topic) }
                publish( "$productId/$deviceGuid/shadow/get", "{}")
            }
            processSubscribeQueue()
            processPublishQueue()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
            isMqttServiceBound = false
            Log.d(TAG, "MqttClientService disconnected")
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMqttConnectedEvent(event: MqttConnectedEvent) {
        if (event.url == mqttUrl) {
            if (productId != null && deviceGuid != null) {
                val topics = listOf(
                    "$productId/$deviceGuid/shadow/update/accepted",
                    "$productId/$deviceGuid/shadow/get/accepted"
                )
                topics.forEach { topic -> subscribe(topic) }
                publish( "$productId/$deviceGuid/shadow/get", "{}")
            }
            processSubscribeQueue()
            processPublishQueue()
        }
    }

    companion object {
        private const val TAG = "DevicePanelActivity"
        private const val FLUTTER_CHANNEL = "com.six.iot/device_control"
        const val EXTRA_DEVICE_GUID = "EXTRA_DEVICE_GUID"
        const val EXTRA_DEVICE_NAME = "EXTRA_DEVICE_NAME"
        const val EXTRA_DEVICE_PRODUCT_ID = "EXTRA_DEVICE_PRODUCT_ID"
        const val EXTRA_DEVICE_STATUS = "EXTRA_DEVICE_STATUS"

        const val EXTRA_MQTT_URL = "EXTRA_MQTT_URL"
        const val EXTRA_DEVICE_ON = "EXTRA_DEVICE_ON"

        @JvmStatic
        fun newIntent(context: Context, device: Device): Intent {
            return Intent(context, DevicePanelActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_NAME, device.name)
                putExtra(EXTRA_DEVICE_GUID, device.guid)
                putExtra(EXTRA_DEVICE_PRODUCT_ID, device.productId)
                putExtra(EXTRA_DEVICE_STATUS, device.status)
                putExtra(EXTRA_MQTT_URL, getMqttUrl(device))
                val state = device.shadow["state"] as? Map<*, *>
                val reported = state?.get("reported") as? Map<*, *>
                val lightStatus = reported?.get("light") as? String
                // Convert "on"/"off" string to Boolean or pass as String depending on your needs
                val isOn = lightStatus == "on"
                putExtra(EXTRA_DEVICE_ON, isOn)
            }
        }

        fun getMqttUrl(device: Device): String {
            val federateIot = device.product["federateIot"] as? Map<*, *>
            val platform = federateIot?.get("platform") as? String
            // Determine URL and Auth Config based on platform
            when (platform) {
                "AwsIotCore" -> {
                    val target = federateIot["target"] as? Map<*, *>
                    val endpoint = target?.get("iotEndpoint") as? String ?: ""
                    val url = "wss://$endpoint/mqtt"

                    return url
                }

                else -> {
                    // Default or null
                    val url = BuildConfig.MQTT_BROKER_URL
                    return url;
                }
            }
        }

        @JvmStatic
        fun renderDeviceShadowPanel(shadowPanelContainer: FrameLayout, device: Device) {
            val inflater = LayoutInflater.from(shadowPanelContainer.context)
            val lightView =
                inflater.inflate(R.layout.layout_light_details, shadowPanelContainer, true)
            val statusIcon = lightView.findViewById<ImageView>(R.id.light_status_icon)
            val reportedState = (device.shadow["state"] as? Map<String, Any>)
                ?.get("reported") as? Map<String, Any>
            val lightStatus = reportedState?.get("light") as? String
            statusIcon.setImageDrawable(
                if (lightStatus == "on") shadowPanelContainer.context.getDrawable(R.drawable.ic_power_on)
                else shadowPanelContainer.context.getDrawable(R.drawable.ic_power_off)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevicePanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)
        deviceGuid = intent.getStringExtra(EXTRA_DEVICE_GUID)
        deviceStatus = intent.getStringExtra(EXTRA_DEVICE_STATUS)
        productId = intent.getStringExtra(EXTRA_DEVICE_PRODUCT_ID)
        deviceOn = intent.getIntExtra(EXTRA_DEVICE_ON, 0)
        mqttUrl = intent.getStringExtra(EXTRA_MQTT_URL)

        setToolbar()
        // Post the heavy Flutter engine initialization to run after the first layout pass.
        // This makes the activity and its loading bar appear much faster.
        binding.root.post { initializeFlutter() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.device_panel_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_unbind_device -> {
                showUnbindConfirmationDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showUnbindConfirmationDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.unbind_device_confirm_title))
        builder.setMessage(getString(R.string.unbind_device_confirm_message))
        builder.setPositiveButton(android.R.string.ok) { dialog, _ ->
            dialog.dismiss()
            doUnbindDevice()
        }
        builder.setNegativeButton(android.R.string.cancel) { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }

    private fun doUnbindDevice() {
        val token = AuthManager.authenticatedAccessToken(this)
        if (token == null) {
            Toast.makeText(
                this,
                getString(R.string.authentication_token_not_found),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (deviceGuid == null) {
            Toast.makeText(this, getString(R.string.device_guid_not_found), Toast.LENGTH_SHORT)
                .show()
            return
        }

        UnbindDeviceUtil().unbindDevice(token, deviceGuid!!, object : UnbindHandlerHook {
            override fun onUnbindSuccess() {
                runOnUiThread {
                    Toast.makeText(
                        this@DevicePanelActivity,
                        getString(R.string.unbind_device_success),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }

            override fun onUnbindFailure() {
                runOnUiThread {
                    Toast.makeText(
                        this@DevicePanelActivity,
                        getString(R.string.unbind_device_failure),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    private fun initializeFlutter() {
        flutterEngineId = deviceGuid
        if (flutterEngineId == null) {
            Log.e(TAG, "Device GUID is missing, closing activity.")
            finish()
            return
        }

        val flutterEngine =
            FlutterEngineCache.getInstance().get(flutterEngineId!!) ?: createAndCacheFlutterEngine()

        // Configure the channel AND set the handler immediately.
        configureMethodChannel(flutterEngine)
        setupMethodCallHandler()

        if (supportFragmentManager.findFragmentByTag("flutter_fragment") == null) {
            val flutterFragment =
                FlutterFragment.withCachedEngine(flutterEngineId!!).build<FlutterFragment>()
            supportFragmentManager.beginTransaction()
                .add(R.id.flutter_fragment_container, flutterFragment, "flutter_fragment").commit()
        }
    }

    private fun createAndCacheFlutterEngine(): FlutterEngine {
        Log.d(TAG, "FlutterEngine not found in cache. Creating a new one.")
        val flutterEngine = FlutterEngine(this)
        flutterEngine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
        FlutterEngineCache.getInstance().put(flutterEngineId!!, flutterEngine)
        return flutterEngine
    }

    private fun setToolbar() {
        setSupportActionBar(binding.titleBar.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
        binding.titleBar.toolbar.navigationIcon =
            AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back_ios_24dp)
        binding.titleBar.toolbar.setNavigationOnClickListener { onBackPressed() }
        binding.titleBar.toolbarTitle.text = deviceName
    }

    private fun configureMethodChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, FLUTTER_CHANNEL)
        val languageCode = Locale.getDefault().language
        val initialData = mapOf(
            "name" to deviceName,
            "guid" to deviceGuid,
            "status" to deviceStatus,
            "on" to deviceOn,
            "language" to languageCode
        )
        channel.invokeMethod("setInitialData", initialData)
    }

    private fun setupMethodCallHandler() {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "flutterUiReady" -> {
                    binding.loadingProgressBar.visibility = View.GONE
                    binding.flutterFragmentContainer.visibility = View.VISIBLE
                    result.success(null)
                }

                "toggleLight" -> {
                    val args = call.arguments as? Map<String, Any>
                    val newState = args?.get("newState") as? Boolean
                    if (newState != null && deviceGuid != null && productId != null) {
                        val payload =
                            if (newState) "{\"state\":{\"desired\" : {\"light\":\"on\"}}}" else "{\"state\":{\"desired\" : {\"light\":\"off\"}}}"
                        val topic = "$productId/$deviceGuid/shadow/update"
                        publish(topic, payload)
                        result.success(null)
                    } else {
                        result.error("INVALID_ARGS", "Missing arguments for toggleLight", null)
                    }
                }

                "onBackPressed" -> {
                    finish()
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun publish(topic: String, payload: String) {
        if (isMqttServiceBound && mqttService != null) {
            mqttService!!.publish(mqttUrl!!, topic, payload)
        } else {
            Log.w(TAG, "Service not bound, queuing publish for topic: $topic")
            publishQueue.add(Pair(topic, payload))
        }
    }

    private fun subscribe(topic: String) {
        if (isMqttServiceBound && mqttService != null) {
            mqttService!!.subscribe(mqttUrl!!, topic)
        } else {
            Log.w(TAG, "Service not bound, queuing subscribe for topic: $topic")
            subscribeQueue.add(topic)
        }
    }

    private fun processPublishQueue() {
        synchronized(publishQueue) {
            Log.d(TAG, "Processing ${publishQueue.size} queued publish requests.")
            while (publishQueue.isNotEmpty()) {
                publishQueue.poll()?.let { (topic, payload) ->
                    publish(topic, payload) // Re-call publish to ensure service is now bound
                }
            }
        }
    }

    private fun processSubscribeQueue() {
        synchronized(subscribeQueue) {
            Log.d(TAG, "Processing ${publishQueue.size} queued subscribe requests.")
            while (subscribeQueue.isNotEmpty()) {
                subscribeQueue.poll()?.let { topic ->
                    subscribe(topic) // Re-call subcribe to ensure service is now bound
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
        Intent(this, MqttClientService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-attach the handler every time the activity is resumed to handle backgrounding.
        if (::channel.isInitialized) {
            setupMethodCallHandler()
        }
    }

    override fun onPause() {
        super.onPause()
        // Detach the handler when the activity is paused to prevent leaks.
        if (::channel.isInitialized) {
            channel.setMethodCallHandler(null)
        }
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
        if (isMqttServiceBound) {
            unbindService(serviceConnection)
            isMqttServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            flutterEngineId?.let {
                val flutterEngine = FlutterEngineCache.getInstance().get(it)
                flutterEngine?.destroy()
                FlutterEngineCache.getInstance().remove(it)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onShadowGetAcceptedEvent(event: ShadowGetAcceptedEvent) {
        if (event.deviceGuid == deviceGuid) {
            try {
                val json = event.json

                val stateObject = json?.optJSONObject("state")
                val reportedObject = stateObject?.optJSONObject("reported")

                val status = reportedObject?.optString("status") ?: "Unknown"
                if (status.equals("Offline", ignoreCase = true)) {
                    Toast.makeText(this@DevicePanelActivity, "Device is offline", Toast.LENGTH_SHORT).show()
                }

                val onValue = if (reportedObject?.optString("light") == "on") 1 else 0
                val isOn = onValue == 1
                channel.invokeMethod("updateLightState", mapOf("isOn" to isOn))

            } catch (e: Exception) {
                Log.e(TAG, "Error parsing shadow data in onShadowRead", e)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onShadowUpdateAcceptedEvent(event: ShadowUpdateAcceptedEvent) {
        if (event.deviceGuid == deviceGuid) {
            try {
                val json = event.json
                val stateObject = json?.optJSONObject("state")
                val reportedObject = stateObject?.optJSONObject("reported")

                val status = reportedObject?.optString("status") ?: "Unknown"
                if (status.equals("Offline", ignoreCase = true)) {
                    Toast.makeText(this@DevicePanelActivity, "Device is offline", Toast.LENGTH_SHORT).show()
                }

                // 2. Existing logic for light state
                val onValue = if (reportedObject?.optString("light") == "on") 1 else 0
                val isOn = onValue == 1
                channel.invokeMethod("updateLightState", mapOf("isOn" to isOn))

            } catch (e: Exception) {
                Log.e(TAG, "Error parsing shadow data in onShadowRead", e)
            }
        }
    }
}
