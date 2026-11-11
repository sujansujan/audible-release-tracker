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

    @Query("DELETE FROM tracked_items WHERE id = :id")
    suspend fun deleteTrackedItemById(id: Long)

    @Query("UPDATE tracked_items SET lastReadSequence = :seq WHERE id = :id")
    suspend fun updateSeriesReadSequence(id: Long, seq: Int)

    @Query("UPDATE tracked_items SET thumbnailUrl = :url WHERE id = :id")
    suspend fun updateTrackedItemThumbnail(id: Long, url: String)

    @Query("UPDATE tracked_items SET queryValue = :queryValue, externalId = :extId, thumbnailUrl = :url WHERE id = :id")
    suspend fun updateTrackedItemDetails(id: Long, queryValue: String, extId: String?, url: String)

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

    @Transaction
    suspend fun replaceSyncedReleases(releases: List<ReleaseEntity>) {
        if (releases.isEmpty()) {
            clearAllReleases()
        } else {
            upsertReleases(releases)
            deleteReleasesNotIn(releases.map { it.asin })
        }
    }

    @Query("DELETE FROM releases WHERE asin = :asin")
    suspend fun deleteReleaseByAsin(asin: String)

    @Query("DELETE FROM releases WHERE LOWER(seriesTitle) = LOWER(:seriesKey) OR seriesAsin = :seriesKey")
    suspend fun deleteReleasesBySeries(seriesKey: String)

    @Query("UPDATE releases SET isNewDiscovery = 0, narratorJustAnnounced = 0 WHERE isNewDiscovery = 1 OR narratorJustAnnounced = 1")
    suspend fun clearNewDiscoveryFlags()

    @Query("UPDATE releases SET notifiedSevenDay = :sevenDay, notifiedReleaseDay = :releaseDay WHERE asin = :asin")
    suspend fun updateNotificationFlags(asin: String, sevenDay: Boolean, releaseDay: Boolean)

    @Query("UPDATE releases SET abbAvailable = :available, abbPostUrl = :postUrl, abbMagnetUri = :magnetUri WHERE asin = :asin")
    suspend fun updateAbbAvailability(asin: String, available: Boolean, postUrl: String, magnetUri: String)

    @Query("UPDATE releases SET notifiedAbbAvailable = :notified WHERE asin = :asin")
    suspend fun updateAbbNotifiedFlag(asin: String, notified: Boolean)
}

@Database(
    entities = [TrackedItemEntity::class, DndRuleEntity::class, ReleaseEntity::class],
    version = 7,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracked_items ADD COLUMN thumbnailUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE releases ADD COLUMN previousReleaseDateIso TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE releases ADD COLUMN narratorJustAnnounced INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE releases ADD COLUMN abbAvailable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE releases ADD COLUMN abbPostUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE releases ADD COLUMN abbMagnetUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE releases ADD COLUMN notifiedAbbAvailable INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Reset any false-positive ABB matches from v2.7/v2.8 so they are re-verified with strict author + Posted date rules
                db.execSQL(
                    "UPDATE releases SET abbAvailable = 0, abbPostUrl = '', abbMagnetUri = '', notifiedAbbAvailable = 0"
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
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
