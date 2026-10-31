package com.plaintext.audible.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
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
data class FilterPrefs(
    val englishOnly: Boolean = true,
    val hideAbridged: Boolean = true,
    val hideDramatized: Boolean = false,
    val enableDirectAdd: Boolean = false,
    val alwaysShowCardActions: Boolean = false,
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

    init {
        AudibleApiClient.initCache(context.applicationContext.cacheDir)
    }

    fun getPrefs(): FilterPrefs = FilterPrefs(
        englishOnly = prefs.getBoolean("english_only", true),
        hideAbridged = prefs.getBoolean("hide_abridged", true),
        hideDramatized = prefs.getBoolean("hide_dramatized", false),
        enableDirectAdd = prefs.getBoolean("enable_direct_add", false),
        alwaysShowCardActions = prefs.getBoolean("always_show_card_actions", false),
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
            .putString("theme_mode", newPrefs.themeModeCode)
            .putInt("notify_hour", newPrefs.notifyHour)
            .putInt("notify_minute", newPrefs.notifyMinute)
            .putString("marketplace_code", newPrefs.marketplaceCode)
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
            if (existing.thumbnailUrl.isBlank() && initialThumbnailUrl.isNotBlank()) {
                dao.updateTrackedItemThumbnail(existing.id, initialThumbnailUrl)
            }
            return false
        }

        // For Authors, resolve the exact official Audible author page photo first
        val resolvedThumb = when (type) {
            TrackType.AUTHOR -> {
                AudibleApiClient.fetchAudibleAuthorImageUrl(cleaned, externalId) ?: initialThumbnailUrl
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

    /**
     * Normalizes book titles so US/UK regional variants, subtitle variants ("Book 13: A LitRPG Adventure"),
     * and Roman numeral variants ("Book IV" vs "Book 4") collapse into a single canonical key.
     */
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

    /**
     * Scores two duplicate editions of the same book so the primary edition always keeps:
     * 1) Real cover art (over blank/missing cover art)
     * 2) Populated series title & sequence metadata
     * 3) Longer/populated synopsis and runtime
     * 4) Earliest scheduled release date (e.g., 2026-10-06 over 2026-10-15, or 2027-08-10 over 2027-08-12)
     */
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
        val tracked = dao.getAllTrackedItems()
        if (tracked.isEmpty()) {
            dao.clearAllReleases()
            return emptyList()
        }

        val dndRules = dao.getAllDndRules()
        val existingByAsin = dao.getAllReleases().associateBy { it.asin }
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
                                forceRefresh = forceRefresh
                            )
                            TrackType.NARRATOR -> AudibleApiClient.searchCatalog(
                                narrator = item.queryValue,
                                forceRefresh = forceRefresh
                            )
                            TrackType.SERIES -> AudibleApiClient.searchCatalog(
                                titleOrSeries = item.queryValue,
                                forceRefresh = forceRefresh
                            )
                            TrackType.BOOK -> AudibleApiClient.searchCatalog(
                                asin = item.externalId ?: item.queryValue,
                                titleOrSeries = item.queryValue,
                                forceRefresh = forceRefresh
                            )
                        }
                        item to results
                    }
                }
            }.awaitAll()
        }

        // Upgrade thumbnails and normalize display casing for followed items
        val assignedThumbnails = tracked.mapNotNull { it.thumbnailUrl.takeIf { u -> u.isNotBlank() } }.toMutableSet()
        val bookCoversSet = fetchedPairs.flatMap { it.second }.mapNotNull { it.coverUrl.takeIf { u -> u.isNotBlank() } }.toSet()

        for ((item, results) in fetchedPairs) {
            // Canonicalize name casing (e.g., "andy weir" -> "Andy Weir", "Brandon sanderson" -> "Brandon Sanderson")
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

            // Detect if an AUTHOR item is currently showing a book cover instead of an Audible author portrait
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

        // Track highest already-released sequence number per series for Smart Re-Issue Guard
        val releasedMaxSeqBySeries = mutableMapOf<String, Int>()
        val trackedSeriesLastRead = tracked.filter { it.type == TrackType.SERIES }
            .associate { it.queryValue.lowercase(Locale.US) to it.lastReadSequence }

        for ((_, results) in fetchedPairs) {
            for (prod in results) {
                val sKey = prod.seriesTitle.trim().lowercase(Locale.US)
                val seq = prod.seriesSequence
                if (sKey.isNotBlank() && seq != null && prod.releaseDateIso != "TBA" && prod.releaseDateIso <= todayIso) {
                    val prevMax = releasedMaxSeqBySeries[sKey] ?: 0
                    if (seq > prevMax) releasedMaxSeqBySeries[sKey] = seq
                }
            }
        }

        val groupedEditions = linkedMapOf<String, EditionGroup>()

        for ((item, results) in fetchedPairs) {
            val qNorm = item.queryValue.trim()
            for (prod in results) {
                // Strict per-type relevance verification so podcasts, unrelated series, or anthology credits don't leak in
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
                        }
                        if (!authorMatches) continue
                    }
                    TrackType.NARRATOR -> {
                        val narratorMatches = prod.narrators.any { n ->
                            n.equals(qNorm, ignoreCase = true) || n.contains(qNorm, ignoreCase = true)
                        }
                        if (!narratorMatches) continue
                    }
                    TrackType.BOOK -> {
                        val bookMatches = prod.asin.equals(qNorm, ignoreCase = true) ||
                            prod.title.contains(qNorm, ignoreCase = true)
                        if (!bookMatches) continue
                    }
                }

                if (item.type != TrackType.BOOK &&
                    prod.releaseDateIso != "TBA" &&
                    prod.releaseDateIso < recentCutoffIso
                ) {
                    continue
                }

                // Smart Re-Issue Guard: if a future listing has a series sequence <= user's lastReadSequence
                // (or <= an already-released book's sequence when it's an older installment re-issue), skip it
                if (prod.seriesTitle.isNotBlank() && prod.seriesSequence != null && prod.releaseDateIso > todayIso) {
                    val sKey = prod.seriesTitle.trim().lowercase(Locale.US)
                    val userLastRead = trackedSeriesLastRead.entries
                        .firstOrNull { (k, _) -> sKey.contains(k) || k.contains(sKey) }?.value ?: 0
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

                // Deduplicate across regional release dates by grouping on normalized title + primary author
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

        val finalEntities = groupedEditions.values.map { group ->
            val prod = group.primaryProduct
            val prev = existingByAsin[prod.asin]
            val isDramatized = prod.title.contains("Dramatized", ignoreCase = true) ||
                prod.subtitle.contains("Dramatized", ignoreCase = true)
            ReleaseEntity(
                asin = prod.asin,
                title = prod.title,
                subtitle = prod.subtitle,
                authors = prod.authors.joinToString(", ").ifBlank { "Unknown Author" },
                narrators = prod.narrators.joinToString(", ").ifBlank { "TBA" },
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
                altAsins = group.editionAsins.joinToString(",")
            )
        }

        dao.replaceSyncedReleases(finalEntities)
        return dao.getAllReleases()
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
