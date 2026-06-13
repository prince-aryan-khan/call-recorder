package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.CallRecordEntity
import com.example.data.CallRecordRepository
import com.example.data.PrefManager
import com.example.service.CallRecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CallRecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository = CallRecordRepository(db.callRecordDao())
    private val prefManager = PrefManager(application)

    // Exposed DB state
    val callRecordsState: StateFlow<List<CallRecordEntity>> = repository.allRecords
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Preference States
    private val _isAutoRecordEnabled = MutableStateFlow(prefManager.isAutoRecordEnabled)
    val isAutoRecordEnabled: StateFlow<Boolean> = _isAutoRecordEnabled.asStateFlow()

    // Service state collectors (mirrors the static flows from CallRecordingService)
    val isRecordingState: StateFlow<Boolean> = CallRecordingService.isRecording
    val activeCallNumber: StateFlow<String?> = CallRecordingService.activeCallNumber
    val recordingDurationSec: StateFlow<Int> = CallRecordingService.recordingDurationSec

    // Audio Playback states
    private var mediaPlayer: MediaPlayer? = null
    
    private val _playingRecordId = MutableStateFlow<Int?>(null)
    val playingRecordId: StateFlow<Int?> = _playingRecordId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private var progressJob: kotlinx.coroutines.Job? = null

    fun toggleAutoRecord() {
        val newVal = !prefManager.isAutoRecordEnabled
        prefManager.isAutoRecordEnabled = newVal
        _isAutoRecordEnabled.value = newVal
    }

    fun startManualRecordingTest(context: Context) {
        val intent = Intent(context, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_START_RECORDING
            putExtra(CallRecordingService.EXTRA_CALL_TYPE, "MANUAL")
            putExtra(CallRecordingService.EXTRA_PHONE_NUMBER, "Manual Sample Test")
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("CallRecorderViewModel", "Error starting manual recording service", e)
        }
    }

    fun stopRecording(context: Context) {
        val intent = Intent(context, CallRecordingService::class.java).apply {
            action = CallRecordingService.ACTION_STOP_RECORDING
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("CallRecorderViewModel", "Error stopping recording service", e)
        }
    }

    fun playPauseRecord(context: Context, record: CallRecordEntity) {
        if (_playingRecordId.value == record.id) {
            if (_isPlaying.value) {
                // Pause
                try {
                    mediaPlayer?.pause()
                    _isPlaying.value = false
                    progressJob?.cancel()
                } catch (e: Exception) {
                    Log.e("CallRecorderViewModel", "Error pausing playback", e)
                }
            } else {
                // Resume
                try {
                    mediaPlayer?.start()
                    _isPlaying.value = true
                    startPlaybackProgressTracker()
                } catch (e: Exception) {
                    Log.e("CallRecorderViewModel", "Error resuming playback", e)
                }
            }
        } else {
            // Play a new record
            stopPlayback()
            _playingRecordId.value = record.id
            
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, Uri.parse(record.fileUri))
                    prepare()
                    start()
                    setOnCompletionListener {
                        stopPlayback()
                    }
                }
                _isPlaying.value = true
                startPlaybackProgressTracker()
            } catch (e: Exception) {
                Log.e("CallRecorderViewModel", "Error initiating audio source playback", e)
                stopPlayback()
            }
        }
    }

    private fun startPlaybackProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch(Dispatchers.Main) {
            val player = mediaPlayer ?: return@launch
            while (player.isPlaying) {
                try {
                    val duration = player.duration.toFloat()
                    if (duration > 0) {
                        _playbackProgress.value = player.currentPosition.toFloat() / duration
                    }
                } catch (e: Exception) {
                    // Ignore transient exceptions during state checks
                }
                kotlinx.coroutines.delay(200L)
            }
        }
    }

    fun stopPlayback() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e("CallRecorderViewModel", "Error releasing MediaPlayer", e)
        } finally {
            mediaPlayer = null
        }
        _playingRecordId.value = null
        _isPlaying.value = false
        _playbackProgress.value = 0f
    }

    fun deleteRecord(context: Context, record: CallRecordEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // Stop playback if deleting active playing item
            if (_playingRecordId.value == record.id) {
                withContext(Dispatchers.Main) {
                    stopPlayback()
                }
            }

            // Delete from Room
            repository.deleteRecordById(record.id)

            // Delete physically from MediaStore
            try {
                val resolver = context.contentResolver
                val fileUri = Uri.parse(record.fileUri)
                resolver.delete(fileUri, null, null)
                Log.i("CallRecorderViewModel", "Physically deleted Media Store record uri: $fileUri")
            } catch (e: SecurityException) {
                Log.w("CallRecorderViewModel", "Expected SecurityException on certain devices during MediaStore physical delete. Row was removed from history catalog, which is fine.", e)
            } catch (e: Exception) {
                Log.e("CallRecorderViewModel", "Exception physically deleting file from MediaStore", e)
            }
        }
    }

    fun shareRecord(context: Context, record: CallRecordEntity) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(record.fileUri))
                putExtra(Intent.EXTRA_TITLE, record.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Recording"))
        } catch (e: Exception) {
            Log.e("CallRecorderViewModel", "Exception sharing record", e)
        }
    }

    fun checkPowerOptimization(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val packageName = context.packageName
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:$packageName")
                    }
                } else {
                    action = Settings.ACTION_SETTINGS
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                // Generic battery save screen
                val bIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                context.startActivity(bIntent)
            } catch (ex: Exception) {
                // Master settings
                val settingsIntent = Intent(Settings.ACTION_SETTINGS)
                context.startActivity(settingsIntent)
            }
        }
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
