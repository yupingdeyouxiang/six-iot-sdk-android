package com.six.iot

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.FrameLayout
import org.json.JSONObject
import java.lang.reflect.Method

class DeviceRender {

    companion object {
        private const val TAG = "DeviceRender"
        private const val PROP_UI_PANEL = "ui.panel"
        private const val PREFIX_CLASS = "class:"

        /**
         * Renders the shadow panel by calling a reflected static method.
         */
        fun renderDeviceShadowPanel(shadowPanelContainer: FrameLayout, device: Device) {
            val clazz = getTargetClass(device) ?: return
            try {
                val method: Method = clazz.getMethod(
                    "renderDeviceShadowPanel",
                    FrameLayout::class.java,
                    Device::class.java
                )
                method.invoke(null, shadowPanelContainer, device)
            } catch (e: Exception) {
                Log.e(TAG, "Error in reflection for renderDeviceShadowPanel", e)
            }
        }

        /**
         * Returns an Intent by calling a reflected static newIntent method.
         */
        fun getDevicePanelIntent(context: Context, device: Device): Intent? {
            val clazz = getTargetClass(device) ?: return null
            return try {
                val method: Method = clazz.getMethod(
                    "newIntent",
                    Context::class.java,
                    Device::class.java
                )
                method.invoke(null, context, device) as? Intent
            } catch (e: Exception) {
                Log.e(TAG, "Error in reflection for launchDevicePanel", e)
                null
            }
        }

        /**
         * Shared logic to extract the className from device props and get the Class object.
         */
        private fun getTargetClass(device: Device): Class<*>? {
            return try {
                val props = device.product["props"] as? List<*> ?: return null

                val panelProp = props.find {
                    val map = it as? Map<*, *>
                    map?.get("name") == PROP_UI_PANEL
                } as? Map<*, *> ?: return null

                val propValue = panelProp["value"] as? String ?: return null
                val jsonValue = JSONObject(propValue)
                val androidValue = jsonValue.optString("android")

                if (androidValue.startsWith(PREFIX_CLASS)) {
                    val className = androidValue.substring(PREFIX_CLASS.length).trim()
                    Class.forName(className)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing ui.panel class path", e)
                null
            }
        }
    }
}