package com.six.iot

interface DeviceHandlerHook {
    fun userDevicesGetSucceed(devicesResp: Map<String, Any>)
    fun userDevicesGetFail()
}