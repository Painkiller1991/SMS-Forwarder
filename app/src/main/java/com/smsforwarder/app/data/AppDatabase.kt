package com.smsforwarder.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, OutboxEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN msgId TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_msgId ON messages(msgId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        msgId TEXT NOT NULL,
                        endpointId TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        secret TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        body TEXT NOT NULL,
                        smsTimestamp INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        attempts INTEGER NOT NULL,
                        nextAttemptAt INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        lastError TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_outbox_state_nextAttemptAt ON outbox(state, nextAttemptAt)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_outbox_msgId_endpointId ON outbox(msgId, endpointId)"
                )
            }
        }

        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sms_forwarder.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .enableMultiInstanceInvalidation()
                .build()
        }
    }
}
