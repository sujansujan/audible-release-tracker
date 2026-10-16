package com.plaintext.audible.ui

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
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class ActiveTab {
    UPCOMING, TRACKED, DND, SETTINGS
}

enum class QuickFilter(val label: String) {
    ALL("ALL"),
    SERIES("SERIES"),
    AUTHOR("AUTHOR"),
    NARRATOR("NARRATOR"),
    SOON_30D("<=30d")
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

    // Background warm-sync using disk/memory cache on startup
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

    // Helper for adding a tracked item with duplicate protection & toast feedback
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
                // Top Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "PTS // AUDIBLE ${prefs.marketplace.code}",
                        color = palette.purple,
                        fontSizeSp = 15,
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
                            onExportCalendar = { rel ->
                                val date = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
                                if (date != null) {
                                    val startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                                    val calIntent = Intent(Intent.ACTION_INSERT).apply {
                                        data = CalendarContract.Events.CONTENT_URI
                                        putExtra(CalendarContract.Events.TITLE, "Audible Release: ${rel.title}")
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
                            onMarkPriorRead = { trackedSeries, seq ->
                                scope.launch { repo.dao.updateSeriesReadSequence(trackedSeries.id, seq) }
                            }
                        )

                        ActiveTab.TRACKED -> TrackedEntitiesView(
                            trackedItems = trackedItems,
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

                        ActiveTab.DND -> DndListView(
                            dndRules = dndRules,
                            onRemoveRule = { rule ->
                                scope.launch {
                                    repo.dao.deleteDndRule(rule)
                                    repo.syncCatalog(forceRefresh = false)
                                }
                            }
                        )

                        ActiveTab.SETTINGS -> SettingsAndBackupView(
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
                            onAddTrackedDirect = handleAddTracked,
                            onExportBackup = onExportBackup,
                            onImportBackup = onImportBackup
                        )
                    }
                }
            }

