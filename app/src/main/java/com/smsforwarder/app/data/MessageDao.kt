package com.smsforwarder.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages")
    suspend fun clear()
}
