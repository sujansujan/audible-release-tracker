package com.plaintext.audible.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackerDao {
    @Query("SELECT * FROM tracked_items ORDER BY type, queryValue ASC")
    fun observeTrackedItems(): Flow<List<TrackedItemEntity>>

    @Query("SELECT * FROM tracked_items")
    suspend fun getAllTrackedItems(): List<TrackedItemEntity>

    @Query(
        "SELECT * FROM tracked_items WHERE type = :type AND LOWER(TRIM(queryValue)) = LOWER(TRIM(:queryValue)) LIMIT 1"
    )
    suspend fun findTrackedItem(type: TrackType, queryValue: String): TrackedItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackedItem(item: TrackedItemEntity): Long

    @Delete
    suspend fun deleteTrackedItem(item: TrackedItemEntity)

    @Query("UPDATE tracked_items SET lastReadSequence = :seq WHERE id = :id")
    suspend fun updateSeriesReadSequence(id: Long, seq: Int)

    @Query("SELECT * FROM dnd_rules ORDER BY createdAt DESC")
    fun observeDndRules(): Flow<List<DndRuleEntity>>

    @Query("SELECT * FROM dnd_rules")
    suspend fun getAllDndRules(): List<DndRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDndRule(rule: DndRuleEntity)

    @Delete
    suspend fun deleteDndRule(rule: DndRuleEntity)

    @Query("SELECT * FROM releases ORDER BY releaseDateIso ASC, title ASC")
    fun observeReleases(): Flow<List<ReleaseEntity>>

    @Query("SELECT * FROM releases")
    suspend fun getAllReleases(): List<ReleaseEntity>

    @Query("DELETE FROM releases")
    suspend fun clearAllReleases()

    @Query("DELETE FROM releases WHERE asin NOT IN (:validAsins)")
    suspend fun deleteReleasesNotIn(validAsins: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReleases(releases: List<ReleaseEntity>)

    @Query("DELETE FROM releases WHERE asin = :asin")
    suspend fun deleteReleaseByAsin(asin: String)

    @Query("DELETE FROM releases WHERE LOWER(seriesTitle) = LOWER(:seriesKey) OR seriesAsin = :seriesKey")
    suspend fun deleteReleasesBySeries(seriesKey: String)

    @Query("UPDATE releases SET isNewDiscovery = 0 WHERE isNewDiscovery = 1")
    suspend fun clearNewDiscoveryFlags()

    @Query("UPDATE releases SET notifiedSevenDay = :sevenDay, notifiedReleaseDay = :releaseDay WHERE asin = :asin")
    suspend fun updateNotificationFlags(asin: String, sevenDay: Boolean, releaseDay: Boolean)
}

@Database(
    entities = [TrackedItemEntity::class, DndRuleEntity::class, ReleaseEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TrackerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE releases ADD COLUMN coverUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE releases ADD COLUMN synopsis TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE releases ADD COLUMN runtimeMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE releases ADD COLUMN publisher TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE releases ADD COLUMN altAsins TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove any duplicate tracked items (keep earliest row per type + case-insensitive queryValue)
                db.execSQL(
                    "DELETE FROM tracked_items WHERE id NOT IN (" +
                        "SELECT MIN(id) FROM tracked_items GROUP BY type, LOWER(TRIM(queryValue))" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracked_items_type_queryValue ON tracked_items(type, queryValue)"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "plaintext_audible.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
