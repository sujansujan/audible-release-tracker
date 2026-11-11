package com.plaintext.audible.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

@Immutable
data class FilterPrefs(
    val englishOnly: Boolean = true,
    val hideAbridged: Boolean = true,
    val hideDramatized: Boolean = false,
    val enableDirectAdd: Boolean = false,
    val alwaysShowCardActions: Boolean = false,
    val checkAbbDaily: Boolean = false,
    val themeModeCode: String = "SYSTEM",
    val notifyHour: Int = 9,
    val notifyMinute: Int = 0,
    val marketplaceCode: String = "US"
)

data class SyncNotificationBatch(
    val sevenDayOut: List<ReleaseEntity>,
    val releaseDay: List<ReleaseEntity>,
    val narratorRevealed: List<ReleaseEntity>
)

class ReleaseRepository(context: Context) {
    private val appContext = context.applicationContext
    val dao: TrackerDao = AppDatabase.get(appContext).dao()
    private val sharedPrefs = appContext.getSharedPreferences("pts_audible_prefs", Context.MODE_PRIVATE)

    init {
        AudibleApiClient.initCache(appContext.cacheDir)
    }

    val trackedItemsFlow: Flow<List<TrackedItemEntity>> = dao.observeTrackedItems()
    val dndRulesFlow: Flow<List<DndRuleEntity>> = dao.observeDndRules()
    val releasesFlow: Flow<List<ReleaseEntity>> = dao.observeReleases()

    fun getPrefs(): FilterPrefs {
        return FilterPrefs(
            englishOnly = sharedPrefs.getBoolean("englishOnly", true),
            hideAbridged = sharedPrefs.getBoolean("hideAbridged", true),
            hideDramatized = sharedPrefs.getBoolean("hideDramatized", false),
            enableDirectAdd = sharedPrefs.getBoolean("enableDirectAdd", false),
            alwaysShowCardActions = sharedPrefs.getBoolean("alwaysShowCardActions", false),
            checkAbbDaily = sharedPrefs.getBoolean("checkAbbDaily", false),
            themeModeCode = sharedPrefs.getString("themeModeCode", "SYSTEM") ?: "SYSTEM",
            notifyHour = sharedPrefs.getInt("notifyHour", 9),
            notifyMinute = sharedPrefs.getInt("notifyMinute", 0),
            marketplaceCode = sharedPrefs.getString("marketplaceCode", "US") ?: "US"
        )
    }

    fun savePrefs(prefs: FilterPrefs) {
        sharedPrefs.edit()
            .putBoolean("englishOnly", prefs.englishOnly)
            .putBoolean("hideAbridged", prefs.hideAbridged)
            .putBoolean("hideDramatized", prefs.hideDramatized)
            .putBoolean("enableDirectAdd", prefs.enableDirectAdd)
            .putBoolean("alwaysShowCardActions", prefs.alwaysShowCardActions)
            .putBoolean("checkAbbDaily", prefs.checkAbbDaily)
            .putString("themeModeCode", prefs.themeModeCode)
            .putInt("notifyHour", prefs.notifyHour)
            .putInt("notifyMinute", prefs.notifyMinute)
            .putString("marketplaceCode", prefs.marketplaceCode)
            .apply()
    }

    fun getLastSyncEpochMs(): Long = sharedPrefs.getLong("lastSyncEpochMs", 0L)

    private fun setLastSyncEpochMs(epochMs: Long) {
        sharedPrefs.edit().putLong("lastSyncEpochMs", epochMs).apply()
    }

