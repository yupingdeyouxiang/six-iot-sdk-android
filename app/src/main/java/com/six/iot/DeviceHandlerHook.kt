package com.six.iot

interface DeviceHandlerHook {
    fun userDevicesGetSucceed(devices: Map<String, Any>)
    fun userDevicesGetFail()
}