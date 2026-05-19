// Copyright 2025 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.ui.activities

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.espressif.AppConstants
import com.espressif.network.BindingHandlerHook
import com.espressif.network.DeviceBindingUtil
import com.espressif.provisioning.DeviceConnectionEvent
import com.espressif.provisioning.ESPConstants
import com.espressif.provisioning.ESPConstants.ProvisionFailureReason
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.provisioning.listeners.ProvisionListener
import com.espressif.provisioning.listeners.ResponseListener
import com.six.iot.EspSixUserMapping
import com.six.iam.AuthManager
import com.espressif.wifi_provisioning.R
import com.espressif.wifi_provisioning.databinding.ActivityProvisionBinding
import com.google.protobuf.InvalidProtocolBufferException
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Base64
import java.util.UUID

class ProvisionActivity : AppCompatActivity() {

    companion object {
        private val TAG: String = ProvisionActivity::class.java.simpleName
    }

    private lateinit var binding: ActivityProvisionBinding
    private lateinit var provisionManager: ESPProvisionManager

    private var ssidValue: String? = null
    private var passphraseValue: String? = ""
    private var dataset: String? = null
    private var isProvisioningCompleted = false

    // Stored values from challenge-response
    private var challenge: String? = null
    private var nodeId: String? = null
    private var signature: String? = null
    private var productId: String? = null // New field to store productId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProvisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val intent = intent
        ssidValue = intent.getStringExtra(AppConstants.KEY_WIFI_SSID)
        passphraseValue = intent.getStringExtra(AppConstants.KEY_WIFI_PASSWORD)
        dataset = intent.getStringExtra(AppConstants.KEY_THREAD_DATASET)
        provisionManager = ESPProvisionManager.getInstance(applicationContext)
        initViews()
        EventBus.getDefault().register(this)