    suspend fun addTrackedItemUnique(
        type: TrackType,
        rawQuery: String,
        lastReadSequence: Int = 0,
        initialThumbnailUrl: String = "",
        externalId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val canonical = AudibleApiClient.formatCanonicalName(rawQuery)
        if (canonical.isBlank()) return@withContext false

        val existing = dao.findTrackedItem(type, canonical)
        if (existing != null) {
            val updatedThumb = initialThumbnailUrl.ifBlank { existing.thumbnailUrl }
            val updatedExt = externalId ?: existing.externalId
            dao.updateTrackedItemDetails(existing.id, canonical, updatedExt, updatedThumb)
            return@withContext false
        }

        val resolvedThumb = if (initialThumbnailUrl.isNotBlank()) {
            initialThumbnailUrl
        } else {
            when (type) {
                TrackType.AUTHOR -> AudibleApiClient.fetchAudibleAuthorImageUrl(
                    authorName = canonical,
                    knownAuthorAsin = externalId?.substringBefore("|").orEmpty()
                ).orEmpty()
                TrackType.NARRATOR -> AudibleApiClient.fetchNarratorPortraitUrl(canonical).orEmpty()
                else -> ""
            }
        }

        dao.insertTrackedItem(
            TrackedItemEntity(
                type = type,
                queryValue = canonical,
                externalId = externalId,
                lastReadSequence = lastReadSequence,
                thumbnailUrl = resolvedThumb
            )
        )
        true
    }

    suspend fun removeTrackedItem(item: TrackedItemEntity) = withContext(Dispatchers.IO) {
        dao.deleteTrackedItem(item)
        syncCatalog(forceRefresh = false)
    }

    suspend fun updateSeriesReadProgress(id: Long, lastReadSeq: Int) = withContext(Dispatchers.IO) {
        dao.updateSeriesReadSequence(id, lastReadSeq.coerceAtLeast(0))
    }

    suspend fun muteBook(asin: String, title: String) = withContext(Dispatchers.IO) {
        dao.insertDndRule(
            DndRuleEntity(
                targetKey = asin,
                targetType = DndTargetType.BOOK,
                displayTitle = title.ifBlank { asin }
            )
        )
        dao.deleteReleaseByAsin(asin)
    }

    suspend fun muteSeries(seriesKey: String, displayTitle: String) = withContext(Dispatchers.IO) {
        dao.insertDndRule(
            DndRuleEntity(
                targetKey = seriesKey,
                targetType = DndTargetType.SERIES,
                displayTitle = displayTitle.ifBlank { seriesKey }
            )
        )
        dao.deleteReleasesBySeries(seriesKey)
    }

    suspend fun removeDndRule(rule: DndRuleEntity) = withContext(Dispatchers.IO) {
        dao.deleteDndRule(rule)
    }

    suspend fun markAllSeen() = withContext(Dispatchers.IO) {
        dao.clearNewDiscoveryFlags()
    }

    suspend fun markNotified(asin: String, sevenDay: Boolean, releaseDay: Boolean) = withContext(Dispatchers.IO) {
        dao.updateNotificationFlags(asin, sevenDay, releaseDay)
    }

    suspend fun refreshSingleTrackedPortrait(item: TrackedItemEntity): String = withContext(Dispatchers.IO) {
        val freshUrl = when (item.type) {
            TrackType.AUTHOR -> {
                AudibleApiClient.fetchAudibleAuthorImageUrl(
                    authorName = item.queryValue,
                    knownAuthorAsin = item.pinnedAsin
                ).orEmpty()
            }
            TrackType.NARRATOR -> AudibleApiClient.fetchNarratorPortraitUrl(item.queryValue).orEmpty()
            else -> item.thumbnailUrl
        }
        if (freshUrl.isNotBlank() && freshUrl != item.thumbnailUrl) {
            dao.updateTrackedItemThumbnail(item.id, freshUrl)
        }
        freshUrl
    }

