package com.plaintext.audible.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

data class FilterPrefs(
    val englishOnly: Boolean = true,
    val hideAbridged: Boolean = true,
    val hideDramatized: Boolean = false,
    val isDarkTheme: Boolean = true,
    val notifyHour: Int = 9,
    val notifyMinute: Int = 0
)

class ReleaseRepository(context: Context) {
    private val db = AppDatabase.get(context)
    val dao = db.dao()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pts_audible_prefs", Context.MODE_PRIVATE)

    fun getPrefs(): FilterPrefs = FilterPrefs(
        englishOnly = prefs.getBoolean("english_only", true),
        hideAbridged = prefs.getBoolean("hide_abridged", true),
        hideDramatized = prefs.getBoolean("hide_dramatized", false),
        isDarkTheme = prefs.getBoolean("dark_theme", true),
        notifyHour = prefs.getInt("notify_hour", 9),
        notifyMinute = prefs.getInt("notify_minute", 0)
    )

    fun savePrefs(newPrefs: FilterPrefs) {
        prefs.edit()
            .putBoolean("english_only", newPrefs.englishOnly)
            .putBoolean("hide_abridged", newPrefs.hideAbridged)
            .putBoolean("hide_dramatized", newPrefs.hideDramatized)
            .putBoolean("dark_theme", newPrefs.isDarkTheme)
            .putInt("notify_hour", newPrefs.notifyHour)
            .putInt("notify_minute", newPrefs.notifyMinute)
            .apply()
    }

    suspend fun syncCatalog(): List<ReleaseEntity> {
        val tracked = dao.getAllTrackedItems()
        val dndRules = dao.getAllDndRules()
        val existingByAsin = dao.getAllReleases().associateBy { it.asin }
        val filterPrefs = getPrefs()
        val todayIso = LocalDate.now().toString()

        val dndBooks = dndRules.filter { it.targetType == DndTargetType.BOOK }
            .map { it.targetKey.lowercase() }.toSet()
        val dndSeries = dndRules.filter { it.targetType == DndTargetType.SERIES }
            .map { it.targetKey.lowercase() }.toSet()

        val mergedMap = linkedMapOf<String, Pair<CatalogProduct, MutableSet<String>>>()

        for (item in tracked) {
            val results = when (item.type) {
                TrackType.AUTHOR -> AudibleApiClient.searchCatalog(author = item.queryValue)
                TrackType.NARRATOR -> AudibleApiClient.searchCatalog(narrator = item.queryValue)
                TrackType.SERIES -> AudibleApiClient.searchCatalog(titleOrSeries = item.queryValue)
                TrackType.BOOK -> AudibleApiClient.searchCatalog(
                    asin = item.externalId ?: item.queryValue,
                    titleOrSeries = item.queryValue
                )
            }

            for (prod in results) {
                if (item.type == TrackType.SERIES &&
                    !prod.seriesTitle.contains(item.queryValue, ignoreCase = true) &&
                    !prod.title.contains(item.queryValue, ignoreCase = true)
                ) {
                    continue
                }

                if (item.type != TrackType.BOOK &&
                    prod.releaseDateIso != "TBA" &&
                    prod.releaseDateIso < todayIso
                ) {
                    continue
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

                if (prod.asin.lowercase() in dndBooks) continue
                if (prod.seriesTitle.isNotBlank() && prod.seriesTitle.lowercase() in dndSeries) continue
                if (prod.seriesAsin.isNotBlank() && prod.seriesAsin.lowercase() in dndSeries) continue

                val entry = mergedMap.getOrPut(prod.asin) { prod to linkedSetOf() }
                entry.second.add(item.type.name)
            }
        }

        val finalEntities = mergedMap.values.map { (prod, matchedSet) ->
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
                matchedTags = matchedSet.joinToString(","),
                isNewDiscovery = prev?.isNewDiscovery ?: true,
                notifiedSevenDay = prev?.notifiedSevenDay ?: false,
                notifiedReleaseDay = prev?.notifiedReleaseDay ?: false,
                firstSeenEpochMs = prev?.firstSeenEpochMs ?: System.currentTimeMillis()
            )
        }

        if (finalEntities.isNotEmpty()) {
            dao.upsertReleases(finalEntities)
        }
        return dao.getAllReleases()
    }

    suspend fun muteBook(asin: String, title: String) {
        dao.insertDndRule(
            DndRuleEntity(
                targetKey = asin.lowercase(),
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
                targetKey = seriesKey.lowercase(),
                targetType = DndTargetType.SERIES,
                displayTitle = displayTitle
            )
        )
        dao.deleteReleasesBySeries(seriesKey)
    }
}
