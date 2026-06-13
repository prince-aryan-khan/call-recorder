package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream

class CallRecordService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFileUri: Uri? = null
    private var currentParcelFileDescriptor: ParcelFileDescriptor? = null
    private var telephonyCallback: Any? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate service started")
        createNotificationChannel()

        // Start as foreground immediately with microphone status type to comply with API 34+ guidelines
        val notification = createNotification("Idle Guard: Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        registerTelephonyCallback()
        instance = this
        _isServiceRunning.value = true
        _serviceStatus.value = "Idle Guard: Monitoring"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START_SERVICE -> {
                _serviceStatus.value = "Idle Guard: Monitoring"
                updateNotification("Idle Guard: Active")
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }
            ACTION_CALL_STATE_CHANGED -> {
                val state = intent.getIntExtra(EXTRA_CALL_STATE, -1)
                handleCallStateTransition(state)
            }
            ACTION_MANUAL_RECORD_START -> {
                startManualRecord()
            }
            ACTION_MANUAL_RECORD_STOP -> {
                stopManualRecord()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy service shutting down")
        super.onDestroy()
        unregisterTelephonyCallback()
        stopRecording()
        _isServiceRunning.value = false
        _isRecording.value = false
        _serviceStatus.value = "Idle Guard: Inactive"
        instance = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // --- Core Recording Implementation ---

    private fun startRecording(context: Context) {
        if (_isRecording.value) {
            Log.d(TAG, "Recording already in progress, ignoring start request")
            return
        }

        try {
            val fileName = "Call_Record_${System.currentTimeMillis()}.mp4"
            val uri = createAudioFileUri(context, fileName)
            if (uri == null) {
                Log.e(TAG, "Failed to register audio file in MediaStore")
                _serviceStatus.value = "Capture Error: MediaStore failure"
                return
            }

            currentFileUri = uri
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            currentParcelFileDescriptor = pfd

            if (pfd == null) {
                Log.e(TAG, "Failed to open FileDescriptor for MediaStore uri")
                _serviceStatus.value = "Capture Error: FileDescriptor failure"
                return
            }

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                // Using hardware microphone stream workaround to avoid modern API silence blocks
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }

            mediaRecorder = recorder
            _isRecording.value = true
            Log.d(TAG, "Call recording successfully started: $fileName")

        } catch (e: Exception) {
            Log.e(TAG, "Exception during call record initiation: ${e.message}", e)
            _serviceStatus.value = "Capture Error: ${e.localizedMessage}"
            cleanupRecording()
        }
    }

    private fun stopRecording() {
        if (!_isRecording.value) return

        Log.d(TAG, "Stopping active recording")
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping MediaRecorder (stream might be too short): ${e.message}")
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null

            currentParcelFileDescriptor?.close()
            currentParcelFileDescriptor = null

            currentFileUri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    try {
                        contentResolver.update(uri, contentValues, null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating pending status key: ${e.message}")
                    }
                }
            }
            currentFileUri = null
            _isRecording.value = false
            Log.d(TAG, "Call recording stopped and saved to MediaStore")
        }
    }

    private fun cleanupRecording() {
        mediaRecorder?.release()
        mediaRecorder = null
        try {
            currentParcelFileDescriptor?.close()
        } catch (e: Exception) {
            // Safe ignore
        }
        currentParcelFileDescriptor = null
        currentFileUri = null
        _isRecording.value = false
    }

    private fun createAudioFileUri(context: Context, fileName: String): Uri? {
        val resolver = context.contentResolver
        val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Recordings/CallRecorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        return try {
            resolver.insert(audioCollection, contentValues)
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating media collection entry: ${e.message}")
            null
        }
    }

    // --- State Transitions ---

    private fun handleCallStateTransition(state: Int) {
        Log.d(TAG, "Transitioning call state to: $state")
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                _serviceStatus.value = "Recording active session..."
                updateNotification("Recording call session...")
                startRecording(this)
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                _serviceStatus.value = "Call incoming/outgoing..."
                updateNotification("Ringing alert active...")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (_isRecording.value) {
                    stopRecording()
                }
                _serviceStatus.value = "Idle Guard: Monitoring"
                updateNotification("Idle Guard: Active")
            }
        }
    }

    private fun startManualRecord() {
        _serviceStatus.value = "Manual Rec Test Active"
        updateNotification("Recording manual workflow...")
        startRecording(this)
    }

    private fun stopManualRecord() {
        stopRecording()
        _serviceStatus.value = "Idle Guard: Monitoring"
        updateNotification("Idle Guard: Active")
    }

    // --- Telephony Manager Subsystem ---

    private fun registerTelephonyCallback() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    Log.d(TAG, "TelephonyCallback: state changed to $state")
                    handleCallStateTransition(state)
                }
            }
            telephonyCallback = callback
            try {
                telephonyManager.registerTelephonyCallback(mainExecutor, callback)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException registering TelephonyCallback: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering TelephonyCallback programmatically: ${e.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : android.telephony.PhoneStateListener() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    Log.d(TAG, "PhoneStateListener: state changed to $state")
                    handleCallStateTransition(state)
                }
            }
            telephonyCallback = listener
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering PhoneStateListener: ${e.message}")
            }
        }
    }

    private fun unregisterTelephonyCallback() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                if (it is TelephonyCallback) {
                    try {
                        telephonyManager.unregisterTelephonyCallback(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed unregistering TelephonyCallback: ${e.message}")
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(null, android.telephony.PhoneStateListener.LISTEN_NONE)
        }
        telephonyCallback = null
    }

    // --- Notification Helpers ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recorder Guard Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps recording listener active in background and displays status updates."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Recorder Guard")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(statusText))
    }

    companion object {
        private const val TAG = "CallRecordService"

        const val CHANNEL_ID = "call_recorder_guard_channel"
        const val NOTIFICATION_ID = 4452

        const val ACTION_START_SERVICE = "com.example.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"
        const val ACTION_CALL_STATE_CHANGED = "com.example.action.CALL_STATE_CHANGED"
        const val EXTRA_CALL_STATE = "com.example.extra.CALL_STATE"

        const val ACTION_MANUAL_RECORD_START = "com.example.action.MANUAL_RECORD_START"
        const val ACTION_MANUAL_RECORD_STOP = "com.example.action.MANUAL_RECORD_STOP"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _serviceStatus = MutableStateFlow("Idle Guard: Inactive")
        val serviceStatus: StateFlow<String> = _serviceStatus.asStateFlow()

        private var instance: CallRecordService? = null
    }
}
