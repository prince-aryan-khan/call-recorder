package com.example.data

import kotlinx.coroutines.flow.Flow

class CallRecordRepository(private val callRecordDao: CallRecordDao) {
    val allRecords: Flow<List<CallRecordEntity>> = callRecordDao.getAllRecords()

    suspend fun insertRecord(record: CallRecordEntity): Long {
        return callRecordDao.insertRecord(record)
    }

    suspend fun deleteRecordById(id: Int) {
        callRecordDao.deleteRecordById(id)
    }

    suspend fun getRecordById(id: Int): CallRecordEntity? {
        return callRecordDao.getRecordById(id)
    }

    suspend fun updateDurationAndSize(id: Int, durationMs: Long, fileSize: Long) {
        callRecordDao.updateDurationAndSize(id, durationMs, fileSize)
    }

    suspend fun updateRecord(record: CallRecordEntity) {
        callRecordDao.updateRecord(record)
    }
}
