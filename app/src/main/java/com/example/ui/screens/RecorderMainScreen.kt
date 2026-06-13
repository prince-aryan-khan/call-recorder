package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.CallRecordEntity
import com.example.ui.viewmodel.CallRecorderViewModel
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderMainScreen(
    viewModel: CallRecorderViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isRecording by viewModel.isRecordingState.collectAsState()
    val activeCallNumber by viewModel.activeCallNumber.collectAsState()
    val recordingDurationSec by viewModel.recordingDurationSec.collectAsState()
    val isAutoRecordEnabled by viewModel.isAutoRecordEnabled.collectAsState()
    val callRecords by viewModel.callRecordsState.collectAsState()

    // Permission check state
    var permissionsGranted by remember {
        mutableStateOf(hasRequiredPermissions(context))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            val req = getRequiredPermissionsList().toTypedArray()
            launcher.launch(req)
        }
    }

    var showInfoDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "App logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Secure Call Recorder",
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.testTag("info_help_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Platform info",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        )
                    )
                )
                .padding(horizontal = 16.dp)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Dynamic Recording Hub / Pulse Wave Dashboard
                item {
                    RecordingDashboard(
                        isRecording = isRecording,
                        activeNumber = activeCallNumber,
                        durationSec = recordingDurationSec,
                        onStopRecording = { viewModel.stopRecording(context) },
                        onStartManual = { viewModel.startManualRecordingTest(context) }
                    )
                }

                // Section 2: Automated Settings, Battery Save Exclusions, & Permissions Alert
                item {
                    QuickControlPanel(
                        context = context,
                        isAutoOn = isAutoRecordEnabled,
                        onToggleAuto = { viewModel.toggleAutoRecord() },
                        permissionsOk = permissionsGranted,
                        onRequestPermissions = {
                            launcher.launch(getRequiredPermissionsList().toTypedArray())
                        },
                        onOptimizeSettings = { viewModel.openBatteryOptimizationSettings(context) },
                        viewModel = viewModel
                    )
                }

                // Section 3: Call Records Title
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recording History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = "${callRecords.size} files",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Section 4: History list view
                if (callRecords.isEmpty()) {
                    item {
                        EmptyState()
                    }
                } else {
                    items(callRecords, key = { it.id }) { record ->
                        RecordingHistoryItem(
                            record = record,
                            viewModel = viewModel,
                            context = context
                        )
                    }
                }
            }
        }
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Android Platform Call Note", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Due to modern Android security and access limits (Android 9 to 16), direct voice call audio stream capturing is restricted for third-party apps on stock systems.\n\n" +
                    "To guarantee maximum reliability, this app uses standard high-quality microphone input (MIC). For best results recording caller voices, please ALWAYS enable Speakerphone during active calls.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Got It")
                }
            }
        )
    }
}

// Sub-component 1: pulse wave dynamic panel
@Composable
fun RecordingDashboard(
    isRecording: Boolean,
    activeNumber: String?,
    durationSec: Int,
    onStopRecording: () -> Unit,
    onStartManual: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scaleMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recording_dashboard_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecording) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isRecording) {
                // Recording Active UI State
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .scale(scaleMultiplier)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                    )
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Mic active",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "RECORDING ACTIVE",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp
                )

                Text(
                    text = formatDuration(durationSec * 1000L),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = activeNumber ?: "Active Call Session...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Button(
                    onClick = onStopRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stop_recording_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop icon",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Stop Recording", fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Recording Idle State UI
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    )
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Phone monitoring idle",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "MONITORING ACTIVE",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp
                )

                Text(
                    text = "Idle",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = "Will trigger automatically when voice calls start",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
                )

                Button(
                    onClick = onStartManual,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_test_record_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play icon",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Start Manual Test Recording", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// Sub-component 2: settings card holding auto record toggle and optimize battery link
@Composable
fun QuickControlPanel(
    context: Context,
    isAutoOn: Boolean,
    onToggleAuto: () -> Unit,
    permissionsOk: Boolean,
    onRequestPermissions: () -> Unit,
    onOptimizeSettings: () -> Unit,
    viewModel: CallRecorderViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Row 1: Auto Enable/Disable Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleAuto() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isAutoOn) Icons.Default.SettingsSuggest else Icons.Default.Settings,
                        contentDescription = "Auto Trigger",
                        tint = if (isAutoOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Auto Call Recording",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (isAutoOn) "Armed to trigger on calls" else "Manually controlled only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = isAutoOn,
                    onCheckedChange = { onToggleAuto() },
                    modifier = Modifier.testTag("auto_record_switch")
                )
            }

            Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            // Row 2: Runtime Permission Checklist Warning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (!permissionsOk) onRequestPermissions() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (permissionsOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Permissions Status",
                        tint = if (permissionsOk) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Permission Handshakes",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (permissionsOk) "Recordings enabled & fully permitted" else "Tap here to request missing permissions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!permissionsOk) {
                    IconButton(
                        onClick = onRequestPermissions,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.1f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Expand Permission request",
                            tint = Color(0xFFFF9800)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Permitted",
                        tint = Color(0xFF4CAF50)
                    )
                }
            }

            // Power Management Exclusion shortcut
            var batteryExempt by remember {
                mutableStateOf(viewModel.checkPowerOptimization(context))
            }

            // Simple lifecycle check to update optimization state
            var clickPollToggle by remember { mutableStateOf(false) }
            LaunchedEffect(clickPollToggle) {
                batteryExempt = viewModel.checkPowerOptimization(context)
            }

            Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOptimizeSettings()
                        clickPollToggle = !clickPollToggle
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryChargingFull,
                        contentDescription = "Battery optimization limiters",
                        tint = if (batteryExempt) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Background Protection",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (batteryExempt) "Battery saving exclusions validated" else "Tap to safeguard service from system kill",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = {
                        onOptimizeSettings()
                        clickPollToggle = !clickPollToggle
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Battery Saver Settings link",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// Sub-component 3: Empty history place card
@Composable
fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.AudioFile,
            contentDescription = "Empty History",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Call Recordings Yet",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        Text(
            text = "Initiate an incoming or outgoing call, or start a Manual sample recording list",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp)
        )
    }
}

