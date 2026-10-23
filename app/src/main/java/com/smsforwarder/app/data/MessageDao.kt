package com.smsforwarder.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY pinned DESC, receivedAt DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE pinned = 0")
    suspend fun clear()

    @Query("DELETE FROM messages WHERE id = :id AND pinned = 0")
    suspend fun deleteById(id: Long)

    @Query("UPDATE messages SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)
}
