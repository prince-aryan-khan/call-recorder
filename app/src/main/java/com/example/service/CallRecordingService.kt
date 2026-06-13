package com.example.service

import android.app.*
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.CallRecordEntity
import com.example.data.PrefManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

class CallRecordingService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var mediaRecorder: MediaRecorder? = null
    private var currentUri: Uri? = null
    private var currentRecordId: Int? = null
    private var startTimeMs: Long = 0L
    private var pfd: ParcelFileDescriptor? = null
    private var isRecordingLocal = false

    private lateinit var prefManager: PrefManager
    private lateinit var db: AppDatabase

    companion object {
        private const val TAG = "CallRecordingService"
        private const val NOTIFICATION_ID = 8816
        private const val CHANNEL_ID = "call_recording_channel"

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _activeCallNumber = MutableStateFlow<String?>(null)
        val activeCallNumber: StateFlow<String?> = _activeCallNumber.asStateFlow()

        private val _recordingDurationSec = MutableStateFlow(0)
        val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

        // Commands
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        
        // Extras
        const val EXTRA_CALL_TYPE = "EXTRA_CALL_TYPE" // INCOMING, OUTGOING, MANUAL
        const val EXTRA_PHONE_NUMBER = "EXTRA_PHONE_NUMBER"

        var durationJob: Job? = null
    }

    override fun onCreate() {
        super.onCreate()
        prefManager = PrefManager(this)
        db = AppDatabase.getDatabase(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val callType = intent?.getStringExtra(EXTRA_CALL_TYPE) ?: "MANUAL"
        val phoneNumber = intent?.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Private Number"

        Log.d(TAG, "onStartCommand action: $action, callType: $callType, number: $phoneNumber")

        when (action) {
            ACTION_START_RECORDING -> {
                startRecordingFlow(callType, phoneNumber)
            }
            ACTION_STOP_RECORDING -> {
                stopRecordingFlow()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecordingFlow(callType: String, phoneNumber: String) {
        if (isRecordingLocal) {
            Log.w(TAG, "Recording is already active, ignoring start request")
            return
        }

        prefManager.isRecordingActive = true
        _activeCallNumber.value = if (phoneNumber.isBlank() || phoneNumber == "Private Number") "Unknown Caller" else phoneNumber
        _isRecording.value = true

        val notification = createNotification("Recording call audio...", phoneNumber)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service in foreground", e)
            stopSelf()
            return
        }

        startTimeMs = System.currentTimeMillis()
        val mediaPair = createMediaStoreAudioFile(callType, phoneNumber)
        if (mediaPair == null) {
            Log.e(TAG, "Failed to create media store file, stopping recording flow")
            updateNotification("Failed to prepare storage.", "")
            stopSelfFlow()
            return
        }

        currentUri = mediaPair.first
        val displayName = mediaPair.second

        if (currentUri == null) {
            Log.e(TAG, "Uri is null!")
            stopSelfFlow()
            return
        }

        // Insert placeholder in database
        serviceScope.launch {
            val recordId = db.callRecordDao().insertRecord(
                CallRecordEntity(
                    fileName = displayName,
                    fileUri = currentUri.toString(),
                    phoneNumber = phoneNumber,
                    callType = callType,
                    timestamp = startTimeMs,
                    durationMs = 0L,
                    fileSize = 0L
                )
            ).toInt()
            currentRecordId = recordId

            // Prepare MediaRecorder on IO threads
            val prepared = withContext(Dispatchers.IO) {
                prepareMediaRecorder(currentUri!!)
            }

            if (prepared) {
                try {
                    mediaRecorder?.start()
                    isRecordingLocal = true
                    Log.i(TAG, "MediaRecorder started successfully")
                    startDurationCounter()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MediaRecorder", e)
                    updateNotification("Recording Error: Mic is occupied", "Ensure speakerphone is used.")
                    // Clean up DB placeholder
                    db.callRecordDao().deleteRecordById(recordId)
                    stopSelfFlow()
                }
            } else {
                Log.e(TAG, "MediaRecorder failed preparation")
                updateNotification("Failed to initialize recorder", "")
                db.callRecordDao().deleteRecordById(recordId)
                stopSelfFlow()
            }
        }
    }

    private fun prepareMediaRecorder(uri: Uri): Boolean {
        return try {
            pfd = contentResolver.openFileDescriptor(uri, "rw")
            if (pfd == null) {
                Log.e(TAG, "ParcelFileDescriptor is null")
                return false
            }

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC) // Fallback for standard Call Recording stream
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(pfd!!.fileDescriptor)
                prepare()
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "IOException preparing MediaRecorder", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception preparing MediaRecorder", e)
            false
        }
    }

    private fun startDurationCounter() {
        durationJob?.cancel()
        _recordingDurationSec.value = 0
        durationJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                _recordingDurationSec.value += 1
            }
        }
    }

    private fun stopRecordingFlow() {
        if (!isRecordingLocal) {
            Log.w(TAG, "stopRecordingFlow: No active recording, stopping service")
            stopSelfFlow()
            return
        }

        durationJob?.cancel()
        isRecordingLocal = false
        _isRecording.value = false
        prefManager.isRecordingActive = false

        val durationMs = System.currentTimeMillis() - startTimeMs
        var finalSize = 0L

        // Stop MediaRecorder on Background Dispatcher
        serviceScope.launch(Dispatchers.IO) {
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping mediaRecorder", e)
            } finally {
                mediaRecorder = null
            }

            try {
                pfd?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ParcelFileDescriptor", e)
            } finally {
                pfd = null
            }

            // Finish MediaStore pending flag
            currentUri?.let { uri ->
                try {
                    val values = ContentValues().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Audio.Media.IS_PENDING, 0)
                        }
                    }
                    contentResolver.update(uri, values, null, null)

                    // Retrieve exact file size
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { assetFd ->
                        finalSize = assetFd.length
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizing MediaStore flags", e)
                }

                // Update final metadata in DB
                currentRecordId?.let { recordId ->
                    db.callRecordDao().updateDurationAndSize(recordId, durationMs, finalSize)
                    Log.i(TAG, "DB record $recordId finalized. Duration: $durationMs ms, Size: $finalSize bytes")
                }
            }

            withContext(Dispatchers.Main) {
                stopSelfFlow()
            }
        }
    }

    private fun stopSelfFlow() {
        _isRecording.value = false
        prefManager.isRecordingActive = false
        _activeCallNumber.value = null
        durationJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createMediaStoreAudioFile(callType: String, phoneNumber: String): Pair<Uri?, String>? {
        val resolver = contentResolver
        val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val timestamp = System.currentTimeMillis()
        val suffix = when (callType) {
            "INCOMING" -> "Incoming_${phoneNumber}"
            "OUTGOING" -> "Outgoing_${phoneNumber}"
            else -> "Manual"
        }
        val cleanSuffix = suffix.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val displayName = "CallRecord_${timestamp}_${cleanSuffix}.m4a"

        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Recordings/CallRecorder")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        return try {
            val uri = resolver.insert(audioCollection, contentValues)
            if (uri != null) {
                Pair(uri, displayName)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting file to MediaStore", e)
            null
        }
    }

    private fun createNotification(contentTitle: String, contentText: String): Notification {
        val pendingIntentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            pendingIntentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.presence_video_online) // Robust standard system icon to avoid crashes
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(title, text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Automatic Call Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows an ongoing status notification when call recording is active."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
