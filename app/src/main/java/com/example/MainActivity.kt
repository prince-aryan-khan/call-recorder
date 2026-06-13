package com.example

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.BackgroundSlate
import com.example.ui.theme.DeepSlate
import com.example.ui.theme.GrayText
import com.example.ui.theme.LightSlate
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.OnNeonCyan
import com.example.ui.theme.SurfaceSlate
import com.example.ui.theme.White
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BackgroundSlate)
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// Media Representation Data Model
data class RecordingLog(
    val id: Long,
    val name: String,
    val duration: String,
    val dateAdded: String,
    val size: String,
    val uriString: String
)

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Gather Live Stream States from Foreground Service
    val isServiceRunning by CallRecordService.isServiceRunning.collectAsState()
    val isRecording by CallRecordService.isRecording.collectAsState()
    val serviceStatus by CallRecordService.serviceStatus.collectAsState()

    var logsList by remember { mutableStateOf<List<RecordingLog>>(emptyList()) }
    var playingUriString by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Track state of permissions natively in Compose
    var permissionsGranted by remember {
        mutableStateOf(
            checkSinglePermission(context, Manifest.permission.RECORD_AUDIO) &&
                    checkSinglePermission(context, Manifest.permission.READ_PHONE_STATE) &&
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        checkSinglePermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    } else true
        )
    }

    val permissionsList = remember {
        mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            Toast.makeText(context, "Permissions Ready", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissions are required for operation", Toast.LENGTH_LONG).show()
        }
    }

    // Refresh audio list helper
    fun refreshAudioLogs() {
        logsList = queryAudioRecordings(context)
    }

    // Playback Controller Helper
    fun togglePlay(log: RecordingLog) {
        if (playingUriString == log.uriString) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            playingUriString = null
        } else {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, Uri.parse(log.uriString))
                    prepare()
                    start()
                    setOnCompletionListener {
                        playingUriString = null
                        mediaPlayer = null
                    }
                }
                playingUriString = log.uriString
            } catch (e: Exception) {
                Log.e("MainActivity", "Playback failed: ${e.message}")
                Toast.makeText(context, "Playback error or blank recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Dynamic initial loading triggers
    LaunchedEffect(isRecording, isServiceRunning) {
        refreshAudioLogs()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundSlate)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title/Brand header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "CALL RECORDER",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = NeonCyan,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Android 16 Secure Hub",
                    fontSize = 12.sp,
                    color = GrayText
                )
            }

            // Power button to toggle continuous guard service
            Button(
                onClick = {
                    if (isServiceRunning) {
                        val stopIntent = Intent(context, CallRecordService::class.java).apply {
                            action = CallRecordService.ACTION_STOP_SERVICE
                        }
                        context.stopService(stopIntent)
                    } else {
                        if (!permissionsGranted) {
                            permissionsLauncher.launch(permissionsList)
                        } else {
                            val startIntent = Intent(context, CallRecordService::class.java).apply {
                                action = CallRecordService.ACTION_START_SERVICE
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(startIntent)
                            } else {
                                context.startService(startIntent)
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) Color(0xFFFF453A) else NeonCyan,
                    contentColor = if (isServiceRunning) White else OnNeonCyan
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("toggle_guard_button")
            ) {
                Text(
                    text = if (isServiceRunning) "OFF" else "ON",
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // STATUS PORTAL PANEL (Idle Guard status header)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceSlate),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SYSTEM STATUS",
                    fontSize = 11.sp,
                    color = GrayText,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Pulsing indicator light
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) Color.Red else if (isServiceRunning) NeonCyan else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRecording) "RECORDING IN PROGRESS" else if (isServiceRunning) "IDLE GUARD ACTIVE" else "GUARD NOT RUNNING",
                        fontSize = 15.sp,
                        color = if (isRecording) Color.Red else if (isServiceRunning) NeonCyan else White,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = serviceStatus,
                    color = White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // UI WARNING: If permissions not granted, overlay clean reminder
        AnimatedVisibility(visible = !permissionsGranted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0x33FF453A)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning icon",
                        tint = Color(0xFFFF453A)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Permission Action Required",
                            fontWeight = FontWeight.Bold,
                            color = White,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Grant Record, Phone, & Notifications to run",
                            color = GrayText,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = { permissionsLauncher.launch(permissionsList) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("GRANT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = White)
                    }
                }
            }
        }

        // CONTROL STATION WORKFLOWS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // "START MANUAL RECORD TEST" high-visibility trigger
            Button(
                onClick = {
                    if (!permissionsGranted) {
                        permissionsLauncher.launch(permissionsList)
                        return@Button
                    }
                    // Start service if inactive
                    if (!isServiceRunning) {
                        val initIntent = Intent(context, CallRecordService::class.java).apply {
                            action = CallRecordService.ACTION_START_SERVICE
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(initIntent)
                        } else {
                            context.startService(initIntent)
                        }
                    }
                    // Toggle Recording action command
                    val recordIntent = Intent(context, CallRecordService::class.java).apply {
                        action = if (isRecording) {
                            CallRecordService.ACTION_MANUAL_RECORD_STOP
                        } else {
                            CallRecordService.ACTION_MANUAL_RECORD_START
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(recordIntent)
                    } else {
                        context.startService(recordIntent)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("manual_test_record_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color(0xFFFF453A) else NeonCyan,
                    contentColor = if (isRecording) White else OnNeonCyan
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isRecording) "STOP MANUAL TEST" else "START MANUAL RECORD TEST",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            // EXCLUDE BATTERY OPTIMIZATIONS BUTTON
            Button(
                onClick = {
                    val pManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val ignored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        pManager.isIgnoringBatteryOptimizations(context.packageName)
                    } else true

                    if (ignored) {
                        Toast.makeText(context, "App already excluded from battery restrictions!", Toast.LENGTH_SHORT).show()
                    } else {
                        val batIntent = Intent().apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                data = Uri.parse("package:${context.packageName}")
                            }
                        }
                        try {
                            context.startActivity(batIntent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "System battery settings launched", Toast.LENGTH_SHORT).show()
                            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            try {
                                context.startActivity(fallbackIntent)
                            } catch (fallbackEx: Exception) {
                                Log.e("BatterySettings", "Failed battery launches: ${fallbackEx.message}")
                            }
                        }
                    }
                },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("battery_optimization_button"),
                colors = ButtonDefaults.buttonColors(containerColor = LightSlate, contentColor = White),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("OPTIMIZE FIX", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // HEADER INDEX FOR RECORDINGS LIST
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "SAVED CALL LOGS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan,
                letterSpacing = 1.sp
            )
            Text(
                text = "REFRESH",
                fontSize = 11.sp,
                color = GrayText,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { refreshAudioLogs() }
                    .padding(4.dp)
            )
        }

        HorizontalDivider(color = LightSlate, thickness = 1.dp)

        Spacer(modifier = Modifier.height(8.dp))

        // STREAM LAZY LIST
        if (logsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No saved logs detected",
                        fontSize = 14.sp,
                        color = GrayText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Outputs will appear here when call state triggers a standard recording stream.",
                        textAlign = TextAlign.Center,
                        fontSize = 11.sp,
                        color = GrayText.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logsList) { log ->
                    RecordingLogItem(
                        log = log,
                        isPlaying = playingUriString == log.uriString,
                        onPlayToggle = { togglePlay(log) }
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingLogItem(
    log: RecordingLog,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("call_log_item_${log.id}")
            .border(
                width = 1.dp,
                color = if (isPlaying) NeonCyan else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = SurfaceSlate),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Visual play indicator mic/audio circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) NeonCyan else LightSlate),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPlaying) "■" else "▶",
                    color = if (isPlaying) OnNeonCyan else White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onPlayToggle() }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Text data details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (isPlaying) NeonCyan else White,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = log.duration, fontSize = 11.sp, color = GrayText)
                    Text(text = log.size, fontSize = 11.sp, color = GrayText)
                    Text(text = log.dateAdded, fontSize = 11.sp, color = GrayText)
                }
            }
        }
    }
}

