package com.plaintext.audible.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plaintext.audible.data.*
import com.plaintext.audible.worker.ReleaseCheckWorker
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class ActiveTab {
    UPCOMING, TRACKED, SETTINGS
}

enum class QuickFilter(val label: String) {
    ALL("ALL"),
    SERIES("SERIES"),
    AUTHOR("AUTHOR"),
    NARRATOR("NARRATOR"),
    SOON_30D("<=30d"),
    RECENT_30D("RECENT"),
    DND("DND")
}

enum class TrackedSourceFilter(val label: String, val trackType: TrackType?) {
    ALL("ALL", null),
    SERIES("SERIES", TrackType.SERIES),
    AUTHOR("AUTHOR", TrackType.AUTHOR),
    NARRATOR("NARRATOR", TrackType.NARRATOR),
    BOOK("BOOK", TrackType.BOOK)
}

@Composable
fun TrackerScreen(
    repo: ReleaseRepository,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prefs by remember { mutableStateOf(repo.getPrefs()) }
    val palette = if (prefs.isDarkTheme) DraculaPalette else AlucardPalette

    val releases by repo.dao.observeReleases().collectAsState(initial = emptyList())
    val trackedItems by repo.dao.observeTrackedItems().collectAsState(initial = emptyList())
    val dndRules by repo.dao.observeDndRules().collectAsState(initial = emptyList())

    var activeTab by remember { mutableStateOf(ActiveTab.UPCOMING) }
    var activeFilter by remember { mutableStateOf(QuickFilter.ALL) }
    var isSyncing by remember { mutableStateOf(false) }
    var showTbaExpanded by remember { mutableStateOf(false) }
    var showAboutPage by remember { mutableStateOf(false) }

    val todayIso = remember { LocalDate.now().toString() }

    LaunchedEffect(Unit) {
        repo.syncCatalog(forceRefresh = false)
    }

    var isBottomBarVisible by remember { mutableStateOf(true) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) {
                    isBottomBarVisible = false
                } else if (available.y > 6f) {
                    isBottomBarVisible = true
                }
                return Offset.Zero
            }
        }
    }

    val seriesReadMap = remember(trackedItems) {
        trackedItems.filter { it.type == TrackType.SERIES }
            .associateBy({ it.queryValue.lowercase() }, { it })
    }

    val handleAddTracked: (TrackType, String, Int) -> Unit = { type, query, lastRead ->
        scope.launch {
            val cleaned = query.trim()
            val added = repo.addTrackedItemUnique(type, cleaned, lastRead)
            if (added) {
                Toast.makeText(context, "[ + Tracked ${type.name}: $cleaned ]", Toast.LENGTH_SHORT).show()
                isSyncing = true
                repo.syncCatalog(forceRefresh = false)
                isSyncing = false
            } else {
                Toast.makeText(context, "[ Already Tracked: $cleaned ]", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val upcomingCount = remember(releases, todayIso) {
        releases.count { it.releaseDateIso != "TBA" && it.releaseDateIso >= todayIso }
    }

    CompositionLocalProvider(LocalPtsPalette provides palette) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .systemBarsPadding()
                .nestedScroll(nestedScrollConnection)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // Headerless Top Utility Bar (No app name shown anywhere except ABOUT page)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "[ ${activeTab.name} // ${prefs.marketplace.code} ]",
                        color = palette.comment,
                        fontSizeSp = 13,
                        bold = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BracketButton(
                            label = if (isSyncing) "SYNCING..." else "SYNC",
                            color = palette.green,
                            onClick = {
                                if (!isSyncing) {
                                    scope.launch {
                                        isSyncing = true
                                        repo.syncCatalog(forceRefresh = true)
                                        isSyncing = false
                                    }
                                }
                            }
                        )
                        BracketButton(
                            label = palette.name,
                            color = palette.orange,
                            onClick = {
                                val updated = prefs.copy(isDarkTheme = !prefs.isDarkTheme)
                                prefs = updated
                                repo.savePrefs(updated)
                            }
                        )
                    }
                }

                HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
                Spacer(modifier = Modifier.height(6.dp))

                Box(modifier = Modifier.weight(1f)) {
                    when (activeTab) {
                        ActiveTab.UPCOMING -> UpcomingFeedView(
                            releases = releases,
                            dndRules = dndRules,
                            quickFilter = activeFilter,
                            trackedSeriesMap = seriesReadMap,
                            showTbaExpanded = showTbaExpanded,
                            onToggleTba = { showTbaExpanded = !showTbaExpanded },
                            onClearNewBadges = { scope.launch { repo.dao.clearNewDiscoveryFlags() } },
                            onOpenAudibleSearch = { rel ->
                                val domain = prefs.marketplace.domain
                                val primaryAuthor = rel.authors.split(",").firstOrNull()?.trim().orEmpty()
                                val q = URLEncoder.encode("${rel.title} $primaryAuthor".trim(), "UTF-8")
                                val url = "https://www.$domain/search?keywords=$q&ipRedirectOverride=true&overrideBaseCountry=true"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            onOpenAudioBookBay = { rel ->
                                val primaryAuthor = rel.authors.split(",").firstOrNull()?.trim().orEmpty()
                                val q = URLEncoder.encode("${rel.title} $primaryAuthor".trim().lowercase(), "UTF-8")
                                val abbUrl = "https://audiobookbay.lu/?s=$q&cat=undefined%2Cundefined"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(abbUrl)))
                            },
                            onCopyRelease = { rel ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val seriesPart = if (rel.seriesTitle.isNotBlank()) {
                                    " (${rel.seriesTitle}${rel.seriesSequence?.let { " #$it" }.orEmpty()})"
                                } else ""
                                val summary = "${rel.title}$seriesPart — By ${rel.authors} • Narrated by ${rel.narrators} • Release: ${rel.releaseDateIso}"
                                clipboard.setPrimaryClip(ClipData.newPlainText("Audiobook Release", summary))
                                Toast.makeText(context, "[ Copied to Clipboard ]", Toast.LENGTH_SHORT).show()
                            },
                            onExportCalendar = { rel ->
                                val date = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
                                if (date != null) {
                                    val startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                                    val calIntent = Intent(Intent.ACTION_INSERT).apply {
                                        data = CalendarContract.Events.CONTENT_URI
                                        putExtra(CalendarContract.Events.TITLE, "Audiobook Release: ${rel.title}")
                                        putExtra(
                                            CalendarContract.Events.DESCRIPTION,
                                            "By ${rel.authors} • Narrated by ${rel.narrators} • Series: ${rel.seriesTitle} • ASIN: ${rel.asin}"
                                        )
                                        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + 86_400_000L)
                                    }
                                    runCatching { context.startActivity(calIntent) }.onFailure {
                                        Toast.makeText(context, "No Calendar app found", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onQuickTrack = { type, value -> handleAddTracked(type, value, 0) },
                            onMuteBook = { rel -> scope.launch { repo.muteBook(rel.asin, rel.title) } },
                            onMuteSeries = { rel ->
                                scope.launch { repo.muteSeries(rel.seriesTitle, rel.seriesTitle) }
                            },
                            onUnmuteRule = { rule ->
                                scope.launch {
                                    repo.dao.deleteDndRule(rule)
                                    repo.syncCatalog(forceRefresh = false)
                                }
                            },
                            onMarkSeriesSeqRead = { trackedSeries, seq ->
                                scope.launch {
                                    repo.dao.updateSeriesReadSequence(trackedSeries.id, seq)
                                    Toast.makeText(context, "[ Marked #${seq} Read ]", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        ActiveTab.TRACKED -> TrackedEntitiesView(
                            trackedItems = trackedItems,
                            releases = releases,
                            enableDirectAdd = prefs.enableDirectAdd,
                            onAddTracked = handleAddTracked,
                            onDeleteTracked = { item ->
                                scope.launch {
                                    repo.dao.deleteTrackedItem(item)
                                    repo.syncCatalog(forceRefresh = false)
                                }
                            },
                            onUpdateSeriesSeq = { item, newSeq ->
                                scope.launch { repo.dao.updateSeriesReadSequence(item.id, newSeq) }
                            },
                            onOpenAbbQuery = { rawQuery ->
                                val q = URLEncoder.encode(rawQuery.trim().lowercase(), "UTF-8")
                                val abbUrl = "https://audiobookbay.lu/?s=$q&cat=undefined%2Cundefined"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(abbUrl)))
                            }
                        )

                        ActiveTab.SETTINGS -> {
                            if (showAboutPage) {
                                AboutPageView(onBack = { showAboutPage = false })
                            } else {
                                SettingsAndBackupView(
                                    prefs = prefs,
                                    onUpdatePrefs = { updated ->
                                        prefs = updated
                                        repo.savePrefs(updated)
                                        ReleaseCheckWorker.scheduleDailyCheck(
                                            context,
                                            updated.notifyHour,
                                            updated.notifyMinute
                                        )
                                        scope.launch { repo.syncCatalog(forceRefresh = false) }
                                    },
                                    onOpenAbout = { showAboutPage = true },
                                    onExportBackup = onExportBackup,
                                    onImportBackup = onImportBackup
                                )
                            }
                        }
                    }
                }
            }

            // Auto-Hiding Bottom Navigation Bar & UPCOMING Filter Strip
            AnimatedVisibility(
                visible = isBottomBarVisible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = palette.background,
                    border = BorderStroke(1.dp, palette.surfaceLine),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (activeTab == ActiveTab.UPCOMING) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MonoText(text = "FLT:", color = palette.comment, fontSizeSp = 11)
                                QuickFilter.values().forEach { filter ->
                                    val chipLabel = if (filter == QuickFilter.DND) {
                                        "DND (${dndRules.size})"
                                    } else {
                                        filter.label
                                    }
                                    val chipColor = when (filter) {
                                        QuickFilter.DND -> palette.red
                                        QuickFilter.RECENT_30D -> palette.orange
                                        else -> palette.purple
                                    }
                                    BracketButton(
                                        label = chipLabel,
                                        active = activeFilter == filter,
                                        color = chipColor,
                                        onClick = { activeFilter = filter }
                                    )
                                }
                            }
                            HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
                        }

                        // 3-Tab Bottom Bar (< UPCOMING | TRACKED | SETTINGS >)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BracketButton(
                                label = "UPCOMING ($upcomingCount)",
                                active = activeTab == ActiveTab.UPCOMING,
                                onClick = { activeTab = ActiveTab.UPCOMING }
                            )
                            BracketButton(
                                label = "TRACKED (${trackedItems.size})",
                                active = activeTab == ActiveTab.TRACKED,
                                onClick = { activeTab = ActiveTab.TRACKED }
                            )
                            BracketButton(
                                label = "SETTINGS",
                                active = activeTab == ActiveTab.SETTINGS,
                                onClick = {
                                    showAboutPage = false
                                    activeTab = ActiveTab.SETTINGS
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingFeedView(
    releases: List<ReleaseEntity>,
    dndRules: List<DndRuleEntity>,
    quickFilter: QuickFilter,
    trackedSeriesMap: Map<String, TrackedItemEntity>,
    showTbaExpanded: Boolean,
    onToggleTba: () -> Unit,
    onClearNewBadges: () -> Unit,
    onOpenAudibleSearch: (ReleaseEntity) -> Unit,
    onOpenAudioBookBay: (ReleaseEntity) -> Unit,
    onCopyRelease: (ReleaseEntity) -> Unit,
    onExportCalendar: (ReleaseEntity) -> Unit,
    onQuickTrack: (TrackType, String) -> Unit,
    onMuteBook: (ReleaseEntity) -> Unit,
    onMuteSeries: (ReleaseEntity) -> Unit,
    onUnmuteRule: (DndRuleEntity) -> Unit,
    onMarkSeriesSeqRead: (TrackedItemEntity, Int) -> Unit
) {
    val palette = LocalPtsPalette.current
    val today = remember { LocalDate.now() }
    val todayIso = remember { today.toString() }

    if (quickFilter == QuickFilter.DND) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                MonoText(
                    text = "DO NOT DISTURB LIST (${dndRules.size}) — Suppressed from all Author, Narrator, and Series syncs:",
                    color = palette.comment,
                    fontSizeSp = 12
                )
            }
            if (dndRules.isEmpty()) {
                item {
                    AsciiBox(headerLabel = "DND FILTER EMPTY") {
                        MonoText(text = "No muted books or series.", color = palette.comment)
                    }
                }
            }
            items(dndRules, key = { "dnd_${it.targetKey}" }) { rule ->
                AsciiBox(
                    headerLabel = "MUTED ${rule.targetType.name}",
                    borderColor = palette.red,
                    headerColor = palette.red
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = "${rule.displayTitle} (${rule.targetKey})",
                            modifier = Modifier.weight(1f)
                        )
                        BracketButton(
                            label = "UNMUTE",
                            color = palette.green,
                            onClick = { onUnmuteRule(rule) }
                        )
                    }
                }
            }
        }
        return
    }

    val filteredReleases = remember(releases, quickFilter, todayIso) {
        releases.filter { rel ->
            val isPastRelease = rel.releaseDateIso != "TBA" && rel.releaseDateIso < todayIso
            when (quickFilter) {
                QuickFilter.RECENT_30D -> isPastRelease
                QuickFilter.ALL -> !isPastRelease
                QuickFilter.SERIES -> !isPastRelease && rel.matchedTags.contains("SERIES", ignoreCase = true)
                QuickFilter.AUTHOR -> !isPastRelease && rel.matchedTags.contains("AUTHOR", ignoreCase = true)
                QuickFilter.NARRATOR -> !isPastRelease && rel.matchedTags.contains("NARRATOR", ignoreCase = true)
                QuickFilter.SOON_30D -> {
                    val d = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(rel.releaseDateIso)) }.getOrNull()
                    d != null && d in 0..30
                }
                QuickFilter.DND -> false
            }
        }
    }

    val scheduled = remember(filteredReleases, quickFilter) {
        val list = filteredReleases.filter { it.releaseDateIso != "TBA" }
        if (quickFilter == QuickFilter.RECENT_30D) {
            list.sortedByDescending { it.releaseDateIso }
        } else {
            list.sortedBy { it.releaseDateIso }
        }
    }

    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US) }
    val groupedByMonth = remember(scheduled) {
        scheduled.groupBy { rel ->
            runCatching {
                YearMonth.from(LocalDate.parse(rel.releaseDateIso)).format(monthFormatter).uppercase()
            }.getOrDefault("SCHEDULED")
        }
    }

    val tbaList = remember(filteredReleases, quickFilter) {
        if (quickFilter == QuickFilter.RECENT_30D) emptyList()
        else filteredReleases.filter { it.releaseDateIso == "TBA" }
    }
    val newCount = remember(releases) { releases.count { it.isNewDiscovery } }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (newCount > 0 && quickFilter != QuickFilter.RECENT_30D) {
            item {
                AsciiBox(
                    headerLabel = "NEW PRE-ORDER DISCOVERED",
                    borderColor = palette.pink,
                    headerColor = palette.pink
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = "$newCount newly listed release(s) found.",
                            color = palette.pink,
                            bold = true
                        )
                        BracketButton(
                            label = "ACK / CLEAR",
                            color = palette.pink,
                            onClick = onClearNewBadges
                        )
                    }
                }
            }
        }

        if (scheduled.isEmpty()) {
            item {
                AsciiBox(headerLabel = "SCOREBOARD EMPTY") {
                    MonoText(
                        text = if (quickFilter == QuickFilter.RECENT_30D) {
                            "No releases from the past 30 days found for your followed sources."
                        } else {
                            "No scheduled upcoming releases match [${quickFilter.label}].\nTap [ TRACKED ] on the bottom bar to search the live catalog."
                        },
                        color = palette.comment
                    )
                }
            }
        }

        groupedByMonth.forEach { (monthTitle, monthReleases) ->
            item(key = "header_$monthTitle") {
                val noun = if (monthReleases.size == 1) "RELEASE" else "RELEASES"
                MonoText(
                    text = "+=== $monthTitle (${monthReleases.size} $noun) ===+",
                    color = palette.purple,
                    fontSizeSp = 13,
                    bold = true,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(monthReleases, key = { it.asin }) { rel ->
                ReleaseScorecard(
                    rel = rel,
                    trackedSeriesMap = trackedSeriesMap,
                    onOpenAudibleSearch = { onOpenAudibleSearch(rel) },
                    onOpenAudioBookBay = { onOpenAudioBookBay(rel) },
                    onCopyRelease = { onCopyRelease(rel) },
                    onExportCalendar = { onExportCalendar(rel) },
                    onQuickTrack = onQuickTrack,
                    onMuteBook = { onMuteBook(rel) },
                    onMuteSeries = { onMuteSeries(rel) },
                    onMarkSeriesSeqRead = onMarkSeriesSeqRead
                )
            }
        }

        if (tbaList.isNotEmpty()) {
            item {
                BracketButton(
                    label = if (showTbaExpanded) "- HIDE TBA / UNSCHEDULED (${tbaList.size})"
                    else "+ SHOW TBA / UNSCHEDULED (${tbaList.size})",
                    color = palette.comment,
                    onClick = onToggleTba
                )
            }
            if (showTbaExpanded) {
                items(tbaList, key = { it.asin }) { rel ->
                    ReleaseScorecard(
                        rel = rel,
                        trackedSeriesMap = trackedSeriesMap,
                        onOpenAudibleSearch = { onOpenAudibleSearch(rel) },
                        onOpenAudioBookBay = { onOpenAudioBookBay(rel) },
                        onCopyRelease = { onCopyRelease(rel) },
                        onExportCalendar = { onExportCalendar(rel) },
                        onQuickTrack = onQuickTrack,
                        onMuteBook = { onMuteBook(rel) },
                        onMuteSeries = { onMuteSeries(rel) },
                        onMarkSeriesSeqRead = onMarkSeriesSeqRead
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseScorecard(
    rel: ReleaseEntity,
    trackedSeriesMap: Map<String, TrackedItemEntity>,
    onOpenAudibleSearch: () -> Unit,
    onOpenAudioBookBay: () -> Unit,
    onCopyRelease: () -> Unit,
    onExportCalendar: () -> Unit,
    onQuickTrack: (TrackType, String) -> Unit,
    onMuteBook: () -> Unit,
    onMuteSeries: () -> Unit,
    onMarkSeriesSeqRead: (TrackedItemEntity, Int) -> Unit
) {
    val palette = LocalPtsPalette.current
    val today = remember { LocalDate.now() }
    val daysUntil = remember(rel.releaseDateIso) {
        runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(rel.releaseDateIso)) }.getOrNull()
    }

    var showInfoDrawer by remember { mutableStateOf(false) }

    val countdownBadge = when {
        daysUntil == null -> "[ TBA ]"
        daysUntil == 0L -> "[ OUT TODAY ]"
        daysUntil < 0L -> "[ ${-daysUntil}d AGO ]"
        else -> "[ T-${daysUntil}d ]"
    }

    val badgeColor = when {
        daysUntil == 0L -> palette.green
        daysUntil != null && daysUntil < 0L -> palette.comment
        daysUntil != null && daysUntil in 1..7 -> palette.orange
        else -> palette.cyan
    }

    val matchedTrackedSeries = remember(rel.seriesTitle, trackedSeriesMap) {
        trackedSeriesMap.entries.firstOrNull { (key, _) ->
            rel.seriesTitle.contains(key, ignoreCase = true)
        }?.value
    }
    val seq = rel.seriesSequence
    val hasSeriesGap = matchedTrackedSeries != null &&
        seq != null &&
        seq > 1 &&
        matchedTrackedSeries.lastReadSequence < (seq - 1)

    val canMarkCurrentRead = matchedTrackedSeries != null &&
        seq != null &&
        daysUntil != null &&
        daysUntil <= 0L &&
        matchedTrackedSeries.lastReadSequence < seq

    AsciiBox(
        headerLabel = "${rel.releaseDateIso} $countdownBadge",
        borderColor = if (rel.isNewDiscovery && (daysUntil == null || daysUntil >= 0L)) palette.pink else palette.surfaceLine,
        headerColor = badgeColor
    ) {
        if (rel.isNewDiscovery && (daysUntil == null || daysUntil >= 0L)) {
            MonoText(text = "[ NEW PRE-ORDER ]", color = palette.pink, fontSizeSp = 11, bold = true)
            Spacer(modifier = Modifier.height(2.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            CoverThumbnail(
                coverUrl = rel.coverUrl,
                sizeDp = 62.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                MonoText(
                    text = rel.title.uppercase(),
                    color = palette.foreground,
                    fontSizeSp = 14,
                    bold = true
                )
                if (rel.seriesTitle.isNotBlank()) {
                    val seqLabel = rel.seriesSequence?.let { " #$it" }.orEmpty()
                    MonoText(
                        text = "Series: ${rel.seriesTitle}$seqLabel",
                        color = palette.purple,
                        fontSizeSp = 12
                    )
                }
                MonoText(text = "Author: ${rel.authors}", color = palette.yellow, fontSizeSp = 12)
                MonoText(text = "Narrator: ${rel.narrators}", color = palette.orange, fontSizeSp = 12)
                MonoText(
                    text = "Match: [${rel.matchedTags.replace(",", "] [")}]",
                    color = palette.comment,
                    fontSizeSp = 11
                )
            }
        }

        if (hasSeriesGap && matchedTrackedSeries != null && seq != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                MonoText(
                    text = "[ ! GAP: Book #${seq - 1} Unread (Read: #${matchedTrackedSeries.lastReadSequence}) ]",
                    color = palette.red,
                    fontSizeSp = 11,
                    bold = true
                )
                BracketButton(
                    label = "Mark #${seq - 1} Read",
                    color = palette.green,
                    onClick = { onMarkSeriesSeqRead(matchedTrackedSeries, seq - 1) }
                )
            }
        }

        if (canMarkCurrentRead && matchedTrackedSeries != null && seq != null) {
            Spacer(modifier = Modifier.height(4.dp))
            BracketButton(
                label = "✓ Mark Book #$seq Read",
                color = palette.green,
                onClick = { onMarkSeriesSeqRead(matchedTrackedSeries, seq) }
            )
        }

        if (showInfoDrawer) {
            Spacer(modifier = Modifier.height(6.dp))
            AsciiBox(
                headerLabel = "BOOK DETAILS & SYNOPSIS",
                borderColor = palette.comment,
                headerColor = palette.cyan
            ) {
                val hours = rel.runtimeMinutes / 60
                val mins = rel.runtimeMinutes % 60
                val runtimeStr = if (rel.runtimeMinutes > 0) "${hours}h ${mins}m" else "TBA"
                val pubStr = rel.publisher.ifBlank { "TBA" }
                MonoText(
                    text = "Runtime: $runtimeStr  |  Publisher: $pubStr  |  Lang: ${rel.language}",
                    color = palette.cyan,
                    fontSizeSp = 11
                )
                Spacer(modifier = Modifier.height(4.dp))
                MonoText(
                    text = rel.synopsis.ifBlank { "No publisher summary posted yet for this listing." },
                    color = palette.foreground,
                    fontSizeSp = 12
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (rel.seriesTitle.isNotBlank()) {
                        BracketButton(
                            label = "+ Track Series",
                            color = palette.purple,
                            onClick = { onQuickTrack(TrackType.SERIES, rel.seriesTitle) }
                        )
                    }
                    val firstAuthor = rel.authors.split(",").firstOrNull()?.trim().orEmpty()
                    if (firstAuthor.isNotBlank()) {
                        BracketButton(
                            label = "+ Track Author",
                            color = palette.yellow,
                            onClick = { onQuickTrack(TrackType.AUTHOR, firstAuthor) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)

        // Action Bar: [+ Info] [Audible] [ABB] [Copy] [+ Cal] [DND Book] [DND Series]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BracketButton(
                label = if (showInfoDrawer) "- Info" else "+ Info",
                color = palette.purple,
                active = showInfoDrawer,
                onClick = { showInfoDrawer = !showInfoDrawer }
            )
            BracketButton(
                label = "Audible",
                color = palette.green,
                onClick = onOpenAudibleSearch
            )
            BracketButton(
                label = "ABB",
                color = palette.orange,
                onClick = onOpenAudioBookBay
            )
            BracketButton(
                label = "Copy",
                color = palette.cyan,
                onClick = onCopyRelease
            )
            if (rel.releaseDateIso != "TBA") {
                BracketButton(
                    label = "+ Cal",
                    color = palette.yellow,
                    onClick = onExportCalendar
                )
            }
            BracketButton(
                label = "DND Book",
                color = palette.red,
                onClick = onMuteBook
            )
            if (rel.seriesTitle.isNotBlank()) {
                BracketButton(
                    label = "DND Series",
                    color = palette.red,
                    onClick = onMuteSeries
                )
            }
        }
    }
}

@Composable
private fun TrackedEntitiesView(
    trackedItems: List<TrackedItemEntity>,
    releases: List<ReleaseEntity>,
    enableDirectAdd: Boolean,
    onAddTracked: (TrackType, String, Int) -> Unit,
    onDeleteTracked: (TrackedItemEntity) -> Unit,
    onUpdateSeriesSeq: (TrackedItemEntity, Int) -> Unit,
    onOpenAbbQuery: (String) -> Unit
) {
    val palette = LocalPtsPalette.current
    val scope = rememberCoroutineScope()
    val todayIso = remember { LocalDate.now().toString() }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchingCatalog by remember { mutableStateOf(false) }
    var catalogResults by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }

    var useDirectMode by remember { mutableStateOf(false) }
    var directType by remember { mutableStateOf(TrackType.SERIES) }
    var directLastRead by remember { mutableStateOf("0") }

    var sourceFilter by remember { mutableStateOf(TrackedSourceFilter.ALL) }

    val filteredTrackedItems = remember(trackedItems, sourceFilter) {
        if (sourceFilter.trackType == null) trackedItems
        else trackedItems.filter { it.type == sourceFilter.trackType }
    }

    val upcomingCountsByItemId = remember(trackedItems, releases, todayIso) {
        val futureReleases = releases.filter { it.releaseDateIso == "TBA" || it.releaseDateIso >= todayIso }
        trackedItems.associate { item ->
            val q = item.queryValue.trim()
            val count = futureReleases.count { rel ->
                when (item.type) {
                    TrackType.SERIES -> rel.seriesTitle.contains(q, ignoreCase = true) ||
                        rel.title.contains(q, ignoreCase = true)
                    TrackType.AUTHOR -> rel.authors.contains(q, ignoreCase = true)
                    TrackType.NARRATOR -> rel.narrators.contains(q, ignoreCase = true)
                    TrackType.BOOK -> rel.asin.equals(q, ignoreCase = true) ||
                        rel.title.contains(q, ignoreCase = true)
                }
            }
            item.id to count
        }
    }

    val trackedKeySet = remember(trackedItems) {
        trackedItems.map { "${it.type.name}:${it.queryValue.trim().lowercase()}" }.toSet()
    }

    val performLiveSearch = {
        if (searchQuery.isNotBlank() && !isSearchingCatalog) {
            scope.launch {
                isSearchingCatalog = true
                catalogResults = AudibleApiClient.searchCatalog(
                    titleOrSeries = searchQuery,
                    numResults = 15,
                    forceRefresh = false
                )
                isSearchingCatalog = false
            }
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 84.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            AsciiBox(
                headerLabel = if (enableDirectAdd && useDirectMode) "DIRECT MANUAL ADD" else "LIVE CATALOG SEARCH"
            ) {
                if (enableDirectAdd) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BracketButton(
                            label = "LIVE SEARCH",
                            active = !useDirectMode,
                            onClick = { useDirectMode = false }
                        )
                        BracketButton(
                            label = "DIRECT ADD",
                            active = useDirectMode,
                            onClick = { useDirectMode = true }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (enableDirectAdd && useDirectMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TrackType.values().forEach { t ->
                            BracketButton(
                                label = t.name,
                                active = directType == t,
                                onClick = { directType = t }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                MonoText(
                    text = if (enableDirectAdd && useDirectMode) {
                        if (directType == TrackType.BOOK) "Enter Book Title or 10-char Audible ASIN:"
                        else "Enter Exact ${directType.name.lowercase().replaceFirstChar { it.uppercase() }} Name:"
                    } else {
                        "Search live catalog by book, series, author, or narrator:"
                    },
                    color = palette.comment,
                    fontSizeSp = 11
                )
                Spacer(modifier = Modifier.height(6.dp))

                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (enableDirectAdd && useDirectMode) {
                            if (searchQuery.isNotBlank()) {
                                onAddTracked(directType, searchQuery, directLastRead.toIntOrNull() ?: 0)
                                searchQuery = ""
                            }
                        } else {
                            performLiveSearch()
                        }
                    }),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = palette.foreground
                    ),
                    cursorBrush = SolidColor(palette.cyan),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surfaceLine.copy(alpha = 0.35f))
                        .padding(10.dp)
                )

                if (enableDirectAdd && useDirectMode && directType == TrackType.SERIES) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MonoText(
                        text = "Last Completed Book # in Series (for Gap Detector):",
                        color = palette.comment,
                        fontSizeSp = 11
                    )
                    BasicTextField(
                        value = directLastRead,
                        onValueChange = { directLastRead = it.filter { ch -> ch.isDigit() } },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = palette.foreground
                        ),
                        cursorBrush = SolidColor(palette.cyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.surfaceLine.copy(alpha = 0.35f))
                            .padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (enableDirectAdd && useDirectMode) {
                        BracketButton(
                            label = "+ ADD DIRECTLY",
                            color = palette.green,
                            onClick = {
                                if (searchQuery.isNotBlank()) {
                                    onAddTracked(
                                        directType,
                                        searchQuery,
                                        directLastRead.toIntOrNull() ?: 0
                                    )
                                    searchQuery = ""
                                }
                            }
                        )
                    } else {
                        BracketButton(
                            label = if (isSearchingCatalog) "SEARCHING..." else "SEARCH CATALOG",
                            color = palette.cyan,
                            onClick = performLiveSearch
                        )
                    }
                    if (searchQuery.isNotBlank()) {
                        BracketButton(
                            label = "ABB SEARCH",
                            color = palette.orange,
                            onClick = { onOpenAbbQuery(searchQuery) }
                        )
                    }
                    if (catalogResults.isNotEmpty() || searchQuery.isNotBlank()) {
                        BracketButton(
                            label = "CLEAR",
                            color = palette.comment,
                            onClick = {
                                searchQuery = ""
                                catalogResults = emptyList()
                            }
                        )
                    }
                }
            }
        }

        if (catalogResults.isNotEmpty() && !(enableDirectAdd && useDirectMode)) {
            item {
                MonoText(
                    text = "LIVE CATALOG RESULTS (${catalogResults.size}) - TAP TO FOLLOW:",
                    color = palette.cyan,
                    fontSizeSp = 12,
                    bold = true
                )
            }
            items(catalogResults, key = { "live_${it.asin}" }) { prod ->
                val seriesTracked = prod.seriesTitle.isNotBlank() &&
                    "SERIES:${prod.seriesTitle.trim().lowercase()}" in trackedKeySet
                val firstAuthor = prod.authors.firstOrNull()?.trim().orEmpty()
                val authorTracked = firstAuthor.isNotBlank() &&
                    "AUTHOR:${firstAuthor.lowercase()}" in trackedKeySet
                val firstNarrator = prod.narrators.firstOrNull()?.trim().orEmpty()
                val narratorTracked = firstNarrator.isNotBlank() &&
                    "NARRATOR:${firstNarrator.lowercase()}" in trackedKeySet
                val bookTracked = "BOOK:${prod.asin.lowercase()}" in trackedKeySet ||
                    "BOOK:${prod.title.trim().lowercase()}" in trackedKeySet

                AsciiBox(
                    headerLabel = "${prod.releaseDateIso} • ${prod.asin}",
                    borderColor = palette.cyan,
                    headerColor = palette.cyan
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        CoverThumbnail(coverUrl = prod.coverUrl, sizeDp = 50.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            MonoText(text = prod.title, bold = true, fontSizeSp = 13)
                            if (prod.seriesTitle.isNotBlank()) {
                                val seqPart = prod.seriesSequence?.let { " #$it" }.orEmpty()
                                MonoText(
                                    text = "Series: ${prod.seriesTitle}$seqPart",
                                    color = palette.purple,
                                    fontSizeSp = 12
                                )
                            }
                            MonoText(
                                text = "Author: ${prod.authors.joinToString(", ")}",
                                color = palette.yellow,
                                fontSizeSp = 12
                            )
                            MonoText(
                                text = "Narrator: ${prod.narrators.joinToString(", ")}",
                                color = palette.orange,
                                fontSizeSp = 12
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (prod.seriesTitle.isNotBlank()) {
                            BracketButton(
                                label = if (seriesTracked) "✓ Series" else "+ Series",
                                active = seriesTracked,
                                color = palette.purple,
                                onClick = {
                                    onAddTracked(TrackType.SERIES, prod.seriesTitle, 0)
                                }
                            )
                        }
                        if (firstAuthor.isNotBlank()) {
                            BracketButton(
                                label = if (authorTracked) "✓ Author" else "+ Author",
                                active = authorTracked,
                                color = palette.yellow,
                                onClick = { onAddTracked(TrackType.AUTHOR, firstAuthor, 0) }
                            )
                        }
                        if (firstNarrator.isNotBlank()) {
                            BracketButton(
                                label = if (narratorTracked) "✓ Narrator" else "+ Narrator",
                                active = narratorTracked,
                                color = palette.orange,
                                onClick = { onAddTracked(TrackType.NARRATOR, firstNarrator, 0) }
                            )
                        }
                        BracketButton(
                            label = if (bookTracked) "✓ Book" else "+ Book",
                            active = bookTracked,
                            color = palette.green,
                            onClick = { onAddTracked(TrackType.BOOK, prod.asin, 0) }
                        )
                        BracketButton(
                            label = "ABB",
                            color = palette.orange,
                            onClick = { onOpenAbbQuery("${prod.title} $firstAuthor") }
                        )
                    }
                }
            }
        }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                MonoText(
                    text = "FOLLOWED SOURCES (${filteredTrackedItems.size}/${trackedItems.size}):",
                    color = palette.purple,
                    fontSizeSp = 12,
                    bold = true
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackedSourceFilter.values().forEach { tf ->
                        BracketButton(
                            label = tf.label,
                            active = sourceFilter == tf,
                            color = palette.purple,
                            onClick = { sourceFilter = tf }
                        )
                    }
                }
            }
        }

        items(filteredTrackedItems, key = { it.id }) { item ->
            val upcomingBadgeCount = upcomingCountsByItemId[item.id] ?: 0
            AsciiBox(headerLabel = "${item.type.name} • [ $upcomingBadgeCount upcoming ]") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MonoText(text = item.queryValue, bold = true)
                        if (item.type == TrackType.SERIES) {
                            MonoText(
                                text = "Last Read Book: #${item.lastReadSequence}",
                                color = palette.purple,
                                fontSizeSp = 12
                            )
                        }
                    }
                    Row {
                        if (item.type == TrackType.SERIES) {
                            BracketButton(
                                label = "-1",
                                color = palette.comment,
                                onClick = {
                                    onUpdateSeriesSeq(item, (item.lastReadSequence - 1).coerceAtLeast(0))
                                }
                            )
                            BracketButton(
                                label = "+1 Read",
                                color = palette.cyan,
                                onClick = { onUpdateSeriesSeq(item, item.lastReadSequence + 1) }
                            )
                        }
                        BracketButton(
                            label = "ABB",
                            color = palette.orange,
                            onClick = { onOpenAbbQuery(item.queryValue) }
                        )
                        BracketButton(
                            label = "x",
                            color = palette.red,
                            onClick = { onDeleteTracked(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsAndBackupView(
    prefs: FilterPrefs,
    onUpdatePrefs: (FilterPrefs) -> Unit,
    onOpenAbout: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val palette = LocalPtsPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var cacheStats by remember { mutableStateOf(CacheStats(0, 0L)) }
    LaunchedEffect(Unit) {
        cacheStats = AudibleApiClient.getCacheStats()
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 84.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            AsciiBox(headerLabel = "CATALOG & TRACKER OPTIONS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText("English Language Only")
                    BracketButton(
                        label = if (prefs.englishOnly) "ON" else "OFF",
                        active = prefs.englishOnly,
                        onClick = { onUpdatePrefs(prefs.copy(englishOnly = !prefs.englishOnly)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText("Hide Abridged Editions")
                    BracketButton(
                        label = if (prefs.hideAbridged) "ON" else "OFF",
                        active = prefs.hideAbridged,
                        onClick = { onUpdatePrefs(prefs.copy(hideAbridged = !prefs.hideAbridged)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText("Hide Dramatized / Full-Cast")
                    BracketButton(
                        label = if (prefs.hideDramatized) "ON" else "OFF",
                        active = prefs.hideDramatized,
                        onClick = { onUpdatePrefs(prefs.copy(hideDramatized = !prefs.hideDramatized)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText("Direct Manual Add Mode")
                    BracketButton(
                        label = if (prefs.enableDirectAdd) "ON" else "OFF",
                        active = prefs.enableDirectAdd,
                        onClick = { onUpdatePrefs(prefs.copy(enableDirectAdd = !prefs.enableDirectAdd)) }
                    )
                }
            }
        }

        item {
            AsciiBox(headerLabel = "AUDIBLE STORE REGION") {
                MonoText(
                    text = "Select the Audible marketplace matching your account for the [ Audible ] button:",
                    color = palette.comment,
                    fontSizeSp = 12
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AudibleMarketplace.values().forEach { mp ->
                        BracketButton(
                            label = mp.label,
                            active = prefs.marketplace == mp,
                            onClick = { onUpdatePrefs(prefs.copy(marketplaceCode = mp.code)) }
                        )
                    }
                }
            }
        }

        item {
            AsciiBox(headerLabel = "NOTIFICATION SCHEDULE (T-7d & T-0d)") {
                MonoText(
                    text = "Daily Check & Alert Time: %02d:%02d Local".format(
                        prefs.notifyHour,
                        prefs.notifyMinute
                    ),
                    color = palette.cyan,
                    bold = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BracketButton(
                        label = "-1 Hour",
                        onClick = {
                            val h = (prefs.notifyHour + 23) % 24
                            onUpdatePrefs(prefs.copy(notifyHour = h))
                        }
                    )
                    BracketButton(
                        label = "+1 Hour",
                        onClick = {
                            val h = (prefs.notifyHour + 1) % 24
                            onUpdatePrefs(prefs.copy(notifyHour = h))
                        }
                    )
                    BracketButton(
                        label = "Reset 09:00",
                        color = palette.orange,
                        onClick = { onUpdatePrefs(prefs.copy(notifyHour = 9, notifyMinute = 0)) }
                    )
                }
            }
        }

        item {
            AsciiBox(headerLabel = "METADATA & COVER CACHE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MonoText(
                            text = "Cached Files: ${cacheStats.fileCount} (${cacheStats.formattedSize})",
                            color = palette.cyan,
                            bold = true
                        )
                        MonoText(
                            text = "12h JSON catalog cache + offline cover art cache",
                            color = palette.comment,
                            fontSizeSp = 11
                        )
                    }
                    BracketButton(
                        label = "CLEAR CACHE",
                        color = palette.red,
                        onClick = {
                            scope.launch {
                                ThumbnailCache.clear()
                                cacheStats = AudibleApiClient.clearAllCaches()
                                Toast.makeText(context, "[ Cache Cleared ]", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        item {
            AsciiBox(headerLabel = "PLAIN-TEXT JSON BACKUP & RESTORE") {
                MonoText(
                    text = "Export or restore your tracked series, authors, narrators, DND rules, and preferences.",
                    color = palette.comment,
                    fontSizeSp = 12
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BracketButton(
                        label = "EXPORT .JSON",
                        color = palette.green,
                        onClick = onExportBackup
                    )
                    BracketButton(
                        label = "IMPORT .JSON",
                        color = palette.yellow,
                        onClick = onImportBackup
                    )
                }
            }
        }

        item {
            AsciiBox(headerLabel = "APPLICATION INFO") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "View app name, version, credits, and architecture details.",
                        color = palette.comment,
                        fontSizeSp = 12,
                        modifier = Modifier.weight(1f)
                    )
                    BracketButton(
                        label = "ABOUT ->",
                        color = palette.purple,
                        onClick = onOpenAbout
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutPageView(onBack: () -> Unit) {
    val palette = LocalPtsPalette.current
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 84.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            BracketButton(
                label = "< BACK TO SETTINGS",
                color = palette.cyan,
                onClick = onBack
            )
        }

        item {
            AsciiBox(
                headerLabel = "ABOUT APPLICATION",
                borderColor = palette.purple,
                headerColor = palette.purple
            ) {
                MonoText(
                    text = "NOCTURNETXT",
                    color = palette.purple,
                    fontSizeSp = 18,
                    bold = true
                )
                MonoText(
                    text = "Version 1.5.0 • Plain-Text Audiobook Release Tracker",
                    color = palette.cyan,
                    fontSizeSp = 12,
                    bold = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                MonoText(
                    text = "NocturneTXT is a lean, native Android tracker for upcoming audiobook releases across Series, Authors, Narrators, and individual Books.",
                    color = palette.foreground,
                    fontSizeSp = 13
                )
            }
        }

        item {
            AsciiBox(headerLabel = "DESIGN & AESTHETIC CREDITS") {
                MonoText(
                    text = "• Layout & Typography: Inspired by PlainTextSports.com, adapted for mobile screens with responsive 1dp ASCII frames (+---+) and 48dp bracketed touch controls.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
                Spacer(modifier = Modifier.height(4.dp))
                MonoText(
                    text = "• Color Palettes: Official Dracula Dark (#282A36) and Alucard Light (#FFFBEB) specifications.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
            }
        }

        item {
            AsciiBox(headerLabel = "ENGINE & PRIVACY") {
                MonoText(
                    text = "• Zero-Bloat Native Stack: Built with Kotlin, Jetpack Compose, Room SQLite, WorkManager, and native HttpURLConnection.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
                Spacer(modifier = Modifier.height(4.dp))
                MonoText(
                    text = "• Smart Deduplication & DND: Automatically merges multi-source clashes into a single card and suppresses muted books or series from future syncs and T-7d / T-0d notifications.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
            }
        }
    }
}
