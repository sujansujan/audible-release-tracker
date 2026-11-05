package com.plaintext.audible.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
<<<<<<< HEAD
import kotlinx.coroutines.delay
=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class AudibleMarketplace(val code: String, val domain: String, val label: String) {
    US("US", "audible.com", "US (.com)"),
    IN("IN", "audible.in", "IN (.in)"),
    UK("UK", "audible.co.uk", "UK (.co.uk)"),
    CA("CA", "audible.ca", "CA (.ca)"),
    AU("AU", "audible.com.au", "AU (.com.au)");

    companion object {
        fun fromCode(code: String): AudibleMarketplace =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: US
    }
}

enum class ThemeMode(val code: String, val label: String) {
    SYSTEM("SYSTEM", "SYSTEM AUTO"),
    DARK("DARK", "DRACULA"),
    LIGHT("LIGHT", "ALUCARD");

    companion object {
        fun fromCode(code: String): ThemeMode =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
    }
}

@Immutable
data class SyncTelemetry(
    val lastSyncEpochMs: Long = 0L,
    val lastSyncSourcesCount: Int = 0,
    val lastSyncErrorCount: Int = 0,
    val lastSyncUsedCachedFallback: Boolean = false
) {
    val formattedLine: String
        get() {
            if (lastSyncEpochMs <= 0L) {
                return "Last Synced: Never"
            }
            val dt = Instant.ofEpochMilli(lastSyncEpochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
            val statusPart = when {
                lastSyncUsedCachedFallback -> "OFFLINE CACHED ($lastSyncErrorCount err)"
                lastSyncErrorCount > 0 -> "$lastSyncErrorCount net err"
                else -> "0 errors"
            }
            return "Last Synced: ${dt.format(fmt)} ($lastSyncSourcesCount sources • $statusPart)"
        }
}

@Immutable
data class FilterPrefs(
    val englishOnly: Boolean = true,
    val hideAbridged: Boolean = true,
    val hideDramatized: Boolean = false,
    val enableDirectAdd: Boolean = false,
    val alwaysShowCardActions: Boolean = false,
<<<<<<< HEAD
    val checkAbbDaily: Boolean = true,
=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
    val themeModeCode: String = "SYSTEM",
    val notifyHour: Int = 9,
    val notifyMinute: Int = 0,
    val marketplaceCode: String = "US"
) {
    val marketplace: AudibleMarketplace
        get() = AudibleMarketplace.fromCode(marketplaceCode)

    val themeMode: ThemeMode
        get() = ThemeMode.fromCode(themeModeCode)
}

class ReleaseRepository(context: Context) {
    private val db = AppDatabase.get(context)
    val dao = db.dao()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pts_audible_prefs", Context.MODE_PRIVATE)
    private val syncSemaphore = Semaphore(4)
<<<<<<< HEAD
    private val abbSemaphore = Semaphore(1)

    companion object {
        private const val ABB_DAILY_INTERVAL_MS = 20L * 60L * 60L * 1000L
    }
=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16

    init {
        AudibleApiClient.initCache(context.applicationContext.cacheDir)
    }

    fun getPrefs(): FilterPrefs = FilterPrefs(
        englishOnly = prefs.getBoolean("english_only", true),
        hideAbridged = prefs.getBoolean("hide_abridged", true),
        hideDramatized = prefs.getBoolean("hide_dramatized", false),
        enableDirectAdd = prefs.getBoolean("enable_direct_add", false),
        alwaysShowCardActions = prefs.getBoolean("always_show_card_actions", false),
<<<<<<< HEAD
        checkAbbDaily = prefs.getBoolean("check_abb_daily", true),
=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
        themeModeCode = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM",
        notifyHour = prefs.getInt("notify_hour", 9),
        notifyMinute = prefs.getInt("notify_minute", 0),
        marketplaceCode = prefs.getString("marketplace_code", "US") ?: "US"
    )

    fun savePrefs(newPrefs: FilterPrefs) {
        prefs.edit()
            .putBoolean("english_only", newPrefs.englishOnly)
            .putBoolean("hide_abridged", newPrefs.hideAbridged)
            .putBoolean("hide_dramatized", newPrefs.hideDramatized)
            .putBoolean("enable_direct_add", newPrefs.enableDirectAdd)
            .putBoolean("always_show_card_actions", newPrefs.alwaysShowCardActions)
<<<<<<< HEAD
            .putBoolean("check_abb_daily", newPrefs.checkAbbDaily)
=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
            .putString("theme_mode", newPrefs.themeModeCode)
            .putInt("notify_hour", newPrefs.notifyHour)
            .putInt("notify_minute", newPrefs.notifyMinute)
            .putString("marketplace_code", newPrefs.marketplaceCode)
            .apply()
    }

    fun getSyncTelemetry(): SyncTelemetry = SyncTelemetry(
        lastSyncEpochMs = prefs.getLong("last_sync_epoch_ms", 0L),
        lastSyncSourcesCount = prefs.getInt("last_sync_sources_count", 0),
        lastSyncErrorCount = prefs.getInt("last_sync_error_count", 0),
        lastSyncUsedCachedFallback = prefs.getBoolean("last_sync_used_cached_fallback", false)
    )

    private fun saveSyncTelemetry(sourcesCount: Int, errorCount: Int, usedCachedFallback: Boolean) {
        prefs.edit()
            .putLong("last_sync_epoch_ms", System.currentTimeMillis())
            .putInt("last_sync_sources_count", sourcesCount)
            .putInt("last_sync_error_count", errorCount)
            .putBoolean("last_sync_used_cached_fallback", usedCachedFallback)
            .apply()
    }

    suspend fun addTrackedItemUnique(
        type: TrackType,
        rawQuery: String,
        lastReadSequence: Int = 0,
        initialThumbnailUrl: String = "",
        externalId: String? = null
    ): Boolean {
        val cleaned = if (type == TrackType.AUTHOR || type == TrackType.NARRATOR || type == TrackType.SERIES) {
            AudibleApiClient.formatCanonicalName(rawQuery)
        } else {
            rawQuery.trim()
        }
        if (cleaned.isBlank()) return false

        val existing = dao.findTrackedItem(type, cleaned)
        if (existing != null) {
            if (type == TrackType.SERIES && lastReadSequence > existing.lastReadSequence) {
                dao.updateSeriesReadSequence(existing.id, lastReadSequence)
            }
            val updatedExt = if (existing.externalId.isNullOrBlank() && !externalId.isNullOrBlank()) {
                externalId
            } else {
                existing.externalId
            }
            val updatedThumb = existing.thumbnailUrl.ifBlank { initialThumbnailUrl }
            if (updatedExt != existing.externalId || updatedThumb != existing.thumbnailUrl) {
                dao.updateTrackedItemDetails(existing.id, existing.queryValue, updatedExt, updatedThumb)
            }
            return false
        }

        val resolvedThumb = when (type) {
            TrackType.AUTHOR -> {
<<<<<<< HEAD
                AudibleApiClient.fetchAudibleAuthorImageUrl(cleaned, externalId?.substringBefore("|")) ?: initialThumbnailUrl
=======
                AudibleApiClient.fetchAudibleAuthorImageUrl(cleaned, externalId) ?: initialThumbnailUrl
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
            }
            TrackType.NARRATOR -> {
                AudibleApiClient.fetchNarratorPortraitUrl(cleaned) ?: initialThumbnailUrl
            }
            else -> initialThumbnailUrl
        }

        dao.insertTrackedItem(
            TrackedItemEntity(
                type = type,
                queryValue = cleaned,
                externalId = externalId,
                lastReadSequence = lastReadSequence,
                thumbnailUrl = resolvedThumb
            )
        )
        return true
    }

    private data class EditionGroup(
        var primaryProduct: CatalogProduct,
        val matchedSources: MutableSet<String> = linkedSetOf(),
        val editionAsins: MutableSet<String> = linkedSetOf()
    )

    private fun normalizeTitleForGrouping(rawTitle: String): String {
        val beforeColon = rawTitle.substringBefore(":").trim().ifBlank { rawTitle }
        return beforeColon.lowercase(Locale.US)
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\bbook\\s+viii\\b"), "book 8")
            .replace(Regex("\\bbook\\s+vii\\b"), "book 7")
            .replace(Regex("\\bbook\\s+vi\\b"), "book 6")
            .replace(Regex("\\bbook\\s+iv\\b"), "book 4")
            .replace(Regex("\\bbook\\s+v\\b"), "book 5")
            .replace(Regex("\\bbook\\s+iii\\b"), "book 3")
            .replace(Regex("\\bbook\\s+ii\\b"), "book 2")
            .replace(Regex("\\bbook\\s+i\\b"), "book 1")
            .replace(Regex("[^a-z0-9]"), "")
    }

<<<<<<< HEAD
    /**
     * Exact word-boundary name check so short names (e.g. "Actus" or "Will")
     * never match inside longer unrelated names.
     */
    private fun matchesPersonNameExactOrWordBoundary(candidateName: String, targetQuery: String): Boolean {
        val cand = candidateName.trim()
        val target = targetQuery.trim()
        if (cand.isBlank() || target.isBlank()) return false
        if (cand.equals(target, ignoreCase = true)) return true
        val escaped = Regex.escape(target)
        return Regex("\\b$escaped\\b", RegexOption.IGNORE_CASE).containsMatchIn(cand)
    }

=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
    private fun mergeDuplicateEditions(existing: CatalogProduct, candidate: CatalogProduct): CatalogProduct {
        val chosenCover = when {
            existing.coverUrl.isNotBlank() && candidate.coverUrl.isBlank() -> existing.coverUrl
            existing.coverUrl.isBlank() && candidate.coverUrl.isNotBlank() -> candidate.coverUrl
            else -> existing.coverUrl.ifBlank { candidate.coverUrl }
        }
        val chosenSeriesTitle = existing.seriesTitle.ifBlank { candidate.seriesTitle }
        val chosenSeriesAsin = existing.seriesAsin.ifBlank { candidate.seriesAsin }
        val chosenSeriesSeq = existing.seriesSequence ?: candidate.seriesSequence

        val chosenReleaseDate = when {
            existing.releaseDateIso == "TBA" -> candidate.releaseDateIso
            candidate.releaseDateIso == "TBA" -> existing.releaseDateIso
            candidate.releaseDateIso < existing.releaseDateIso -> candidate.releaseDateIso
            else -> existing.releaseDateIso
<<<<<<< HEAD
        }

        val chosenSynopsis = if (candidate.synopsis.length > existing.synopsis.length) {
            candidate.synopsis
        } else {
            existing.synopsis
        }
        val chosenRuntime = maxOf(existing.runtimeMinutes, candidate.runtimeMinutes)
        val chosenPublisher = existing.publisher.ifBlank { candidate.publisher }
        val chosenNarrators = if (existing.narrators.isNotEmpty()) existing.narrators else candidate.narrators

        val base = if (existing.coverUrl.isBlank() && candidate.coverUrl.isNotBlank()) candidate else existing
        return base.copy(
            coverUrl = chosenCover,
            seriesTitle = chosenSeriesTitle,
            seriesAsin = chosenSeriesAsin,
            seriesSequence = chosenSeriesSeq,
            releaseDateIso = chosenReleaseDate,
            synopsis = chosenSynopsis,
            runtimeMinutes = chosenRuntime,
            publisher = chosenPublisher,
            narrators = chosenNarrators
        )
    }

    /**
     * Sequentially checks AudioBookBay at most once per day (or when forceAbbCheck = true)
     * for already-released books (`releaseDateIso <= today`) that are not yet marked `abbAvailable`.
     * Uses `Semaphore(1)` + `1500ms` spacing between requests so ABB never rate-limits the app.
     */
    suspend fun checkPendingAbbReleases(forceAbbCheck: Boolean = false): List<ReleaseEntity> {
        val filterPrefs = getPrefs()
        if (!filterPrefs.checkAbbDaily) return dao.getAllReleases()

        val nowMs = System.currentTimeMillis()
        val lastAbbCheckMs = prefs.getLong("last_abb_check_ms", 0L)
        if (!forceAbbCheck && (nowMs - lastAbbCheckMs) < ABB_DAILY_INTERVAL_MS) {
            return dao.getAllReleases()
        }

        val todayIso = LocalDate.now().toString()
        val candidates = dao.getAllReleases().filter { rel ->
            !rel.abbAvailable && rel.releaseDateIso != "TBA" && rel.releaseDateIso <= todayIso
        }

        if (candidates.isEmpty()) {
            prefs.edit().putLong("last_abb_check_ms", nowMs).apply()
            return dao.getAllReleases()
        }

        abbSemaphore.withPermit {
            for ((index, rel) in candidates.take(8).withIndex()) {
                if (index > 0) {
                    delay(1500L)
                }
                val primaryAuthor = rel.authors.split(",").firstOrNull()?.trim().orEmpty()
                val match = AudibleApiClient.checkAudioBookBayAvailability(rel.title, primaryAuthor)
                if (match.available) {
                    dao.updateAbbAvailability(
                        asin = rel.asin,
                        available = true,
                        postUrl = match.postUrl,
                        magnetUri = match.magnetUri
                    )
=======
        }

        val chosenSynopsis = if (candidate.synopsis.length > existing.synopsis.length) {
            candidate.synopsis
        } else {
            existing.synopsis
        }
        val chosenRuntime = maxOf(existing.runtimeMinutes, candidate.runtimeMinutes)
        val chosenPublisher = existing.publisher.ifBlank { candidate.publisher }
        val chosenNarrators = if (existing.narrators.isNotEmpty()) existing.narrators else candidate.narrators

        val base = if (existing.coverUrl.isBlank() && candidate.coverUrl.isNotBlank()) candidate else existing
        return base.copy(
            coverUrl = chosenCover,
            seriesTitle = chosenSeriesTitle,
            seriesAsin = chosenSeriesAsin,
            seriesSequence = chosenSeriesSeq,
            releaseDateIso = chosenReleaseDate,
            synopsis = chosenSynopsis,
            runtimeMinutes = chosenRuntime,
            publisher = chosenPublisher,
            narrators = chosenNarrators
        )
    }

    suspend fun syncCatalog(forceRefresh: Boolean = false): List<ReleaseEntity> {
        // 1. Run lightweight 30-day & 15 MB LRU disk cache pruner
        AudibleApiClient.pruneStaleDiskCache()
        AudibleApiClient.syncNetworkErrorCount.set(0)
        AudibleApiClient.syncUsedStaleCacheCount.set(0)

        val tracked = dao.getAllTrackedItems()
        if (tracked.isEmpty()) {
            dao.clearAllReleases()
            saveSyncTelemetry(sourcesCount = 0, errorCount = 0, usedCachedFallback = false)
            return emptyList()
        }

        val dndRules = dao.getAllDndRules()
        val existingReleases = dao.getAllReleases()
        val existingByAsin = existingReleases.associateBy { it.asin }
        val existingByGroupKey = existingReleases.associateBy { rel ->
            val normT = normalizeTitleForGrouping(rel.title)
            val normA = rel.authors.split(",").firstOrNull()?.lowercase(Locale.US)
                ?.replace(Regex("[^a-z0-9]"), "") ?: ""
            "${normT}_${normA}"
        }

        val filterPrefs = getPrefs()
        val todayIso = LocalDate.now().toString()
        val recentCutoffIso = LocalDate.now().minusDays(30).toString()

        val dndBooks = dndRules.filter { it.targetType == DndTargetType.BOOK }
            .map { it.targetKey.lowercase(Locale.US) }.toSet()
        val dndSeries = dndRules.filter { it.targetType == DndTargetType.SERIES }
            .map { it.targetKey.lowercase(Locale.US) }.toSet()

        val fetchedPairs = coroutineScope {
            tracked.map { item ->
                async {
                    syncSemaphore.withPermit {
                        val results = when (item.type) {
                            TrackType.AUTHOR -> AudibleApiClient.searchCatalog(
                                author = item.queryValue,
                                maxPages = 3,
                                forceRefresh = forceRefresh
                            )
                            TrackType.NARRATOR -> AudibleApiClient.searchCatalog(
                                narrator = item.queryValue,
                                maxPages = 2,
                                forceRefresh = forceRefresh
                            )
                            TrackType.SERIES -> AudibleApiClient.searchCatalog(
                                titleOrSeries = item.queryValue,
                                maxPages = 2,
                                forceRefresh = forceRefresh
                            )
                            TrackType.BOOK -> AudibleApiClient.searchCatalog(
                                asin = item.externalId ?: item.queryValue,
                                titleOrSeries = item.queryValue,
                                maxPages = 1,
                                forceRefresh = forceRefresh
                            )
                        }
                        item to results
                    }
                }
            }.awaitAll()
        }

        val assignedThumbnails = tracked.mapNotNull { it.thumbnailUrl.takeIf { u -> u.isNotBlank() } }.toMutableSet()
        val bookCoversSet = fetchedPairs.flatMap { it.second }.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } }.toSet()

        for ((item, results) in fetchedPairs) {
            val canonicalName = when (item.type) {
                TrackType.AUTHOR -> {
                    results.flatMap { it.authors }
                        .firstOrNull { it.equals(item.queryValue, ignoreCase = true) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.SERIES -> {
                    results.map { it.seriesTitle }
                        .firstOrNull { it.equals(item.queryValue, ignoreCase = true) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.NARRATOR -> {
                    results.flatMap { it.narrators }
                        .firstOrNull { it.equals(item.queryValue, ignoreCase = true) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.BOOK -> item.queryValue
            }

            val isAuthorShowingBookCover = item.type == TrackType.AUTHOR && (
                item.thumbnailUrl.isBlank() ||
                    item.thumbnailUrl in bookCoversSet ||
                    (!item.thumbnailUrl.contains("amzn-author-media", ignoreCase = true) &&
                        !item.thumbnailUrl.contains("ssl-images-amazon", ignoreCase = true))
            )

            val discoveredAuthorAsin = item.externalId?.takeIf { it.isNotBlank() }
                ?: results.firstOrNull { prod ->
                    prod.primaryAuthorAsin.isNotBlank() &&
                        prod.authors.firstOrNull()?.contains(item.queryValue.trim(), ignoreCase = true) == true
                }?.primaryAuthorAsin
                ?: results.firstOrNull { it.primaryAuthorAsin.isNotBlank() }?.primaryAuthorAsin

            var updatedThumb = item.thumbnailUrl
            if (item.thumbnailUrl.isBlank() || isAuthorShowingBookCover) {
                val portrait = when (item.type) {
                    TrackType.AUTHOR -> AudibleApiClient.fetchAudibleAuthorImageUrl(canonicalName, discoveredAuthorAsin)
                    TrackType.NARRATOR -> AudibleApiClient.fetchNarratorPortraitUrl(canonicalName)
                    else -> null
                }
                if (!portrait.isNullOrBlank()) {
                    updatedThumb = portrait
                } else if (updatedThumb.isBlank()) {
                    val availableCovers = results.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } }
                    updatedThumb = availableCovers.firstOrNull { it !in assignedThumbnails }
                        ?: availableCovers.firstOrNull().orEmpty()
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
                }
            }

            if (updatedThumb != item.thumbnailUrl || canonicalName != item.queryValue || (discoveredAuthorAsin != null && discoveredAuthorAsin != item.externalId)) {
                if (updatedThumb.isNotBlank()) assignedThumbnails.add(updatedThumb)
                dao.updateTrackedItemDetails(
                    id = item.id,
                    queryValue = canonicalName,
                    extId = discoveredAuthorAsin ?: item.externalId,
                    url = updatedThumb
                )
            }
        }

<<<<<<< HEAD
        prefs.edit().putLong("last_abb_check_ms", nowMs).apply()
        return dao.getAllReleases()
    }

    suspend fun syncCatalog(forceRefresh: Boolean = false): List<ReleaseEntity> {
        AudibleApiClient.pruneStaleDiskCache()
        AudibleApiClient.syncNetworkErrorCount.set(0)
        AudibleApiClient.syncUsedStaleCacheCount.set(0)

        val tracked = dao.getAllTrackedItems()
        if (tracked.isEmpty()) {
            dao.clearAllReleases()
            saveSyncTelemetry(sourcesCount = 0, errorCount = 0, usedCachedFallback = false)
            return emptyList()
        }

        val dndRules = dao.getAllDndRules()
        val existingReleases = dao.getAllReleases()
        val existingByAsin = existingReleases.associateBy { it.asin }
        val existingByGroupKey = existingReleases.associateBy { rel ->
            val normT = normalizeTitleForGrouping(rel.title)
            val normA = rel.authors.split(",").firstOrNull()?.lowercase(Locale.US)
                ?.replace(Regex("[^a-z0-9]"), "") ?: ""
            "${normT}_${normA}"
        }

        val filterPrefs = getPrefs()
        val todayIso = LocalDate.now().toString()
        val recentCutoffIso = LocalDate.now().minusDays(30).toString()

        val dndBooks = dndRules.filter { it.targetType == DndTargetType.BOOK }
            .map { it.targetKey.lowercase(Locale.US) }.toSet()
        val dndSeries = dndRules.filter { it.targetType == DndTargetType.SERIES }
            .map { it.targetKey.lowercase(Locale.US) }.toSet()

        val fetchedPairs = coroutineScope {
            tracked.map { item ->
                async {
                    syncSemaphore.withPermit {
                        val results = when (item.type) {
                            TrackType.AUTHOR -> AudibleApiClient.searchCatalog(
                                author = item.queryValue,
                                maxPages = 3,
                                forceRefresh = forceRefresh
                            )
                            TrackType.NARRATOR -> AudibleApiClient.searchCatalog(
                                narrator = item.queryValue,
                                maxPages = 2,
                                forceRefresh = forceRefresh
                            )
                            TrackType.SERIES -> {
                                val pinnedAuthor = item.pinnedAuthor
                                AudibleApiClient.searchCatalog(
                                    author = pinnedAuthor.ifBlank { null },
                                    titleOrSeries = item.queryValue,
                                    maxPages = 2,
                                    forceRefresh = forceRefresh
                                )
                            }
                            TrackType.BOOK -> AudibleApiClient.searchCatalog(
                                asin = item.pinnedAsin.ifBlank { item.queryValue },
                                titleOrSeries = item.queryValue,
                                maxPages = 1,
                                forceRefresh = forceRefresh
                            )
                        }
                        item to results
                    }
                }
            }.awaitAll()
        }

        val assignedThumbnails = tracked.mapNotNull { it.thumbnailUrl.takeIf { u -> u.isNotBlank() } }.toMutableSet()
        val bookCoversSet = fetchedPairs.flatMap { it.second }.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } }.toSet()

        // Also build a map of effective pinned externalId per tracked item so same-name series collisions are blocked
        val effectiveExternalIds = mutableMapOf<Long, String?>()

        for ((item, results) in fetchedPairs) {
            val canonicalName = when (item.type) {
                TrackType.AUTHOR -> {
                    results.flatMap { it.authors }
                        .firstOrNull { it.equals(item.queryValue, ignoreCase = true) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.SERIES -> {
                    results.map { it.seriesTitle }
                        .firstOrNull { it.equals(item.queryValue, ignoreCase = true) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.NARRATOR -> {
                    results.flatMap { it.narrators }
                        .firstOrNull { it.equals(item.queryValue, ignoreCase = true) }
                        ?: AudibleApiClient.formatCanonicalName(item.queryValue)
                }
                TrackType.BOOK -> item.queryValue
            }

            // Auto-pin SERIES and BOOK items to their canonical seriesAsin + primary author if not already pinned
            val updatedExtId: String? = when (item.type) {
                TrackType.SERIES -> {
                    if (!item.externalId.isNullOrBlank() && item.externalId.contains("|")) {
                        item.externalId
                    } else {
                        val exactSeriesProd = results.firstOrNull { prod ->
                            prod.seriesTitle.equals(item.queryValue.trim(), ignoreCase = true) &&
                                prod.authors.isNotEmpty()
                        } ?: results.firstOrNull { prod ->
                            prod.seriesTitle.contains(item.queryValue.trim(), ignoreCase = true) &&
                                prod.authors.isNotEmpty()
                        }
                        if (exactSeriesProd != null) {
                            val sAsin = exactSeriesProd.seriesAsin.ifBlank { item.pinnedAsin }
                            val pAuth = exactSeriesProd.authors.firstOrNull().orEmpty()
                            "$sAsin|$pAuth"
                        } else {
                            item.externalId
                        }
                    }
                }
                TrackType.BOOK -> {
                    if (!item.externalId.isNullOrBlank() && item.externalId.contains("|")) {
                        item.externalId
                    } else {
                        val exactBookProd = results.firstOrNull()
                        if (exactBookProd != null) {
                            "${exactBookProd.asin}|${exactBookProd.authors.firstOrNull().orEmpty()}"
                        } else {
                            item.externalId
                        }
                    }
                }
                TrackType.AUTHOR -> {
                    item.externalId?.takeIf { it.isNotBlank() }
                        ?: results.firstOrNull { prod ->
                            prod.primaryAuthorAsin.isNotBlank() &&
                                prod.authors.firstOrNull()?.let { a -> matchesPersonNameExactOrWordBoundary(a, item.queryValue) } == true
                        }?.primaryAuthorAsin
                }
                TrackType.NARRATOR -> item.externalId
            }

            effectiveExternalIds[item.id] = updatedExtId

            val isAuthorShowingBookCover = item.type == TrackType.AUTHOR && (
                item.thumbnailUrl.isBlank() ||
                    item.thumbnailUrl in bookCoversSet ||
                    (!item.thumbnailUrl.contains("amzn-author-media", ignoreCase = true) &&
                        !item.thumbnailUrl.contains("ssl-images-amazon", ignoreCase = true))
            )

            var updatedThumb = item.thumbnailUrl
            if (item.thumbnailUrl.isBlank() || isAuthorShowingBookCover) {
                val portrait = when (item.type) {
                    TrackType.AUTHOR -> AudibleApiClient.fetchAudibleAuthorImageUrl(canonicalName, updatedExtId?.substringBefore("|"))
                    TrackType.NARRATOR -> AudibleApiClient.fetchNarratorPortraitUrl(canonicalName)
                    else -> null
                }
                if (!portrait.isNullOrBlank()) {
                    updatedThumb = portrait
                } else if (updatedThumb.isBlank()) {
                    val availableCovers = results.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } }
                    updatedThumb = availableCovers.firstOrNull { it !in assignedThumbnails }
                        ?: availableCovers.firstOrNull().orEmpty()
                }
            }

            if (updatedThumb != item.thumbnailUrl || canonicalName != item.queryValue || updatedExtId != item.externalId) {
                if (updatedThumb.isNotBlank()) assignedThumbnails.add(updatedThumb)
                dao.updateTrackedItemDetails(
                    id = item.id,
                    queryValue = canonicalName,
                    extId = updatedExtId,
                    url = updatedThumb
                )
            }
        }

=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
        val trackedSeriesLastRead = tracked.filter { it.type == TrackType.SERIES }
            .associate { it.queryValue.lowercase(Locale.US) to it.lastReadSequence }

        val groupedEditions = linkedMapOf<String, EditionGroup>()

        for ((item, results) in fetchedPairs) {
            val qNorm = item.queryValue.trim()
<<<<<<< HEAD
            val effExt = effectiveExternalIds[item.id] ?: item.externalId
            val pinnedAsin = effExt?.substringBefore("|")?.trim().orEmpty()
            val pinnedAuthor = if (effExt?.contains("|") == true) effExt.substringAfter("|").trim() else ""

            for (prod in results) {
                when (item.type) {
                    TrackType.SERIES -> {
                        // Item #1: Strict Series-Author & ASIN Pinning so same-name series by other authors never match
                        val asinExactMatch = pinnedAsin.isNotBlank() &&
                            prod.seriesAsin.isNotBlank() &&
                            prod.seriesAsin.equals(pinnedAsin, ignoreCase = true)

                        val titleMatchesSeries = prod.seriesTitle.isNotBlank() && (
                            prod.seriesTitle.equals(qNorm, ignoreCase = true) ||
                                prod.seriesTitle.equals("$qNorm Series", ignoreCase = true) ||
                                prod.seriesTitle.equals("The $qNorm Series", ignoreCase = true) ||
                                (pinnedAsin.isBlank() && prod.seriesTitle.contains(qNorm, ignoreCase = true))
                        )

                        val authorMatchesPinned = pinnedAuthor.isBlank() || prod.authors.any { a ->
                            matchesPersonNameExactOrWordBoundary(a, pinnedAuthor)
                        }

                        if (!asinExactMatch && !(titleMatchesSeries && authorMatchesPinned)) {
                            continue
                        }
                    }
                    TrackType.AUTHOR -> {
                        // Item #4: Strict word-boundary author matching
                        val authorMatches = prod.authors.take(2).any { a ->
                            matchesPersonNameExactOrWordBoundary(a, qNorm)
=======
            for (prod in results) {
                when (item.type) {
                    TrackType.SERIES -> {
                        val seriesMatches = prod.seriesTitle.isNotBlank() &&
                            prod.seriesTitle.contains(qNorm, ignoreCase = true)
                        val numberedTitleMatches = prod.seriesTitle.isBlank() &&
                            prod.title.startsWith(qNorm, ignoreCase = true) &&
                            prod.authors.isNotEmpty()
                        if (!seriesMatches && !numberedTitleMatches) continue
                    }
                    TrackType.AUTHOR -> {
                        val authorMatches = prod.authors.take(2).any { a ->
                            a.equals(qNorm, ignoreCase = true) || a.contains(qNorm, ignoreCase = true)
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
                        }
                        if (!authorMatches) continue
                    }
                    TrackType.NARRATOR -> {
<<<<<<< HEAD
                        // Item #4: Strict word-boundary narrator matching
                        val narratorMatches = prod.narrators.any { n ->
                            matchesPersonNameExactOrWordBoundary(n, qNorm)
=======
                        val narratorMatches = prod.narrators.any { n ->
                            n.equals(qNorm, ignoreCase = true) || n.contains(qNorm, ignoreCase = true)
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
                        }
                        if (!narratorMatches) continue
                    }
                    TrackType.BOOK -> {
<<<<<<< HEAD
                        val asinMatches = (pinnedAsin.isNotBlank() && prod.asin.equals(pinnedAsin, ignoreCase = true)) ||
                            prod.asin.equals(qNorm, ignoreCase = true)
                        val titleAndAuthorMatches = prod.title.equals(qNorm, ignoreCase = true) &&
                            (pinnedAuthor.isBlank() || prod.authors.any { a -> matchesPersonNameExactOrWordBoundary(a, pinnedAuthor) })
                        if (!asinMatches && !titleAndAuthorMatches) continue
=======
                        val bookMatches = prod.asin.equals(qNorm, ignoreCase = true) ||
                            prod.title.contains(qNorm, ignoreCase = true)
                        if (!bookMatches) continue
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
                    }
                }

                if (item.type != TrackType.BOOK &&
                    prod.releaseDateIso != "TBA" &&
                    prod.releaseDateIso < recentCutoffIso
                ) {
                    continue
                }

                if (prod.seriesTitle.isNotBlank() && prod.seriesSequence != null && prod.releaseDateIso > todayIso) {
                    val sKey = prod.seriesTitle.trim().lowercase(Locale.US)
                    val userLastRead = trackedSeriesLastRead.entries
<<<<<<< HEAD
                        .firstOrNull { (k, _) -> sKey.equals(k, ignoreCase = true) || sKey.contains(k) }?.value ?: 0
=======
                        .firstOrNull { (k, _) -> sKey.contains(k) || k.contains(sKey) }?.value ?: 0
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
                    if (userLastRead > 0 && prod.seriesSequence <= userLastRead) {
                        continue
                    }
                }

                if (filterPrefs.englishOnly && !prod.language.equals("english", ignoreCase = true)) {
                    continue
                }
                val isAbridged = prod.formatType.equals("abridged", ignoreCase = true)
                val isDramatized = prod.title.contains("Dramatized Adaptation", ignoreCase = true) ||
                    prod.subtitle.contains("Dramatized Adaptation", ignoreCase = true) ||
                    prod.authors.any { it.contains("Full Cast", ignoreCase = true) }

                if (filterPrefs.hideAbridged && isAbridged) continue
                if (filterPrefs.hideDramatized && isDramatized) continue

                if (prod.asin.lowercase(Locale.US) in dndBooks) continue
                if (prod.seriesTitle.isNotBlank() && prod.seriesTitle.lowercase(Locale.US) in dndSeries) continue
                if (prod.seriesAsin.isNotBlank() && prod.seriesAsin.lowercase(Locale.US) in dndSeries) continue

                val normTitle = normalizeTitleForGrouping(prod.title)
                val normAuthor = prod.authors.firstOrNull()?.lowercase(Locale.US)
                    ?.replace(Regex("[^a-z0-9]"), "") ?: ""
                val groupKey = "${normTitle}_${normAuthor}"

                val existingGroup = groupedEditions[groupKey]
                if (existingGroup == null) {
                    val newGroup = EditionGroup(primaryProduct = prod)
                    newGroup.matchedSources.add(item.type.name)
                    newGroup.editionAsins.add(prod.asin)
                    groupedEditions[groupKey] = newGroup
                } else {
                    existingGroup.primaryProduct = mergeDuplicateEditions(existingGroup.primaryProduct, prod)
                    existingGroup.matchedSources.add(item.type.name)
                    existingGroup.editionAsins.add(prod.asin)
                }
            }
        }

        val finalEntities = groupedEditions.entries.map { (groupKey, group) ->
            val prod = group.primaryProduct
            val prev = existingByAsin[prod.asin] ?: existingByGroupKey[groupKey]
            val isDramatized = prod.title.contains("Dramatized", ignoreCase = true) ||
                prod.subtitle.contains("Dramatized", ignoreCase = true)

            val newNarratorsStr = prod.narrators.joinToString(", ").ifBlank { "TBA" }

<<<<<<< HEAD
=======
            // 2. Release Date Shift & Delay Detector
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
            val shiftedFromDate = when {
                prev == null -> ""
                prev.releaseDateIso != prod.releaseDateIso && prev.releaseDateIso.isNotBlank() -> prev.releaseDateIso
                else -> prev.previousReleaseDateIso
            }

<<<<<<< HEAD
=======
            // 3. Narrator TBA -> Announced Detector
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
            val narratorNewlyAnnounced = when {
                prev == null -> false
                prev.narrators.equals("TBA", ignoreCase = true) && !newNarratorsStr.equals("TBA", ignoreCase = true) -> true
                else -> prev.narratorJustAnnounced
            }

            ReleaseEntity(
                asin = prod.asin,
                title = prod.title,
                subtitle = prod.subtitle,
                authors = prod.authors.joinToString(", ").ifBlank { "Unknown Author" },
                narrators = newNarratorsStr,
                seriesTitle = prod.seriesTitle,
                seriesAsin = prod.seriesAsin,
                seriesSequence = prod.seriesSequence,
                releaseDateIso = prod.releaseDateIso,
                language = prod.language.replaceFirstChar { it.uppercase() },
                isAbridged = prod.formatType.equals("abridged", ignoreCase = true),
                isDramatized = isDramatized,
                matchedTags = group.matchedSources.joinToString(","),
                isNewDiscovery = prev?.isNewDiscovery ?: true,
                notifiedSevenDay = prev?.notifiedSevenDay ?: false,
                notifiedReleaseDay = prev?.notifiedReleaseDay ?: false,
                firstSeenEpochMs = prev?.firstSeenEpochMs ?: System.currentTimeMillis(),
                coverUrl = prod.coverUrl,
                synopsis = prod.synopsis,
                runtimeMinutes = prod.runtimeMinutes,
                publisher = prod.publisher,
                altAsins = group.editionAsins.joinToString(","),
                previousReleaseDateIso = shiftedFromDate,
<<<<<<< HEAD
                narratorJustAnnounced = narratorNewlyAnnounced,
                abbAvailable = prev?.abbAvailable ?: false,
                abbPostUrl = prev?.abbPostUrl ?: "",
                abbMagnetUri = prev?.abbMagnetUri ?: "",
                notifiedAbbAvailable = prev?.notifiedAbbAvailable ?: false
            )
        }

=======
                narratorJustAnnounced = narratorNewlyAnnounced
            )
        }

        // If every single request failed with no cached fallback and we already had releases in DB, preserve existing DB
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
        val errCount = AudibleApiClient.syncNetworkErrorCount.get()
        val staleCount = AudibleApiClient.syncUsedStaleCacheCount.get()
        if (finalEntities.isEmpty() && existingReleases.isNotEmpty() && errCount > 0) {
            saveSyncTelemetry(
                sourcesCount = tracked.size,
                errorCount = errCount,
                usedCachedFallback = true
            )
            return existingReleases
        }

        dao.replaceSyncedReleases(finalEntities)
        saveSyncTelemetry(
            sourcesCount = tracked.size,
            errorCount = errCount,
            usedCachedFallback = staleCount > 0
        )
<<<<<<< HEAD

        // Item #2: Run throttled daily AudioBookBay availability check for released books
        return checkPendingAbbReleases(forceAbbCheck = forceRefresh)
=======
        return dao.getAllReleases()
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
    }

    suspend fun muteBook(asin: String, title: String) {
        dao.insertDndRule(
            DndRuleEntity(
                targetKey = asin.lowercase(Locale.US),
                targetType = DndTargetType.BOOK,
                displayTitle = title
            )
        )
        dao.deleteReleaseByAsin(asin)
    }

    suspend fun muteSeries(seriesKey: String, displayTitle: String) {
        if (seriesKey.isBlank()) return
        dao.insertDndRule(
            DndRuleEntity(
                targetKey = seriesKey.lowercase(Locale.US),
                targetType = DndTargetType.SERIES,
                displayTitle = displayTitle
            )
        )
        dao.deleteReleasesBySeries(seriesKey)
    }
}