    suspend fun syncCatalog(
        forceRefresh: Boolean = false
    ): SyncNotificationBatch = withContext(Dispatchers.IO) {
        val prefs = getPrefs()
        val trackedItems = dao.getAllTrackedItems()
        val dndRules = dao.getAllDndRules()
        val existingByAsin = dao.getAllReleases().associateBy { it.asin }

        val mutedBookAsins = dndRules
            .filter { it.targetType == DndTargetType.BOOK }
            .map { it.targetKey.lowercase(Locale.US) }
            .toSet()

        val mutedSeriesKeys = dndRules
            .filter { it.targetType == DndTargetType.SERIES }
            .map { it.targetKey.lowercase(Locale.US) }
            .toSet()

        val seriesLastReadMap = trackedItems
            .filter { it.type == TrackType.SERIES && it.lastReadSequence > 0 }
            .associate { it.queryValue.lowercase(Locale.US) to it.lastReadSequence }

        val groupedProducts = LinkedHashMap<String, Pair<CatalogProduct, MutableSet<String>>>()

        for ((idx, item) in trackedItems.withIndex()) {
            val fetched: List<CatalogProduct> = when (item.type) {
                TrackType.AUTHOR -> AudibleApiClient.searchCatalog(
                    author = item.queryValue,
                    maxPages = 2,
                    forceRefresh = forceRefresh
                ).filter { prod ->
                    prod.authors.any { a ->
                        a.equals(item.queryValue, ignoreCase = true) ||
                            a.contains(item.queryValue, ignoreCase = true)
                    }
                }

                TrackType.NARRATOR -> AudibleApiClient.searchCatalog(
                    narrator = item.queryValue,
                    maxPages = 2,
                    forceRefresh = forceRefresh
                ).filter { prod ->
                    prod.narrators.any { n ->
                        n.equals(item.queryValue, ignoreCase = true) ||
                            n.contains(item.queryValue, ignoreCase = true)
                    }
                }

                TrackType.SERIES -> AudibleApiClient.searchCatalog(
                    titleOrSeries = item.queryValue,
                    maxPages = 2,
                    forceRefresh = forceRefresh
                ).filter { prod ->
                    val sMatch = prod.seriesTitle.contains(item.queryValue, ignoreCase = true) ||
                        prod.title.contains(item.queryValue, ignoreCase = true) ||
                        prod.subtitle.contains(item.queryValue, ignoreCase = true)
                    val aMatch = item.pinnedAuthor.isBlank() ||
                        prod.authors.any { it.contains(item.pinnedAuthor, ignoreCase = true) }
                    sMatch && aMatch
                }

                TrackType.BOOK -> {
                    if (item.pinnedAsin.isNotBlank()) {
                        AudibleApiClient.searchCatalog(
                            asin = item.pinnedAsin,
                            forceRefresh = forceRefresh
                        )
                    } else {
                        AudibleApiClient.searchCatalog(
                            titleOrSeries = item.queryValue,
                            maxPages = 1,
                            forceRefresh = forceRefresh
                        )
                    }
                }
            }

            if (item.thumbnailUrl.isBlank() && fetched.isNotEmpty()) {
                val candidateThumb = when (item.type) {
                    TrackType.AUTHOR -> AudibleApiClient.fetchAudibleAuthorImageUrl(
                        authorName = item.queryValue,
                        knownAuthorAsin = fetched.first().primaryAuthorAsin
                    ).orEmpty()
                    TrackType.NARRATOR -> AudibleApiClient.fetchNarratorPortraitUrl(item.queryValue).orEmpty()
                    else -> fetched.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty()
                }
                if (candidateThumb.isNotBlank()) {
                    dao.updateTrackedItemThumbnail(item.id, candidateThumb)
                }
            }

            val tagLabel = item.type.name
            for (prod in fetched) {
                val entry = groupedProducts.getOrPut(prod.asin) { prod to linkedSetOf() }
                entry.second.add(tagLabel)
            }

            if (idx < trackedItems.lastIndex) {
                delay(180L)
            }
        }

        val mergedList = mutableListOf<ReleaseEntity>()
        val narratorRevealedList = mutableListOf<ReleaseEntity>()

        for ((asin, pair) in groupedProducts) {
            val (prod, tags) = pair
            if (asin.lowercase(Locale.US) in mutedBookAsins) continue
            if (prod.seriesTitle.isNotBlank() && prod.seriesTitle.lowercase(Locale.US) in mutedSeriesKeys) continue
            if (prod.seriesAsin.isNotBlank() && prod.seriesAsin.lowercase(Locale.US) in mutedSeriesKeys) continue

            val isAbridged = prod.formatType.contains("abridged", ignoreCase = true) &&
                !prod.formatType.contains("unabridged", ignoreCase = true)
            val isDramatized = prod.title.contains("dramatized", ignoreCase = true) ||
                prod.subtitle.contains("dramatized", ignoreCase = true) ||
                prod.narrators.any { it.contains("full cast", ignoreCase = true) }

            if (prefs.englishOnly && !prod.language.equals("english", ignoreCase = true)) continue
            if (prefs.hideAbridged && isAbridged) continue
            if (prefs.hideDramatized && isDramatized) continue

            val sKey = prod.seriesTitle.lowercase(Locale.US)
            val minSeq = seriesLastReadMap[sKey] ?: 0
            if (minSeq > 0 && prod.seriesSequence != null && prod.seriesSequence <= minSeq) {
                continue
            }

            val prev = existingByAsin[asin]
            val narrStr = prod.narrators.joinToString(", ").ifBlank { "TBA" }
            val prevNarrTba = prev != null && (prev.narrators.equals("TBA", ignoreCase = true) || prev.narrators.isBlank())
            val nowHasNarr = !narrStr.equals("TBA", ignoreCase = true) && narrStr.isNotBlank()
            val justAnnouncedNarr = (prevNarrTba && nowHasNarr) || (prev?.narratorJustAnnounced == true)

            val prevDateIso = if (prev != null && prev.releaseDateIso != prod.releaseDateIso) {
                prev.releaseDateIso
            } else {
                prev?.previousReleaseDateIso.orEmpty()
            }

            val entity = ReleaseEntity(
                asin = asin,
                title = prod.title,
                subtitle = prod.subtitle,
                authors = prod.authors.joinToString(", ").ifBlank { "Unknown Author" },
                narrators = narrStr,
                seriesTitle = prod.seriesTitle,
                seriesAsin = prod.seriesAsin,
                seriesSequence = prod.seriesSequence,
                releaseDateIso = prod.releaseDateIso,
                language = prod.language.replaceFirstChar { it.titlecase(Locale.US) },
                isAbridged = isAbridged,
                isDramatized = isDramatized,
                matchedTags = tags.joinToString(", "),
                isNewDiscovery = prev?.isNewDiscovery ?: (existingByAsin.isNotEmpty()),
                notifiedSevenDay = prev?.notifiedSevenDay ?: false,
                notifiedReleaseDay = prev?.notifiedReleaseDay ?: false,
                firstSeenEpochMs = prev?.firstSeenEpochMs ?: System.currentTimeMillis(),
                coverUrl = prod.coverUrl.ifBlank { prev?.coverUrl.orEmpty() },
                synopsis = prod.synopsis.ifBlank { prev?.synopsis.orEmpty() },
                runtimeMinutes = if (prod.runtimeMinutes > 0) prod.runtimeMinutes else (prev?.runtimeMinutes ?: 0),
                publisher = prod.publisher.ifBlank { prev?.publisher.orEmpty() },
                altAsins = prev?.altAsins.orEmpty(),
                previousReleaseDateIso = prevDateIso,
                narratorJustAnnounced = justAnnouncedNarr,
                abbAvailable = false,
                abbPostUrl = "",
                abbMagnetUri = "",
                notifiedAbbAvailable = false
            )
            mergedList.add(entity)
            if (prevNarrTba && nowHasNarr) {
                narratorRevealedList.add(entity)
            }
        }

        dao.replaceSyncedReleases(mergedList)

        val today = LocalDate.now()
        val sevenDayList = mutableListOf<ReleaseEntity>()
        val releaseDayList = mutableListOf<ReleaseEntity>()

        for (rel in mergedList) {
            val rDate = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull() ?: continue
            val daysUntil = ChronoUnit.DAYS.between(today, rDate)
            if (daysUntil in 1..7 && !rel.notifiedSevenDay) {
                sevenDayList.add(rel)
            } else if (daysUntil == 0L && !rel.notifiedReleaseDay) {
                releaseDayList.add(rel)
            }
        }

        AudibleApiClient.pruneStaleDiskCache()
        setLastSyncEpochMs(System.currentTimeMillis())

        SyncNotificationBatch(
            sevenDayOut = sevenDayList,
            releaseDay = releaseDayList,
            narratorRevealed = narratorRevealedList
        )
    }
}
