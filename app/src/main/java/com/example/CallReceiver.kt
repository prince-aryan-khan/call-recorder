package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            Log.d(TAG, "Phone state broadcast received: $stateStr")

            val state = when (stateStr) {
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                else -> -1
            }

            if (state != -1) {
                val serviceIntent = Intent(context, CallRecordService::class.java).apply {
                    action = CallRecordService.ACTION_CALL_STATE_CHANGED
                    putExtra(CallRecordService.EXTRA_CALL_STATE, state)
                }
                try {
                    // Modern background execution allows starting foreground services via telephony broadcast
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed initiating CallRecordService from call state change broadcast: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "CallReceiver"
    }
}
