package com.smsforwarder.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: OutboxEntity): Long

    @Query(
        """
        SELECT * FROM outbox
        WHERE state = 'pending' AND nextAttemptAt <= :now
        ORDER BY createdAt ASC
        LIMIT :limit
        """
    )
    suspend fun duePending(now: Long, limit: Int = 32): List<OutboxEntity>

    @Query(
        """
        UPDATE outbox SET state = 'done', lastError = NULL
        WHERE id = :id
        """
    )
    suspend fun markDone(id: Long)

    @Query(
        """
        UPDATE outbox SET attempts = :attempts, nextAttemptAt = :nextAttemptAt,
        lastError = :lastError, state = :state
        WHERE id = :id
        """
    )
    suspend fun updateAttempt(
        id: Long,
        attempts: Int,
        nextAttemptAt: Long,
        lastError: String?,
        state: String
    )

    @Query("DELETE FROM outbox WHERE state IN ('done', 'expired') AND createdAt < :before")
    suspend fun purgeOld(before: Long)

    @Query(
        """
        SELECT MIN(nextAttemptAt) FROM outbox
        WHERE state = 'pending'
        """
    )
    suspend fun nextPendingAt(): Long?
}
