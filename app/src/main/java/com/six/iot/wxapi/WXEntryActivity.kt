package com.six.iot.wxapi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.six.iot.BuildConfig
import com.six.iot.ui.auth.webview.WebViewAuthActivity
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import org.json.JSONObject

class WXEntryActivity : Activity(), IWXAPIEventHandler {

    private lateinit var wxApi: IWXAPI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wxApi = WXAPIFactory.createWXAPI(this, BuildConfig.WECHAT_LOGIN_APPID, false)
        wxApi.handleIntent(intent, this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wxApi.handleIntent(intent, this)
    }

    override fun onReq(req: BaseReq) {
        // Not used for simple login responses
    }

    override fun onResp(resp: BaseResp) {
        if (resp is SendAuth.Resp) {
            val responseJson = JSONObject()

            if (resp.errCode == BaseResp.ErrCode.ERR_OK) {
                // Success! Package the code and state just like your JS expects it
                responseJson.put("status", "success")
                responseJson.put("code", resp.code)
                responseJson.put("state", resp.state)
            } else {
                // Failure or user cancellation
                responseJson.put("status", "error")
                responseJson.put("errCode", resp.errCode)
            }

            // Safely pass this JSON string back to the WebView's JavaScript engine
            sendResultToWebView(responseJson.toString())
        }
        finish() // Always close this transparent routing activity immediately
    }

    private fun sendResultToWebView(jsonResult: String) {
        // Run on the UI main thread to execute JavaScript safely
        runOnUiThread {
            WebViewAuthActivity.currentWebView?.evaluateJavascript(
                "javascript:sixIam.onWeChatLoginResult($jsonResult);",
                null
            )
        }
    }
}