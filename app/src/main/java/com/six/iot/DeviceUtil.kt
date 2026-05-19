package com.six.iot

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException

class DeviceUtil {

    private var tag = javaClass.name;
    private val objectMapper: ObjectMapper = ObjectMapper()

    fun getUserDevices(token: String, hook: DeviceHandlerHook) {
        if (null == token) {
            Log.i(tag, "can't get the device with NULL token")
            return
        }
        val request: Request = Request.Builder()
            .url(Config.userDevicesUrl)
            .addHeader("Authorization", "Bearer $token")
            .get().build();
        val client = OkHttpClient()
        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                hook.userDevicesGetFail()
            }

            override fun onResponse(call: Call, response: Response) {
                var devices: String = response.body.string();
                println(devices);
                try {
                    var devicesMap: Map<String, Any> = HashMap();
                    devicesMap = objectMapper.readValue(devices, devicesMap.javaClass);
                    hook.userDevicesGetSucceed(devicesMap)
                } catch (e: Exception) {
                    Log.e(tag, "fail to get devices for the users");
                    hook.userDevicesGetFail()
                }
            }
        })
    }
}