package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<CallRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: CallRecordEntity): Long

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteRecordById(id: Int)

    @Query("SELECT * FROM call_records WHERE id = :id")
    suspend fun getRecordById(id: Int): CallRecordEntity?

    @Query("UPDATE call_records SET durationMs = :durationMs, fileSize = :fileSize WHERE id = :id")
    suspend fun updateDurationAndSize(id: Int, durationMs: Long, fileSize: Long)

    @Update
    suspend fun updateRecord(record: CallRecordEntity)
}
