package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "call_records")
data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileName: String,
    val fileUri: String,
    val phoneNumber: String,
    val callType: String, // INCOMING, OUTGOING, MANUAL
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val fileSize: Long = 0L
)