            // Auto-Hiding Bottom Navigation Bar & Quick Filter Strip
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
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MonoText(text = "FLT:", color = palette.comment, fontSizeSp = 11)
                                QuickFilter.values().forEach { filter ->
                                    BracketButton(
                                        label = filter.label,
                                        active = activeFilter == filter,
                                        color = palette.purple,
                                        onClick = { activeFilter = filter }
                                    )
                                }
                            }
                            HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BracketButton(
                                label = "UPCOMING (${releases.count { it.releaseDateIso != "TBA" }})",
                                active = activeTab == ActiveTab.UPCOMING,
                                onClick = { activeTab = ActiveTab.UPCOMING }
                            )
                            BracketButton(
                                label = "TRACKED (${trackedItems.size})",
                                active = activeTab == ActiveTab.TRACKED,
                                onClick = { activeTab = ActiveTab.TRACKED }
                            )
                            BracketButton(
                                label = "DND (${dndRules.size})",
                                active = activeTab == ActiveTab.DND,
                                onClick = { activeTab = ActiveTab.DND }
                            )
                            BracketButton(
                                label = "CFG",
                                active = activeTab == ActiveTab.SETTINGS,
                                onClick = { activeTab = ActiveTab.SETTINGS }
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
    quickFilter: QuickFilter,
    trackedSeriesMap: Map<String, TrackedItemEntity>,
    showTbaExpanded: Boolean,
    onToggleTba: () -> Unit,
    onClearNewBadges: () -> Unit,
    onOpenAudibleSearch: (ReleaseEntity) -> Unit,
    onOpenAudioBookBay: (ReleaseEntity) -> Unit,
    onExportCalendar: (ReleaseEntity) -> Unit,
    onQuickTrack: (TrackType, String) -> Unit,
    onMuteBook: (ReleaseEntity) -> Unit,
    onMuteSeries: (ReleaseEntity) -> Unit,
    onMarkPriorRead: (TrackedItemEntity, Int) -> Unit
) {
    val palette = LocalPtsPalette.current
    val today = remember { LocalDate.now() }

    val filteredReleases = remember(releases, quickFilter) {
        releases.filter { rel ->
            when (quickFilter) {
                QuickFilter.ALL -> true
                QuickFilter.SERIES -> rel.matchedTags.contains("SERIES", ignoreCase = true)
                QuickFilter.AUTHOR -> rel.matchedTags.contains("AUTHOR", ignoreCase = true)
                QuickFilter.NARRATOR -> rel.matchedTags.contains("NARRATOR", ignoreCase = true)
                QuickFilter.SOON_30D -> {
                    val d = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(rel.releaseDateIso)) }.getOrNull()
                    d != null && d in 0..30
                }
            }
        }
    }

    val scheduled = remember(filteredReleases) { filteredReleases.filter { it.releaseDateIso != "TBA" } }
    val tbaList = remember(filteredReleases) { filteredReleases.filter { it.releaseDateIso == "TBA" } }
    val newCount = remember(releases) { releases.count { it.isNewDiscovery } }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (newCount > 0) {
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
                        text = "No scheduled upcoming releases match [${quickFilter.label}].\nTap [ TRACKED ] on the bottom bar to search the live catalog (or add directly in [ CFG ]).",
                        color = palette.comment
                    )
                }
            }
        }

        items(scheduled, key = { it.asin }) { rel ->
            ReleaseScorecard(
                rel = rel,
                trackedSeriesMap = trackedSeriesMap,
                onOpenAudibleSearch = { onOpenAudibleSearch(rel) },
                onOpenAudioBookBay = { onOpenAudioBookBay(rel) },
                onExportCalendar = { onExportCalendar(rel) },
                onQuickTrack = onQuickTrack,
                onMuteBook = { onMuteBook(rel) },
                onMuteSeries = { onMuteSeries(rel) },
                onMarkPriorRead = onMarkPriorRead
            )
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
                        onExportCalendar = { onExportCalendar(rel) },
                        onQuickTrack = onQuickTrack,
                        onMuteBook = { onMuteBook(rel) },
                        onMuteSeries = { onMuteSeries(rel) },
                        onMarkPriorRead = onMarkPriorRead
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
    onExportCalendar: () -> Unit,
    onQuickTrack: (TrackType, String) -> Unit,
    onMuteBook: () -> Unit,
    onMuteSeries: () -> Unit,
    onMarkPriorRead: (TrackedItemEntity, Int) -> Unit
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
        daysUntil < 0L -> "[ RELEASED ]"
        else -> "[ T-${daysUntil}d ]"
    }

    val badgeColor = when {
        daysUntil == 0L -> palette.green
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

    AsciiBox(
        headerLabel = "${rel.releaseDateIso} $countdownBadge",
        borderColor = if (rel.isNewDiscovery) palette.pink else palette.surfaceLine,
        headerColor = badgeColor
    ) {
        if (rel.isNewDiscovery) {
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
                    onClick = { onMarkPriorRead(matchedTrackedSeries, seq - 1) }
                )
            }
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
                    text = rel.synopsis.ifBlank { "No publisher summary posted yet for this pre-order." },
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

        // Action Bar: [+ Info] [Audible] [ABB] [+ Cal] [DND Book] [DND Series]
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

/**
 * Default Live Catalog Search in TRACKED tab, plus list of currently followed sources.
 * Direct/manual source entry lives in CFG (Settings).
 */
@Composable
private fun TrackedEntitiesView(
    trackedItems: List<TrackedItemEntity>,
    onAddTracked: (TrackType, String, Int) -> Unit,
    onDeleteTracked: (TrackedItemEntity) -> Unit,
    onUpdateSeriesSeq: (TrackedItemEntity, Int) -> Unit,
    onOpenAbbQuery: (String) -> Unit
) {
    val palette = LocalPtsPalette.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchingCatalog by remember { mutableStateOf(false) }
    var catalogResults by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }

    // Lookup set of already-tracked keys ("TYPE:lowercase_value") to prevent duplicates Visually
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
            AsciiBox(headerLabel = "LIVE AUDIBLE CATALOG SEARCH (DEFAULT)") {
                MonoText(
                    text = "Search by book, series, author, or narrator (need manual entry? See [ CFG ]):",
                    color = palette.comment,
                    fontSizeSp = 11
                )
                Spacer(modifier = Modifier.height(6.dp))

                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { performLiveSearch() }),
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

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BracketButton(
                        label = if (isSearchingCatalog) "SEARCHING..." else "SEARCH CATALOG",
                        color = palette.cyan,
                        onClick = performLiveSearch
                    )
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

        if (catalogResults.isNotEmpty()) {
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
            MonoText(
                text = "FOLLOWED SOURCES (${trackedItems.size}):",
                color = palette.purple,
                fontSizeSp = 12,
                bold = true
            )
        }

        items(trackedItems, key = { it.id }) { item ->
            AsciiBox(headerLabel = item.type.name) {
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
private fun DndListView(
    dndRules: List<DndRuleEntity>,
    onRemoveRule: (DndRuleEntity) -> Unit
) {
    val palette = LocalPtsPalette.current
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 84.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            MonoText(
                text = "Books and Series on your Do Not Disturb list are suppressed from all future Author, Narrator, and Series syncs.",
                color = palette.comment,
                fontSizeSp = 12
            )
        }
        if (dndRules.isEmpty()) {
            item {
                AsciiBox(headerLabel = "DND LIST EMPTY") {
                    MonoText(text = "No muted books or series.", color = palette.comment)
                }
            }
        }
        items(dndRules, key = { it.targetKey }) { rule ->
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
                        onClick = { onRemoveRule(rule) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAndBackupView(
    prefs: FilterPrefs,
    onUpdatePrefs: (FilterPrefs) -> Unit,
    onAddTrackedDirect: (TrackType, String, Int) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val palette = LocalPtsPalette.current
    var directType by remember { mutableStateOf(TrackType.SERIES) }
    var directQuery by remember { mutableStateOf("") }
    var directLastRead by remember { mutableStateOf("0") }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 84.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Direct Manual Add Option in Settings (CFG)
        item {
            AsciiBox(headerLabel = "ADD TRACKED SOURCE DIRECTLY") {
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
                Spacer(modifier = Modifier.height(6.dp))
                MonoText(
                    text = if (directType == TrackType.BOOK) "Enter Book Title or 10-char Audible ASIN:"
                    else "Enter Exact ${directType.name.lowercase().replaceFirstChar { it.uppercase() }} Name:",
                    color = palette.comment,
                    fontSizeSp = 12
                )
                BasicTextField(
                    value = directQuery,
                    onValueChange = { directQuery = it },
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
                if (directType == TrackType.SERIES) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MonoText(
                        text = "Last Completed Book # in Series (for Gap Detector):",
                        color = palette.comment,
                        fontSizeSp = 12
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
                BracketButton(
                    label = "+ ADD DIRECTLY",
                    color = palette.green,
                    onClick = {
                        if (directQuery.isNotBlank()) {
                            onAddTrackedDirect(
                                directType,
                                directQuery,
                                directLastRead.toIntOrNull() ?: 0
                            )
                            directQuery = ""
                        }
                    }
                )
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
            AsciiBox(headerLabel = "CATALOG AUTO-FILTERS") {
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
    }
}
