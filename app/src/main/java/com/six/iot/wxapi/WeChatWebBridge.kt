package com.six.iot.wxapi

import android.content.Context
import android.webkit.JavascriptInterface
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import org.json.JSONObject

class WeChatWebBridge(private val context: Context) {

    @JavascriptInterface
    fun startWeChatLogin(paramsJson: String) {
        try {
            val jsonObject = JSONObject(paramsJson)
            // Extract the state parameter if your JS passes it, or default to a random string
            val state = jsonObject.optString("state")
            val appId = jsonObject.optString("appid")
            val scope = jsonObject.optString("scope")

            // Initialize WeChat API (Replace with your actual WeChat App ID)
            val wxApi = WXAPIFactory.createWXAPI(context, appId, true)
            wxApi.registerApp(appId)

            if (!wxApi.isWXAppInstalled) {
                // You might want to pass an error back to the webview here
                return
            }

            // Trigger the native WeChat Login request
            val req = SendAuth.Req().apply {
                this.scope = scope // Standard scope for login
                this.state = state
            }
            wxApi.sendReq(req)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}