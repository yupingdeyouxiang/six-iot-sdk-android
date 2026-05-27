package com.six.iot.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.six.iot.UserUtil
import com.six.iot.events.MqttConnectedEvent
import com.six.iot.events.MqttMessageArriveEvent
import com.six.iot.events.ShadowGetAcceptedEvent
import com.six.iot.events.ShadowUpdateAcceptedEvent
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.greenrobot.eventbus.EventBus
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class MqttClientService : Service() {

    companion object {
        private const val TAG = "MqttClientService"
        private const val EXTRA_ID_TOKEN = "idToken"
        private const val EXTRA_MQTT_URL = "mqttUrl"
        private const val EXTRA_CUSTOM_AUTHZ = "customAuthz"
        private const val EXTRA_CUSTOM_AUTHZ_USERNAME = "customAuthzUserName"

        fun startService(
            context: Context,
            idToken: String,
            mqttUrl: String,
            customAuthz: Boolean,
            customAuthzUserName: String?
        ) {
            val intent = Intent(context, MqttClientService::class.java).apply {
                putExtra(EXTRA_ID_TOKEN, idToken)
                putExtra(EXTRA_MQTT_URL, mqttUrl)
                putExtra(EXTRA_CUSTOM_AUTHZ, customAuthz)
                putExtra(EXTRA_CUSTOM_AUTHZ_USERNAME, customAuthzUserName)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, MqttClientService::class.java))
        }
    }

    // Mapping URL to Client and its specific metadata
    private val clients = ConcurrentHashMap<String, MqttAndroidClient>()
    private val connectingStates = ConcurrentHashMap<String, AtomicBoolean>()
    private val subscribedTopicsMap = ConcurrentHashMap<String, MutableSet<String>>()
    private val publishQueuesMap = ConcurrentHashMap<String, ConcurrentLinkedQueue<Pair<String, String>>>()

    private val binder = MqttBinder()

    inner class MqttBinder : Binder() {
        fun getService(): MqttClientService = this@MqttClientService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_REDELIVER_INTENT

        val idToken = intent.getStringExtra(EXTRA_ID_TOKEN)
        val mqttUrl = intent.getStringExtra(EXTRA_MQTT_URL) ?: ""
        val customAuthz = intent.getBooleanExtra(EXTRA_CUSTOM_AUTHZ, false)
        val customAuthzUserName = intent.getStringExtra(EXTRA_CUSTOM_AUTHZ_USERNAME) ?: ""

        if (idToken.isNullOrEmpty() || mqttUrl.isEmpty()) {
            return START_REDELIVER_INTENT
        }

        // Ensure thread-safe client creation per URL
        synchronized(clients) {
            if (!clients.containsKey(mqttUrl)) {
                val clientId = MqttClient.generateClientId()
                val newClient = MqttAndroidClient(applicationContext, mqttUrl, clientId)
                clients[mqttUrl] = newClient
                connectingStates[mqttUrl] = AtomicBoolean(false)
                subscribedTopicsMap[mqttUrl] = HashSet()
                publishQueuesMap[mqttUrl] = ConcurrentLinkedQueue()
                //only connect the client for first time
                connect(mqttUrl, idToken, customAuthz, customAuthzUserName)
                Log.d(TAG, "New MqttAndroidClient created for URL: $mqttUrl")
            }
        }

        return START_REDELIVER_INTENT
    }

    private fun connect(url: String, idToken: String, customAuthz: Boolean, customAuthzUserName: String) {
        val client = clients[url] ?: return
        val isConnecting = connectingStates[url] ?: return

        if (!isConnecting.compareAndSet(false, true)) return

        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                isConnecting.set(false)
                Log.d(TAG, "Connected to $url. Reconnect: $reconnect")
                processSubscribeQueue(url)
                processPublishQueue(url)
            }

            override fun connectionLost(cause: Throwable?) {
                isConnecting.set(false)
                Log.e(TAG, "Connection lost for $url", cause)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                handleIncomingMessage(topic, message)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val options = MqttConnectOptions().apply {
            serverURIs = arrayOf(url)
            isAutomaticReconnect = true
            isCleanSession = true
            userName = if (customAuthz) customAuthzUserName
            else UserUtil.parseOpenidFromIdToken(idToken)
            password = idToken.toCharArray()
        }

        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    isConnecting.set(false)
                    EventBus.getDefault().post(MqttConnectedEvent(url))
                    Log.d(TAG, "Connection Success: $url")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    isConnecting.set(false)
                    Log.e(TAG, "Connection Failure: $url", exception)
                    Handler(Looper.getMainLooper()).post {
                        // NOTE: Replace 'context' with your actual Context reference
                        Toast.makeText(this@MqttClientService, "Failed to connect to MQTT broker for: $url", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        } catch (e: MqttException) {
            isConnecting.set(false)
        }
    }

    private fun handleIncomingMessage(topic: String, message: MqttMessage) {
        try {
            val payload = String(message.payload)
            val json = JSONObject(payload)
            val topicParts = topic.split("/")
            if (topicParts.size > 4) {
                val productId = topicParts[0]
                val guid = topicParts[1]
                if ("shadow" == topicParts[2] && "get" == topicParts[3] && "accepted" == topicParts[4]) {
                    EventBus.getDefault().post(ShadowGetAcceptedEvent(productId, guid, json))
                    return
                } else if ("shadow" == topicParts[2] && "update" == topicParts[3] && "accepted" == topicParts[4]) {
                    EventBus.getDefault().post(ShadowUpdateAcceptedEvent(productId, guid, json))
                    return
                }
            }
            EventBus.getDefault().post(MqttMessageArriveEvent(topic, json))
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }
    }

    fun publish(url: String, topic: String, payload: String) {
        val client = clients[url]
        if (client == null || !client.isConnected) {
            publishQueuesMap[url]?.add(Pair(topic, payload))
            return
        }
        try {
            val message = MqttMessage(payload.toByteArray()).apply { qos = 1 }
            client.publish(topic, message)
        } catch (e: MqttException) {
            Log.e(TAG, "Publish failed for $url", e)
        }
    }

    private fun processPublishQueue(url: String) {
        val queue = publishQueuesMap[url] ?: return
        while (queue.isNotEmpty()) {
            queue.poll()?.let { (topic, payload) ->
                publish(url, topic, payload)
            }
        }
    }

    fun subscribe(url: String, topic: String) {
        val topicSet = subscribedTopicsMap.computeIfAbsent(url) { ConcurrentHashMap.newKeySet() }
        topicSet.add(topic)

        val client = clients[url]
        if (client == null || !client.isConnected) {
            Log.d(TAG, "Client offline. Cached topic for subscription later: $topic")
            return
        }

        try {
            client.subscribe(topic, 1)
            Log.d(TAG, "Successfully subscribed to: $topic on $url")
        } catch (e: MqttException) {
            Log.e(TAG, "Subscribe failed for $topic on $url", e)
        }
    }

    private fun processSubscribeQueue(url: String) {
        val cachedTopics = subscribedTopicsMap[url] ?: return
        // Iterate through all previously requested topics and subscribe to them on the new connection
        for (topic in cachedTopics) {
            subscribe(url, topic)
        }
    }

    /*fun subscribeToTopics(url: String, topics: List<String>) {
        val client = clients[url] ?: return
        if (!client.isConnected) return

        val subscribedSet = subscribedTopicsMap[url] ?: return
        val newTopics = topics.filter { !subscribedSet.contains(it) }

        if (newTopics.isEmpty()) return

        try {
            client.subscribe(newTopics.toTypedArray(), IntArray(newTopics.size) { 1 }, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    subscribedSet.addAll(newTopics)
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {}
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Subscribe error", e)
        }
    }*/

    override fun onDestroy() {
        super.onDestroy()
        clients.values.forEach { client ->
            try {
                if (client.isConnected) client.disconnect()
            } catch (e: MqttException) { }
        }
        clients.clear()
    }
}