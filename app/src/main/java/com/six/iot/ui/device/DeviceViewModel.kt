package com.six.iot.ui.device

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class DeviceViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "Provision a device"
    }
    val text: LiveData<String> = _text
}