// Check single permission status helper
fun checkSinglePermission(context: Context, permissionName: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
}

// MediaStore Query Implementation
fun queryAudioRecordings(context: Context): List<RecordingLog> {
    val results = mutableListOf<RecordingLog>()
    val mediaUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.SIZE
    )

    // Select files originating from our App pattern starts with "Call_Record_"
    val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
    val selectionArgs = arrayOf("Call_Record_%")
    val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

    try {
        context.contentResolver.query(
            mediaUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val durationMs = cursor.getLong(durationColumn)
                val dateAddedSec = cursor.getLong(dateAddedColumn)
                val sizeBytes = cursor.getLong(sizeColumn)

                val fileUri = ContentUris.withAppendedId(mediaUri, id)

                results.add(
                    RecordingLog(
                        id = id,
                        name = name,
                        duration = formatDurationString(durationMs),
                        dateAdded = formatDateString(dateAddedSec),
                        size = formatSizeString(sizeBytes),
                        uriString = fileUri.toString()
                    )
                )
            }
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "Error querying MediaStore database: ${e.message}")
    }

    return results
}

// String metadata formatters
private fun formatSizeString(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatDurationString(ms: Long): String {
    if (ms <= 0) return "00:00"
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    val hours = (ms / (1000 * 60 * 60)) % 24
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatDateString(seconds: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(seconds * 1000))
}
