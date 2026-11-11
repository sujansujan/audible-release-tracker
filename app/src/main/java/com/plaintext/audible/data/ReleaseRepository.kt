package com.plaintext.audible.data

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ReleaseRepository(
    private val context: Context,
    private val db: AppDatabase
) {
    private val dao = db.trackerDao()

    val authorsFlow: Flow<List<AuthorEntity>> = dao.observeAuthors()
    val releasesFlow: Flow<List<ReleaseEntity>> = dao.observeReleases()
    val syncLogsFlow: Flow<List<SyncLogEntity>> = dao.observeRecentLogs()

    fun getSetting(key: String, default: String): String {
        return dao.getSetting(key) ?: default
    }

    fun setSetting(key: String, value: String) {
        dao.setSetting(AppSettingEntity(key, value))
    }

    suspend fun clearAuthorFilterIfRemoved(authorName: String?) {
        if (authorName == null) return
        val exists = dao.getAuthorsList().any { it.name.equals(authorName, ignoreCase = true) }
        if (!exists) {
            dao.deleteAuthorReleases(authorName)
        }
    }

    suspend fun searchCatalogAuthors(query: String, marketplace: String): List<AuthorCatalogSuggestion> {
        val mkt = AudibleApiClient.MARKETPLACES.find { it.code == marketplace }
            ?: AudibleApiClient.MARKETPLACES.first()
        return AudibleApiClient.searchAuthorSuggestions(query, mkt)
    }

    suspend fun refreshAuthorPortrait(author: AuthorEntity): Boolean {
        val mkt = AudibleApiClient.MARKETPLACES.find { it.code == author.marketplace }
            ?: AudibleApiClient.MARKETPLACES.first()
        val info = AudibleApiClient.fetchAuthorMetadata(author.name, author.asin, mkt)
        if (!info.imageUrl.isNullOrBlank() || !info.asin.isNullOrBlank() || !info.description.isNullOrBlank()) {
            dao.insertAuthor(
                author.copy(
                    asin = info.asin ?: author.asin,
                    imageUrl = info.imageUrl ?: author.imageUrl,
                    bio = info.description ?: author.bio
                )
            )
            return true
        }
        return false
    }

    suspend fun addAuthorAndSync(
        nameInput: String,
        marketplaceCode: String,
        explicitAsin: String? = null,
        initialImageUrl: String? = null
    ): Int {
        val cleanName = nameInput.trim()
        if (cleanName.isBlank()) return 0
        val existing = dao.getAuthorByName(cleanName)
        val mkt = AudibleApiClient.MARKETPLACES.find { it.code == marketplaceCode }
            ?: AudibleApiClient.MARKETPLACES.first()

        val meta = AudibleApiClient.fetchAuthorMetadata(cleanName, explicitAsin ?: existing?.asin, mkt)
        val canonicalName = meta.name.ifBlank { cleanName }
        val authorEntity = AuthorEntity(
            id = existing?.id ?: 0,
            name = canonicalName,
            asin = explicitAsin ?: meta.asin ?: existing?.asin,
            marketplace = mkt.code,
            addedEpochMillis = existing?.addedEpochMillis ?: System.currentTimeMillis(),
            lastCheckedEpochMillis = System.currentTimeMillis(),
            imageUrl = meta.imageUrl ?: initialImageUrl ?: existing?.imageUrl,
            bio = meta.description ?: existing?.bio
        )
        dao.insertAuthor(authorEntity)
        return syncSingleAuthor(authorEntity)
    }

    suspend fun removeAuthor(author: AuthorEntity) {
        dao.deleteAuthorReleases(author.name)
        dao.deleteAuthor(author)
    }

    suspend fun toggleReleasePin(asin: String, currentPinned: Boolean) {
        dao.setReleasePinned(asin, !currentPinned)
    }

    suspend fun setBookAlertMute(asin: String, muted: Boolean) {
        dao.setBookAlertsMuted(asin, muted)
    }

    suspend fun checkSingleReleaseOnAbb(rel: ReleaseEntity): AudibleApiClient.AbbMatchResult {
        val customMirrorsRaw = getSetting("abb_mirrors", AudibleApiClient.DEFAULT_ABB_MIRRORS.joinToString(","))
        val mirrors = customMirrorsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { AudibleApiClient.DEFAULT_ABB_MIRRORS }

        val match = AudibleApiClient.checkAudioBookBayAvailability(
            context = context,
            title = rel.title,
            authorName = rel.authorName,
            seriesName = rel.seriesTitle,
            releaseDateIso = rel.releaseDateIso,
            mirrors = mirrors
        )
        if (match.available) {
            dao.updateAbbAvailability(
                asin = rel.asin,
                available = true,
                url = match.postUrl,
                magnetUri = match.magnetUri ?: rel.abbMagnetUri,
                notified = rel.notifiedAbbAvailable
            )
        }
        return match
    }

    fun buildAbbSearchUrl(title: String, authorName: String): String {
        val customMirrorsRaw = getSetting("abb_mirrors", AudibleApiClient.DEFAULT_ABB_MIRRORS.joinToString(","))
        val primaryMirror = customMirrorsRaw.split(",")
            .map { it.trim().removeSuffix("/") }
            .firstOrNull { it.isNotEmpty() }
            ?: AudibleApiClient.DEFAULT_ABB_MIRRORS.first()

        val cleanTitle = AudibleApiClient.cleanTitleForAbbSearch(title)
        val cleanAuthor = authorName
            .replace(Regex("[^A-Za-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val query = "$cleanTitle $cleanAuthor".trim().lowercase()
        val encoded = query.toUri().toString()
        val safeEncoded = java.net.URLEncoder.encode(query, "UTF-8")
        return "$primaryMirror/?s=$safeEncoded&cat=undefined%2Cundefined"
    }

    suspend fun syncSingleAuthor(author: AuthorEntity): Int {
        val mkt = AudibleApiClient.MARKETPLACES.find { it.code == author.marketplace }
            ?: AudibleApiClient.MARKETPLACES.first()

        if (author.imageUrl.isNullOrBlank() || author.bio.isNullOrBlank()) {
            val meta = AudibleApiClient.fetchAuthorMetadata(author.name, author.asin, mkt)
            if (!meta.imageUrl.isNullOrBlank() || !meta.description.isNullOrBlank() || !meta.asin.isNullOrBlank()) {
                dao.insertAuthor(
                    author.copy(
                        asin = meta.asin ?: author.asin,
                        imageUrl = meta.imageUrl ?: author.imageUrl,
                        bio = meta.description ?: author.bio
                    )
                )
            }
        }

        val existingMap = dao.getReleasesList().associateBy { it.asin }
        val fetched = AudibleApiClient.fetchReleasesForAuthor(
            authorName = author.name,
            authorAsin = author.asin,
            marketplace = mkt
        )
        val now = System.currentTimeMillis()
        val today = LocalDate.now(ZoneId.systemDefault())
        var newOrUpdatedCount = 0

        val mergedEntities = fetched.map { item ->
            val prev = existingMap[item.asin]
            val parsedDate = runCatching { LocalDate.parse(item.releaseDateIso) }.getOrNull()
            val isFuture = parsedDate != null && parsedDate.isAfter(today)
            if (prev == null) newOrUpdatedCount++

            ReleaseEntity(
                asin = item.asin,
                title = item.title,
                subtitle = item.subtitle,
                authorName = author.name,
                narratorName = item.narratorName,
                seriesTitle = item.seriesTitle,
                seriesPosition = item.seriesPosition,
                releaseDateIso = item.releaseDateIso,
                releaseEpochDay = item.releaseEpochDay,
                runtimeMinutes = item.runtimeMinutes,
                language = item.language,
                marketplace = mkt.code,
                productUrl = item.productUrl,
                imageUrl = item.imageUrl ?: prev?.imageUrl,
                summary = item.summary ?: prev?.summary,
                isPreorder = isFuture,
                isPinned = prev?.isPinned ?: false,
                notifiedPreorder = prev?.notifiedPreorder ?: false,
                notifiedReleaseDay = prev?.notifiedReleaseDay ?: false,
                firstSeenEpochMillis = prev?.firstSeenEpochMillis ?: now,
                abbAvailable = prev?.abbAvailable ?: false,
                abbUrl = prev?.abbUrl,
                abbMagnetUri = prev?.abbMagnetUri,
                notifiedAbbAvailable = prev?.notifiedAbbAvailable ?: false,
                muteBookAlerts = prev?.muteBookAlerts ?: false
            )
        }

        if (mergedEntities.isNotEmpty()) {
            dao.upsertReleases(mergedEntities)
        }
        dao.updateAuthorLastChecked(author.id, now)
        return newOrUpdatedCount
    }

    data class SyncSummary(
        val authorsChecked: Int,
        val totalReleases: Int,
        val newPreorders: List<ReleaseEntity>,
        val releasedToday: List<ReleaseEntity>,
        val newlyAvailableOnAbb: List<ReleaseEntity>
    )

    suspend fun syncAllAuthors(checkAbb: Boolean = true): SyncSummary {
        val authors = dao.getAuthorsList()
        val beforeAsins = dao.getReleasesList().map { it.asin }.toSet()

        authors.forEachIndexed { idx, author ->
            syncSingleAuthor(author)
            if (idx < authors.lastIndex) {
                delay(350L)
            }
        }

        val todayEpoch = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val allReleases = dao.getReleasesList()

        val unnotifiedPreorders = allReleases.filter { rel ->
            rel.releaseEpochDay > todayEpoch &&
                !rel.notifiedPreorder &&
                !rel.muteBookAlerts &&
                !beforeAsins.contains(rel.asin)
        }

        val unnotifiedToday = allReleases.filter { rel ->
            rel.releaseEpochDay == todayEpoch &&
                !rel.notifiedReleaseDay &&
                !rel.muteBookAlerts
        }

        val newlyAbb = mutableListOf<ReleaseEntity>()
        val abbEnabled = getSetting("abb_check_enabled", "true") == "true"

        if (checkAbb && abbEnabled) {
            val customMirrorsRaw = getSetting("abb_mirrors", AudibleApiClient.DEFAULT_ABB_MIRRORS.joinToString(","))
            val mirrors = customMirrorsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { AudibleApiClient.DEFAULT_ABB_MIRRORS }

            // Check recent releases (within last 60 days or upcoming within 3 days) or pinned books
            val candidates = allReleases.filter { rel ->
                !rel.abbAvailable &&
                    (rel.isPinned || (rel.releaseEpochDay in (todayEpoch - 60)..(todayEpoch + 3)))
            }.sortedByDescending { it.releaseEpochDay }.take(8)

            for (candidate in candidates) {
                val match = AudibleApiClient.checkAudioBookBayAvailability(
                    context = context,
                    title = candidate.title,
                    authorName = candidate.authorName,
                    seriesName = candidate.seriesTitle,
                    releaseDateIso = candidate.releaseDateIso,
                    mirrors = mirrors
                )
                if (match.available) {
                    val updated = candidate.copy(
                        abbAvailable = true,
                        abbUrl = match.postUrl,
                        abbMagnetUri = match.magnetUri ?: candidate.abbMagnetUri
                    )
                    dao.updateAbbAvailability(
                        asin = candidate.asin,
                        available = true,
                        url = match.postUrl,
                        magnetUri = match.magnetUri ?: candidate.abbMagnetUri,
                        notified = candidate.notifiedAbbAvailable
                    )
                    if (!candidate.notifiedAbbAvailable && !candidate.muteBookAlerts) {
                        newlyAbb.add(updated)
                    }
                }
                delay(400L)
            }
        }

        val now = System.currentTimeMillis()
        dao.insertSyncLog(
            SyncLogEntity(
                timestampEpochMillis = now,
                authorsChecked = authors.size,
                releasesFound = allReleases.size,
                newAlertsCount = unnotifiedPreorders.size + unnotifiedToday.size + newlyAbb.size,
                statusMessage = "OK (${authors.size} authors, ${allReleases.size} titles, ${newlyAbb.size} ABB)"
            )
        )
        val thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli()
        dao.pruneOldLogs(thirtyDaysAgo)

        return SyncSummary(
            authorsChecked = authors.size,
            totalReleases = allReleases.size,
            newPreorders = unnotifiedPreorders,
            releasedToday = unnotifiedToday,
            newlyAvailableOnAbb = newlyAbb
        )
    }

    suspend fun markNotifiedPreorder(asin: String) = dao.markPreorderNotified(asin)
    suspend fun markNotifiedReleaseDay(asin: String) = dao.markReleaseDayNotified(asin)
    suspend fun markNotifiedAbb(asin: String) = dao.markAbbNotified(asin)
}
