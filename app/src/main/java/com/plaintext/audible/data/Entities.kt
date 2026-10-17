package com.plaintext.audible.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TrackType {
    SERIES, AUTHOR, NARRATOR, BOOK
}

enum class DndTargetType {
    BOOK, SERIES
}

@Immutable
@Entity(
    tableName = "tracked_items",
    indices = [Index(value = ["type", "queryValue"])]
)
data class TrackedItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TrackType,
    val queryValue: String,
    val externalId: String? = null,
    val lastReadSequence: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val thumbnailUrl: String = ""
)

@Immutable
@Entity(tableName = "dnd_rules")
data class DndRuleEntity(
    @PrimaryKey val targetKey: String,
    val targetType: DndTargetType,
    val displayTitle: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Immutable
@Entity(tableName = "releases")
data class ReleaseEntity(
    @PrimaryKey val asin: String,
    val title: String,
    val subtitle: String = "",
    val authors: String,
    val narrators: String,
    val seriesTitle: String = "",
    val seriesAsin: String = "",
    val seriesSequence: Int? = null,
    val releaseDateIso: String,
    val language: String = "English",
    val isAbridged: Boolean = false,
    val isDramatized: Boolean = false,
    val matchedTags: String,
    val isNewDiscovery: Boolean = true,
    val notifiedSevenDay: Boolean = false,
    val notifiedReleaseDay: Boolean = false,
    val firstSeenEpochMs: Long = System.currentTimeMillis(),
    val coverUrl: String = "",
    val synopsis: String = "",
    val runtimeMinutes: Int = 0,
    val publisher: String = "",
    val altAsins: String = ""
)
