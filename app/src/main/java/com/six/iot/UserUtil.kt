package com.six.iot

import android.content.Context
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.six.iam.handler.AuthHandlerHook
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.json.JSONObject

class UserUtil {

    private var tag = javaClass.name;
    private val preference : String = "six-iot-userinfo"
    private val objectMapper: ObjectMapper = ObjectMapper()

    companion object {
        fun parseOpenidFromIdToken(idToken: String?): String? {
            if (idToken == null) {
                return null
            }
            try {
                val parts = idToken.split(".")
                if (parts.size > 1) {
                    val payload = parts[1]
                    val decodedBytes = Base64.decode(payload, Base64.URL_SAFE)
                    val decodedString = String(decodedBytes, Charsets.UTF_8)
                    val json = JSONObject(decodedString)
                    return json.optString("openId")
                }
            } catch (e: Exception) {
                // Log the exception, as it's useful for debugging
                Log.e("UserUtil", "Failed to parse ID token", e)
            }
            return null // Return null if parsing fails or token is invalid
        }

        fun parseSubFromIdToken(idToken: String?): String? {
            if (idToken == null) {
                return null
            }
            try {
                val parts = idToken.split(".")
                if (parts.size > 1) {
                    val payload = parts[1]
                    val decodedBytes = Base64.decode(payload, Base64.URL_SAFE)
                    val decodedString = String(decodedBytes, Charsets.UTF_8)
                    val json = JSONObject(decodedString)
                    return json.optString("sub")
                }
            } catch (e: Exception) {
                // Log the exception, as it's useful for debugging
                Log.e("UserUtil", "Failed to parse ID token", e)
            }
            return null // Return null if parsing fails or token is invalid
        }
    }

    fun saveUserOpenId(context:Context, userId: String) {
        val sharedPreference =  context.getSharedPreferences(preference,Context.MODE_PRIVATE)
        val editor = sharedPreference.edit()
        editor.putString("id",userId)
        editor.apply()
    }

    fun saveUser(context:Context, userinfo: String) {
        val sharedPreference =  context.getSharedPreferences(preference,Context.MODE_PRIVATE)
        val editor = sharedPreference.edit()
        editor.putString("userinfo", userinfo)
        editor.apply()
    }

    fun readUserOpenId(context:Context):String? {
        val sharedPreference =  context.getSharedPreferences(preference,Context.MODE_PRIVATE)
        return sharedPreference.getString("id", null);
    }

    fun getUserOpenId(token: String, hook: AuthHandlerHook) {
        val request: Request = Request.Builder()
            .url(Config.userInfoUrl)
            .addHeader("Authorization", "Bearer $token")
            .get().build();
        val client = OkHttpClient()
        val call = client.newCall(request)

        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {
                hook.userOpenIdGetFail()
            }

            override fun onResponse(call: Call, response: Response) {
                var userinfo: String = response.body.string();
                println(userinfo);
                try {
                    var userinfoMap: Map<String, Any> = HashMap();
                    userinfoMap = objectMapper.readValue(userinfo, userinfoMap.javaClass);
                    if(userinfoMap["user"] != null) {
                        val userMap: Map<String, Any> = userinfoMap["user"] as Map<String, Any>;
                        val userOpenId:String = userMap["id"] as String;
                        Log.i(tag, "user openId is $userOpenId");
                        hook.userOpenIdGetSucceed(userOpenId);
                        return;
                    }
                    hook.userOpenIdGetFail();
                }catch (e:Exception) {
                    Log.e(tag, "fail to get userInfo");
                    hook.userOpenIdGetFail();
                }
            }
        })
    }
}
