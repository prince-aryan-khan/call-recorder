package com.example.data

import android.content.Context
import android.content.SharedPreferences

class PrefManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("call_recorder_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_AUTO_RECORD = "key_auto_record"
        private const val KEY_CALL_DIRECTION = "key_call_direction"
        private const val KEY_LAST_PHONE_STATE = "key_last_phone_state"
        private const val KEY_LAST_NUMBER = "key_last_number"
        private const val KEY_IS_RECORDING = "key_is_recording"
    }

    var isAutoRecordEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECORD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RECORD, value).apply()

    var lastPhoneState: String?
        get() = prefs.getString(KEY_LAST_PHONE_STATE, null)
        set(value) = prefs.edit().putString(KEY_LAST_PHONE_STATE, value).apply()

    var callDirection: String?
        get() = prefs.getString(KEY_CALL_DIRECTION, null)
        set(value) = prefs.edit().putString(KEY_CALL_DIRECTION, value).apply()

    var lastNumber: String?
        get() = prefs.getString(KEY_LAST_NUMBER, null)
        set(value) = prefs.edit().putString(KEY_LAST_NUMBER, value).apply()

    var isRecordingActive: Boolean
        get() = prefs.getBoolean(KEY_IS_RECORDING, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_RECORDING, value).apply()
}
