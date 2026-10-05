package com.example.audiblereleases.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "follows", primaryKeys = ["kind", "value"])
data class Follow(@ColumnInfo val kind: String, @ColumnInfo val value: String)

@Entity(tableName = "releases")
data class ReleaseEntity(@PrimaryKey val asin: String, val title: String, val author: String, val narrator: String, val series: String, val length: String, val releaseDate: String, val url: String, val synopsis: String = "", val firstSeen: Long = System.currentTimeMillis())

@Dao interface FollowDao {
    @Query("SELECT * FROM follows ORDER BY kind, value") fun observeAll(): Flow<List<Follow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun add(item: Follow)
    @Delete suspend fun remove(item: Follow)
}

@Dao interface ReleaseDao {
    @Query("SELECT * FROM releases ORDER BY releaseDate") fun observeAll(): Flow<List<ReleaseEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<ReleaseEntity>)
}

@Database(entities = [Follow::class, ReleaseEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun follows(): FollowDao
    abstract fun releases(): ReleaseDao
    companion object { @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) { INSTANCE ?: Room.databaseBuilder(context, AppDatabase::class.java, "audible-releases.db").addMigrations(MIGRATION_1_2).build().also { INSTANCE = it } }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) { database.execSQL("ALTER TABLE releases ADD COLUMN synopsis TEXT NOT NULL DEFAULT ''") }
        }
    }
}