// Sub-component 4: Record historic list element
@Composable
fun RecordingHistoryItem(
    record: CallRecordEntity,
    viewModel: CallRecorderViewModel,
    context: Context
) {
    val isPlayingFlow by viewModel.isPlaying.collectAsState()
    val playingRecordIdFlow by viewModel.playingRecordId.collectAsState()
    val playbackProgressFlow by viewModel.playbackProgress.collectAsState()

    val isThisPlaying = playingRecordIdFlow == record.id
    val isPlaying = isThisPlaying && isPlayingFlow

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recording_item_${record.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isThisPlaying) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Call type indicator icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            when (record.callType) {
                                "INCOMING" -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                                "OUTGOING" -> Color(0xFF2196F3).copy(alpha = 0.15f)
                                else -> Color(0xFFFF9800).copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (record.callType) {
                            "INCOMING" -> Icons.Default.CallReceived
                            "OUTGOING" -> Icons.Default.CallMade
                            else -> Icons.Default.Mic
                        },
                        contentDescription = "Direction icon",
                        tint = when (record.callType) {
                            "INCOMING" -> Color(0xFF4CAF50)
                            "OUTGOING" -> Color(0xFF2196F3)
                            else -> Color(0xFFFF9800)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (record.phoneNumber == "Private Number" || record.phoneNumber.isBlank()) "Unknown Caller" else record.phoneNumber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = record.callType,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (record.callType) {
                                "INCOMING" -> Color(0xFF4CAF50)
                                "OUTGOING" -> Color(0xFF2196F3)
                                else -> Color(0xFFFF9800)
                            }
                        )
                        Text(
                            text = "•",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = formatDate(record.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.shareRecord(context, record) },
                        modifier = Modifier.testTag("share_button_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share recording icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.deleteRecord(context, record) },
                        modifier = Modifier.testTag("delete_button_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete recording icon",
                            tint = Color(0xFFF44336),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sub row of file parameters and audio feedback
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play-pause icon
                IconButton(
                    onClick = { viewModel.playPauseRecord(context, record) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isThisPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("play_pause_${record.id}")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Control icon",
                        tint = if (isThisPlaying) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                if (isThisPlaying) {
                    // Playback progress slider
                    Slider(
                        value = playbackProgressFlow,
                        onValueChange = {},
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Audio sound output",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    // Total static parameters
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDuration(record.durationMs),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = formatSize(record.fileSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// Helpers for formatted displays
@SuppressLint("SimpleDateFormat")
private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy h:mm a")
    return sdf.format(Date(timestamp))
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0.0 KB"
    val df = DecimalFormat("#,##0.0")
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        "${df.format(mb)} MB"
    } else {
        "${df.format(kb)} KB"
    }
}

private fun getRequiredPermissionsList(): List<String> {
    val list = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return list
}

private fun hasRequiredPermissions(context: Context): Boolean {
    return getRequiredPermissionsList().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
