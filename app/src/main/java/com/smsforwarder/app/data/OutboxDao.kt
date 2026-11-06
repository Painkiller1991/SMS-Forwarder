package com.smsforwarder.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: OutboxEntity): Long

    /** All pending rows, oldest SMS first. */
    @Query(
        """
        SELECT * FROM outbox
        WHERE state = 'pending'
        ORDER BY smsTimestamp ASC, createdAt ASC, id ASC
        """
    )
    suspend fun allPendingOrdered(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'pending'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query(
        """
        SELECT endpointId, COUNT(*) as cnt FROM outbox
        WHERE state = 'pending'
        GROUP BY endpointId
        """
    )
    suspend fun pendingCountsByEndpoint(): List<EndpointPendingCount>

    @Query(
        """
        SELECT endpointId, COUNT(*) as cnt FROM outbox
        WHERE state = 'pending'
        GROUP BY endpointId
        """
    )
    fun observePendingCountsByEndpoint(): Flow<List<EndpointPendingCount>>

    @Query(
        """
        SELECT * FROM outbox
        WHERE state = 'pending'
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun latestPendingError(): OutboxEntity?

    @Query(
        """
        SELECT * FROM outbox
        WHERE endpointId = :endpointId AND lastError IS NOT NULL AND length(lastError) > 0
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun latestErrorForEndpoint(endpointId: String): OutboxEntity?

    @Query(
        """
        SELECT MAX(createdAt) FROM outbox
        WHERE endpointId = :endpointId AND state IN ('synced', 'done')
        """
    )
    suspend fun lastSyncedAt(endpointId: String): Long?

    @Query(
        """
        UPDATE outbox SET state = 'synced', lastError = NULL
        WHERE id = :id
        """
    )
    suspend fun markSynced(id: Long)

    @Query(
        """
        UPDATE outbox SET attempts = :attempts, lastError = :lastError
        WHERE id = :id
        """
    )
    suspend fun markAttemptFailed(id: Long, attempts: Int, lastError: String?)

    @Query(
        """
        DELETE FROM outbox
        WHERE state IN ('synced', 'done', 'expired') AND createdAt < :before
        """
    )
    suspend fun purgeOld(before: Long)
}
