package com.plaintext.audible.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TrackType {
    SERIES, AUTHOR, NARRATOR, BOOK
}

enum class DndTargetType {
    BOOK, SERIES
}

@Entity(tableName = "tracked_items")
data class TrackedItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TrackType,
    val queryValue: String,          // Name of Author/Narrator/Series, or ASIN for Book
    val externalId: String? = null,  // Optional Audible Series ASIN or Book ASIN
    val lastReadSequence: Int = 0,   // Used by Series Gap Detector (Feature #5)
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "dnd_rules")
data class DndRuleEntity(
    @PrimaryKey val targetKey: String, // ASIN for Book, or normalized Series key/ASIN
    val targetType: DndTargetType,
    val displayTitle: String,
    val createdAt: Long = System.currentTimeMillis()
)

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
    val releaseDateIso: String,      // YYYY-MM-DD or "TBA"
    val language: String = "English",
    val isAbridged: Boolean = false,
    val isDramatized: Boolean = false,
    val matchedTags: String,         // e.g. "SERIES,AUTHOR,NARRATOR"
    val isNewDiscovery: Boolean = true,
    val notifiedSevenDay: Boolean = false,
    val notifiedReleaseDay: Boolean = false,
    val firstSeenEpochMs: Long = System.currentTimeMillis(),
    val coverUrl: String = "",       // 252px/500px square cover thumbnail URL
    val synopsis: String = "",       // Plain-text summary from product_desc
    val runtimeMinutes: Int = 0,     // Runtime length in minutes
    val publisher: String = "",      // Publisher name
    val altAsins: String = ""        // Comma-separated all edition ASINs (US + International)
)
