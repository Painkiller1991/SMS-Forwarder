package com.smsforwarder.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY pinned DESC, receivedAt DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE pinned = 1 OR receivedAt >= :dayStart
        ORDER BY pinned DESC, receivedAt DESC
        """
    )
    fun observeInbox(dayStart: Long): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE pinned = 0 AND receivedAt < :dayStart
        ORDER BY receivedAt DESC
        """
    )
    fun observeHistory(dayStart: Long): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    /** Unpinned messages from today only (main inbox Clear all). */
    @Query("DELETE FROM messages WHERE pinned = 0 AND receivedAt >= :dayStart")
    suspend fun clearTodayUnpinned(dayStart: Long)

    /** Older unpinned messages (History Delete all). */
    @Query("DELETE FROM messages WHERE pinned = 0 AND receivedAt < :dayStart")
    suspend fun clearHistory(dayStart: Long)

    @Query("DELETE FROM messages WHERE id = :id AND pinned = 0")
    suspend fun deleteById(id: Long)

    @Query("UPDATE messages SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)
}
