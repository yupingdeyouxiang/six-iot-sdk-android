package com.six.iot

// The Device data class now holds the entire reported state from the device shadow.
data class Device(
    val name: String,
    val status: String,
    val iconUrl: String,
    val guid: String,
    val location: String,
    val productId: String,
    var product: Map<String, Any> = emptyMap(),
    var thing: Map<String, Any> = emptyMap(),
    var shadow: Map<String, Any> = emptyMap()
)