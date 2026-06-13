package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.PrefManager
import com.example.service.CallRecordingService

class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive action: $action")

        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val rawIncomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            
            Log.d(TAG, "Phone State: $stateStr, raw number: $rawIncomingNumber")

            val prefManager = PrefManager(context)
            if (!prefManager.isAutoRecordEnabled) {
                Log.d(TAG, "Auto call recording is disabled in settings. Skipping broadcast triggered recorder.")
                return
            }

            val savedLastState = prefManager.lastPhoneState
            
            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // Incoming call is ringing
                    prefManager.lastPhoneState = TelephonyManager.EXTRA_STATE_RINGING
                    prefManager.callDirection = "INCOMING"
                    if (!rawIncomingNumber.isNullOrEmpty()) {
                        prefManager.lastNumber = rawIncomingNumber
                    } else {
                        prefManager.lastNumber = "Private Number"
                    }
                    Log.d(TAG, "Transition to RINGING, incoming call detected, number: ${prefManager.lastNumber}")
                }
                
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Call answered (incoming) or call dialing/commencing (outgoing)
                    val direction = if (savedLastState == TelephonyManager.EXTRA_STATE_RINGING) {
                        "INCOMING"
                    } else {
                        "OUTGOING"
                    }
                    
                    val phoneNumber = if (direction == "INCOMING") {
                        prefManager.lastNumber ?: "Private Number"
                    } else {
                        rawIncomingNumber ?: prefManager.lastNumber ?: "Outgoing Call"
                    }

                    prefManager.lastPhoneState = TelephonyManager.EXTRA_STATE_OFFHOOK
                    prefManager.callDirection = direction
                    
                    Log.i(TAG, "Transition to OFFHOOK. Starting CallRecordingService. Direction: $direction, Number: $phoneNumber")
                    
                    val startIntent = Intent(context, CallRecordingService::class.java).apply {
                        setAction(CallRecordingService.ACTION_START_RECORDING)
                        putExtra(CallRecordingService.EXTRA_CALL_TYPE, direction)
                        putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, phoneNumber)
                    }
                    
                    try {
                        ContextCompat.startForegroundService(context, startIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting CallRecordingService on Call Answered/Dialing", e)
                    }
                }
                
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Call ended
                    Log.i(TAG, "Transition to IDLE. Stopping CallRecordingService if active.")
                    
                    prefManager.lastPhoneState = TelephonyManager.EXTRA_STATE_IDLE
                    prefManager.callDirection = null
                    prefManager.lastNumber = null
                    
                    val stopIntent = Intent(context, CallRecordingService::class.java).apply {
                        setAction(CallRecordingService.ACTION_STOP_RECORDING)
                    }
                    try {
                        context.startService(stopIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping CallRecordingService on Call Ended", e)
                    }
                }
            }
        }
    }
}