        Log.d(TAG, "Selected AP -$ssidValue")
        showLoading()
        doProvisioning()
    }

    override fun onBackPressed() {
        provisionManager.espDevice.disconnectDevice()
        super.onBackPressed()
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(event: DeviceConnectionEvent) {
        Log.d(TAG, "On Device Connection Event RECEIVED : " + event.eventType)

        when (event.eventType) {
            ESPConstants.EVENT_DEVICE_DISCONNECTED -> if (!isFinishing && !isProvisioningCompleted) {
                showAlertForDeviceDisconnected()
            }
        }
    }

    private val okBtnClickListener = View.OnClickListener {
        provisionManager.espDevice?.disconnectDevice()
        finish()
    }

    private fun initViews() {
        setToolbar()
        binding.btnOk.ivArrow.visibility = View.GONE
        binding.btnOk.textBtn.setText(R.string.btn_ok)
        binding.btnOk.layoutBtn.setOnClickListener(okBtnClickListener)
    }

    private fun setToolbar() {
        setSupportActionBar(binding.titleBar.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowHomeEnabled(false)
            setDisplayShowTitleEnabled(false) // Hide the default title
        }
        binding.titleBar.toolbarTitle.text = getString(R.string.title_activity_provisioning)
    }

    private fun doProvisioning() {
        // Start with the association step (challenge-response) first.
        associateDevice()
    }

    private fun associateDevice() {
        Log.d(TAG, "Starting Step 1: Associate device (Challenge-Response)")

        runOnUiThread {
            binding.ivTick0.visibility = View.GONE
            binding.provProgress0.visibility = View.VISIBLE
        }

        challenge = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray())

        val deviceSecretRequest = EspSixUserMapping.CmdSetUserMapping.newBuilder()
            .setChallenge(challenge)
            .build()

        val payload = EspSixUserMapping.SixConfigPayload.newBuilder()
            .setMsg(EspSixUserMapping.SixConfigMsgType.TypeCmdSetUserMapping)
            .setCmdSetUserMapping(deviceSecretRequest)
            .build()

        provisionManager.espDevice.sendDataToCustomEndPoint(
            AppConstants.HANDLER_RM_USER_MAPPING,
            payload.toByteArray(),
            object : ResponseListener {
                override fun onSuccess(returnData: ByteArray?) {
                    Log.d(TAG, "Step 1 (Association) successful.")
                    runOnUiThread {
                        binding.ivTick0.setImageResource(R.drawable.ic_checkbox_on)
                        binding.ivTick0.visibility = View.VISIBLE
                        binding.provProgress0.visibility = View.GONE
                    }
                    processDetails(returnData, challenge)
                    startWifiProvisioning()
                }

                override fun onFailure(e: java.lang.Exception) {
                    Log.e(TAG, "Step 1 (Association) failed: " + e.message)
                    runOnUiThread {
                        binding.ivTick0.setImageResource(R.drawable.ic_error)
                        binding.ivTick0.visibility = View.VISIBLE
                        binding.provProgress0.visibility = View.GONE
                        binding.tvProvError0.visibility = View.VISIBLE
                        binding.tvProvError0.text = getString(R.string.error_challenge_response_failed)
                        binding.tvProvError.visibility = View.VISIBLE
                        hideLoading()
                    }
                    e.printStackTrace()
                }
            })
    }

    private fun startWifiProvisioning() {
        Log.d(TAG, "Starting Step 2: Wi-Fi Provisioning")

        runOnUiThread {
            binding.ivTick1.visibility = View.GONE
            binding.provProgress1.visibility = View.VISIBLE
        }

        val provisionListener = object : ProvisionListener {
            override fun createSessionFailed(e: Exception) {
                runOnUiThread {
                    binding.ivTick1.setImageResource(R.drawable.ic_error)
                    binding.ivTick1.visibility = View.VISIBLE
                    binding.provProgress1.visibility = View.GONE
                    binding.tvProvError1.visibility = View.VISIBLE
                    binding.tvProvError1.setText(R.string.error_session_creation)
                    binding.tvProvError.visibility = View.VISIBLE
                    hideLoading()
                }
            }

            override fun wifiConfigSent() {
                runOnUiThread {
                    binding.ivTick1.setImageResource(R.drawable.ic_checkbox_on)
                    binding.ivTick1.visibility = View.VISIBLE
                    binding.provProgress1.visibility = View.GONE
                    binding.ivTick2.visibility = View.GONE
                    binding.provProgress2.visibility = View.VISIBLE
                }
            }

            override fun wifiConfigFailed(e: Exception) {
                runOnUiThread {
                    binding.ivTick1.setImageResource(R.drawable.ic_error)
                    binding.ivTick1.visibility = View.VISIBLE
                    binding.provProgress1.visibility = View.GONE
                    binding.tvProvError1.visibility = View.VISIBLE
                    binding.tvProvError1.setText(R.string.error_prov_thread_step_1)
                    binding.tvProvError.visibility = View.VISIBLE
                    hideLoading()
                }
            }

            override fun wifiConfigApplied() {
                runOnUiThread {
                    binding.ivTick2.setImageResource(R.drawable.ic_checkbox_on)
                    binding.ivTick2.visibility = View.VISIBLE
                    binding.provProgress2.visibility = View.GONE
                    binding.ivTick3.visibility = View.GONE
                    binding.provProgress3.visibility = View.VISIBLE
                }
            }

            override fun wifiConfigApplyFailed(e: Exception) {
                runOnUiThread {
                    binding.ivTick2.setImageResource(R.drawable.ic_error)
                    binding.ivTick2.visibility = View.VISIBLE
                    binding.provProgress2.visibility = View.GONE
                    binding.tvProvError2.visibility = View.VISIBLE
                    binding.tvProvError2.setText(R.string.error_prov_thread_step_2)
                    binding.tvProvError.visibility = View.VISIBLE
                    hideLoading()
                }
            }

            override fun provisioningFailedFromDevice(failureReason: ProvisionFailureReason) {
                runOnUiThread {
                    binding.ivTick3.setImageResource(R.drawable.ic_error)
                    binding.ivTick3.visibility = View.VISIBLE
                    binding.provProgress3.visibility = View.GONE
                    binding.tvProvError3.visibility = View.VISIBLE
                    when (failureReason) {
                        ProvisionFailureReason.AUTH_FAILED -> binding.tvProvError3.setText(R.string.error_authentication_failed)
                        ProvisionFailureReason.NETWORK_NOT_FOUND -> binding.tvProvError3.setText(R.string.error_network_not_found)
                        else -> binding.tvProvError3.setText(R.string.error_prov_step_3)
                    }
                    binding.tvProvError.visibility = View.VISIBLE
                    hideLoading()
                }
            }

            override fun deviceProvisioningSuccess() {
                runOnUiThread {
                    isProvisioningCompleted = true
                    binding.ivTick3.setImageResource(R.drawable.ic_checkbox_on)
                    binding.ivTick3.visibility = View.VISIBLE
                    binding.provProgress3.visibility = View.GONE

                    // Start the final binding step
                    bindDeviceToCloud()
                }
            }

            override fun onProvisioningFailed(e: Exception) {
                runOnUiThread {
                    binding.ivTick3.setImageResource(R.drawable.ic_error)
                    binding.ivTick3.visibility = View.VISIBLE
                    binding.provProgress3.visibility = View.GONE
                    binding.tvProvError3.visibility = View.VISIBLE
                    binding.tvProvError3.setText(R.string.error_prov_step_3)
                    binding.tvProvError.visibility = View.VISIBLE
                    hideLoading()
                }
            }
        }

        if (!TextUtils.isEmpty(dataset)) {
            provisionManager.espDevice.provision(dataset, provisionListener)
        } else {
            provisionManager.espDevice.provision(ssidValue, passphraseValue, provisionListener)
        }
    }

    private fun bindDeviceToCloud() {
        Log.d(TAG, "Starting Step 4: Binding with Cloud")

        runOnUiThread {
            binding.ivTick4.visibility = View.GONE
            binding.provProgress4.visibility = View.VISIBLE
        }

        if (nodeId == null || challenge == null || signature == null || productId == null) {
            Log.e(TAG, "Missing data for cloud binding.")
            onBindingFailure(getString(R.string.error_binding_failed))
            return
        }

        val token = AuthManager.authenticatedAccessToken(this)
        if (token == null) {
            Log.e(TAG, "Not authenticated, cannot bind device.")
            onBindingFailure(getString(R.string.error_token_not_found))
            return
        }
        val deviceGuid = nodeId!!

        DeviceBindingUtil().bindDevice(token, deviceGuid, productId!!, challenge!!, signature!!, object : BindingHandlerHook {
            override fun onBindingSuccess(responseMap: Map<String, Any>) {
                val status = responseMap["status"] as? Int
                val alreadyBound = responseMap["alreadyBound"] as? Boolean

                if (status == 200) {
                    if (alreadyBound == true) {
                        onBindingFailure(getString(R.string.error_already_bound))
                    } else {
                        Log.d(TAG, "Cloud binding successful.")
                        runOnUiThread {
                            binding.ivTick4.setImageResource(R.drawable.ic_checkbox_on)
                            binding.ivTick4.visibility = View.VISIBLE
                            binding.provProgress4.visibility = View.GONE
                            hideLoading() // All steps are now complete
                        }
                    }
                } else {
                    onBindingFailure(getString(R.string.error_binding_failed_with_status, status.toString()))
                }
            }

            override fun onBindingFailure() {
                onBindingFailure(getString(R.string.error_binding_failed))
            }
        })
    }

    private fun onBindingFailure(errorMessage: String?) {
        Log.e(TAG, "Step 4 (Cloud Binding) failed: ${errorMessage ?: "Unknown error"}")
        runOnUiThread {
            binding.ivTick4.setImageResource(R.drawable.ic_error)
            binding.ivTick4.visibility = View.VISIBLE
            binding.provProgress4.visibility = View.GONE
            binding.tvProvError4.visibility = View.VISIBLE
            binding.tvProvError4.text = errorMessage ?: getString(R.string.error_binding_failed)
            binding.tvProvError.visibility = View.VISIBLE
            hideLoading()
        }
    }

    private fun showLoading() {
        binding.btnOk.layoutBtn.isEnabled = false
        binding.btnOk.layoutBtn.alpha = 0.5f
    }

    fun hideLoading() {
        binding.btnOk.layoutBtn.isEnabled = true
        binding.btnOk.layoutBtn.alpha = 1f
    }

    private fun showAlertForDeviceDisconnected() {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        builder.setTitle(R.string.error_title)
        builder.setMessage(R.string.dialog_msg_ble_device_disconnection)

        builder.setPositiveButton(R.string.btn_ok) { dialog, which ->
            dialog.dismiss()
            finish()
        }
        builder.show()
    }

    private fun processDetails(returnData: ByteArray?, secretKey: String?) {
        if (returnData == null) {
            Log.e(TAG, "Device returned no data for association.")
            return
        }

        try {
            val payload = EspSixUserMapping.SixConfigPayload.parseFrom(returnData)
            if (payload.msg == EspSixUserMapping.SixConfigMsgType.TypeRespSetUserMapping) {
                val response = payload.respSetUserMapping
                nodeId = response.nodeId
                productId = response.productId
                signature = response.signature
                challenge = secretKey

                Log.d(TAG, "Successfully parsed challenge response.")
                Log.d(TAG, "Received Node ID: $nodeId")
                Log.d(TAG, "Received Product ID: $productId")
            } else {
                Log.e(TAG, "Received unexpected message type from device: ${payload.msg}")
            }
        } catch (e: InvalidProtocolBufferException) {
            Log.e(TAG, "Failed to parse association response from device.", e)
        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred in processDetails.", e)
        }
    }
}
