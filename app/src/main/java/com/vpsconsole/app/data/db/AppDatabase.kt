package com.vpsconsole.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vpsconsole.app.data.model.AlertEventEntity
import com.vpsconsole.app.data.model.AlertRuleEntity
import com.vpsconsole.app.data.model.CommandEntity
import com.vpsconsole.app.data.model.KnownHostEntity
import com.vpsconsole.app.data.model.MetricEntity
import com.vpsconsole.app.data.model.NoteEntity
import com.vpsconsole.app.data.model.ServerProfile
import com.vpsconsole.app.data.model.TunnelEntity

@Database(
    entities = [
        ServerProfile::class,
        MetricEntity::class,
        AlertRuleEntity::class,
        AlertEventEntity::class,
        CommandEntity::class,
        KnownHostEntity::class,
        TunnelEntity::class,
        NoteEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun metricDao(): MetricDao
    abstract fun alertDao(): AlertDao
    abstract fun commandDao(): CommandDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun tunnelDao(): TunnelDao
    abstract fun noteDao(): NoteDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tunnels ADD COLUMN remoteBindHost TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_rules ADD COLUMN isServerThreshold INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS notes (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, " +
                        "`tags` TEXT NOT NULL, " +
                        "`serverId` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "vps-console.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
