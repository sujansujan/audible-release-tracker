package com.plaintext.audible.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.plaintext.audible.data.AudibleApiClient
import com.plaintext.audible.data.CacheStats
import com.plaintext.audible.data.CatalogProduct
import com.plaintext.audible.data.DndRuleEntity
import com.plaintext.audible.data.FilterPrefs
import com.plaintext.audible.data.ReleaseEntity
import com.plaintext.audible.data.ReleaseRepository
import com.plaintext.audible.data.TrackType
import com.plaintext.audible.data.TrackedItemEntity
import com.plaintext.audible.worker.ReleaseCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

private enum class MainTab(val title: String) {
    RELEASES("RELEASES"),
    CALENDAR("CALENDAR"),
    TRACKED("TRACKED"),
    SETTINGS("SETTINGS")
}

private enum class ReleaseFilterMode(val label: String) {
    UPCOMING("UPCOMING"),
    OUT_NOW("OUT NOW"),
    NEW("NEW"),
    ALL("ALL")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackerScreen(
    repo: ReleaseRepository,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prefs by remember { mutableStateOf(repo.getPrefs()) }
    val systemDark = isSystemInDarkTheme()
    val activePalette = remember(prefs.themeModeCode, systemDark) {
        when (prefs.themeModeCode.uppercase(Locale.US)) {
            "LIGHT" -> AlucardPalette
            "DARK" -> DraculaPalette
            else -> if (systemDark) DraculaPalette else AlucardPalette
        }
    }

    val trackedItems by repo.trackedItemsFlow.collectAsState(initial = emptyList())
    val releases by repo.releasesFlow.collectAsState(initial = emptyList())
    val dndRules by repo.dndRulesFlow.collectAsState(initial = emptyList())

    var currentTab by rememberSaveable { mutableStateOf(MainTab.RELEASES) }
    var filterMode by rememberSaveable { mutableStateOf(ReleaseFilterMode.UPCOMING) }
    var activeAuthorFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var activeSeriesFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var expandedAsin by rememberSaveable { mutableStateOf<String?>(null) }
    var showAboutScreen by rememberSaveable { mutableStateOf(false) }

    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusLine by remember { mutableStateOf<String?>(null) }

    var lightboxUrl by remember { mutableStateOf<String?>(null) }
    var lightboxTitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(trackedItems, activeAuthorFilter) {
        if (activeAuthorFilter != null &&
            trackedItems.none { it.queryValue.equals(activeAuthorFilter, ignoreCase = true) }
        ) {
            activeAuthorFilter = null
        }
    }

    BackHandler(
        enabled = showAboutScreen ||
            activeSeriesFilter != null ||
            activeAuthorFilter != null ||
            currentTab != MainTab.RELEASES
    ) {
        when {
            showAboutScreen -> showAboutScreen = false
            activeSeriesFilter != null -> activeSeriesFilter = null
            activeAuthorFilter != null -> activeAuthorFilter = null
            currentTab != MainTab.RELEASES -> currentTab = MainTab.RELEASES
        }
    }

    val today = remember { LocalDate.now() }
    val upcomingCount = remember(releases, today) {
        releases.count { rel ->
            val d = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
            d != null && !d.isBefore(today)
        }
    }
    val newCount = remember(releases) { releases.count { it.isNewDiscovery || it.narratorJustAnnounced } }

    CompositionLocalProvider(LocalPtsPalette provides activePalette) {
        val palette = LocalPtsPalette.current

        Scaffold(
            containerColor = palette.background,
            contentColor = palette.foreground
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.background)
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // Top Telemetry & Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TRACKED:${trackedItems.size}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = palette.purple
                        )
                        Text(
                            text = "UPCOMING:$upcomingCount",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = palette.cyan
                        )
                        if (newCount > 0) {
                            Text(
                                text = "NEW:$newCount",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = palette.orange
                            )
                        }
                    }

                    PtsBracketButton(
                        label = if (isSyncing) "SYNCING..." else "SYNC",
                        color = palette.green,
                        onClick = {
                            if (!isSyncing) {
                                scope.launch {
                                    isSyncing = true
                                    syncStatusLine = "Syncing Audible catalog..."
                                    val batch = repo.syncCatalog(forceRefresh = true)
                                    isSyncing = false
                                    syncStatusLine =
                                        "Sync complete (${batch.releaseDay.size} out today, ${batch.sevenDayOut.size} within 7d)"
                                }
                            }
                        }
                    )
                }

                if (!syncStatusLine.isNullOrBlank()) {
                    Text(
                        text = syncStatusLine!!,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = palette.pink,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                PtsDivider()

                // Primary Navigation Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MainTab.entries.forEach { tab ->
                        val labelWithCount = when (tab) {
                            MainTab.RELEASES -> "RELEASES ($upcomingCount)"
                            MainTab.TRACKED -> "TRACKED (${trackedItems.size})"
                            else -> tab.title
                        }
                        PtsBracketButton(
                            label = labelWithCount,
                            selected = currentTab == tab,
                            color = palette.purple,
                            onClick = {
                                currentTab = tab
                                showAboutScreen = false
                            }
                        )
                    }
                }

                PtsDivider()

                when (currentTab) {
                    MainTab.RELEASES -> {
                        ReleasesView(
                            releases = releases,
                            trackedItems = trackedItems,
                            today = today,
                            prefs = prefs,
                            filterMode = filterMode,
                            onFilterModeChange = { filterMode = it },
                            activeAuthorFilter = activeAuthorFilter,
                            onClearAuthorFilter = { activeAuthorFilter = null },
                            activeSeriesFilter = activeSeriesFilter,
                            onSelectSeriesFilter = { activeSeriesFilter = it },
                            searchText = searchText,
                            onSearchTextChange = { searchText = it },
                            expandedAsin = expandedAsin,
                            onToggleExpand = { asin ->
                                expandedAsin = if (expandedAsin == asin) null else asin
                            },
                            onQuickTrackAuthor = { authorName ->
                                scope.launch {
                                    val primary = authorName.substringBefore(",").trim()
                                    if (primary.isNotBlank()) {
                                        repo.addTrackedItemUnique(TrackType.AUTHOR, primary)
                                        Toast.makeText(context, "[ + Tracked Author: $primary ]", Toast.LENGTH_SHORT).show()
                                        repo.syncCatalog(forceRefresh = false)
                                    }
                                }
                            },
                            onQuickTrackSeries = { seriesTitle, authorName ->
                                scope.launch {
                                    if (seriesTitle.isNotBlank()) {
                                        val primary = authorName.substringBefore(",").trim()
                                        repo.addTrackedItemUnique(
                                            type = TrackType.SERIES,
                                            rawQuery = seriesTitle,
                                            externalId = "|$primary"
                                        )
                                        Toast.makeText(context, "[ + Tracked Series: $seriesTitle ]", Toast.LENGTH_SHORT).show()
                                        repo.syncCatalog(forceRefresh = false)
                                    }
                                }
                            },
                            onMuteBook = { rel ->
                                scope.launch {
                                    repo.muteBook(rel.asin, rel.title)
                                    Toast.makeText(context, "[ DND Book: ${rel.title} ]", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onMuteSeries = { rel ->
                                if (rel.seriesTitle.isNotBlank()) {
                                    scope.launch {
                                        repo.muteSeries(rel.seriesTitle, rel.seriesTitle)
                                        Toast.makeText(context, "[ DND Series: ${rel.seriesTitle} ]", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onMarkAllSeen = {
                                scope.launch { repo.markAllSeen() }
                            },
                            onOpenLightbox = { url, title ->
                                lightboxUrl = url
                                lightboxTitle = title
                            }
                        )
                    }

                    MainTab.CALENDAR -> {
                        CalendarScheduleView(
                            releases = releases,
                            today = today,
                            onSelectRelease = { rel ->
                                activeAuthorFilter = null
                                activeSeriesFilter = null
                                filterMode = ReleaseFilterMode.ALL
                                expandedAsin = rel.asin
                                currentTab = MainTab.RELEASES
                            }
                        )
                    }

                    MainTab.TRACKED -> {
                        TrackedItemsView(
                            repo = repo,
                            trackedItems = trackedItems,
                            releases = releases,
                            onFilterByAuthorOrNarrator = { query ->
                                activeAuthorFilter = query
                                activeSeriesFilter = null
                                filterMode = ReleaseFilterMode.ALL
                                currentTab = MainTab.RELEASES
                            },
                            onFilterBySeries = { series ->
                                activeSeriesFilter = series
                                activeAuthorFilter = null
                                filterMode = ReleaseFilterMode.ALL
                                currentTab = MainTab.RELEASES
                            },
                            onOpenLightbox = { url, title ->
                                lightboxUrl = url
                                lightboxTitle = title
                            },
                            onStatusMessage = { syncStatusLine = it }
                        )
                    }

                    MainTab.SETTINGS -> {
                        if (showAboutScreen) {
                            AboutView(onBack = { showAboutScreen = false })
                        } else {
                            SettingsView(
                                repo = repo,
                                prefs = prefs,
                                dndRules = dndRules,
                                onUpdatePrefs = { updated ->
                                    prefs = updated
                                    repo.savePrefs(updated)
                                    ReleaseCheckWorker.scheduleDailyCheck(
                                        context,
                                        updated.notifyHour,
                                        updated.notifyMinute
                                    )
                                },
                                onExportBackup = onExportBackup,
                                onImportBackup = onImportBackup,
                                onOpenAbout = { showAboutScreen = true },
                                onStatusMessage = { syncStatusLine = it }
                            )
                        }
                    }
                }
            }

            if (!lightboxUrl.isNullOrBlank()) {
                Dialog(onDismissRequest = { lightboxUrl = null }) {
                    PlainTextBox(
                        headerText = lightboxTitle ?: "IMAGE PREVIEW",
                        headerColor = palette.green,
                        borderColor = palette.cyan
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CoverThumbnail(
                                url = lightboxUrl!!,
                                fallbackLabel = lightboxTitle ?: "IMG",
                                size = 260.dp,
                                borderColor = palette.green
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            PtsBracketButton(
                                label = "CLOSE",
                                color = palette.yellow
                            ) {
                                lightboxUrl = null
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleasesView(
    releases: List<ReleaseEntity>,
    trackedItems: List<TrackedItemEntity>,
    today: LocalDate,
    prefs: FilterPrefs,
    filterMode: ReleaseFilterMode,
    onFilterModeChange: (ReleaseFilterMode) -> Unit,
    activeAuthorFilter: String?,
    onClearAuthorFilter: () -> Unit,
    activeSeriesFilter: String?,
    onSelectSeriesFilter: (String?) -> Unit,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    expandedAsin: String?,
    onToggleExpand: (String) -> Unit,
    onQuickTrackAuthor: (String) -> Unit,
    onQuickTrackSeries: (String, String) -> Unit,
    onMuteBook: (ReleaseEntity) -> Unit,
    onMuteSeries: (ReleaseEntity) -> Unit,
    onMarkAllSeen: () -> Unit,
    onOpenLightbox: (String, String) -> Unit
) {
    val palette = LocalPtsPalette.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReleaseFilterMode.entries.forEach { mode ->
            PtsBracketButton(
                label = mode.label,
                selected = filterMode == mode,
                color = palette.cyan,
                onClick = { onFilterModeChange(mode) }
            )
        }
        if (releases.any { it.isNewDiscovery || it.narratorJustAnnounced }) {
            PtsBracketButton(
                label = "MARK SEEN",
                color = palette.orange,
                onClick = onMarkAllSeen
            )
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    if (activeAuthorFilter != null || activeSeriesFilter != null) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (activeAuthorFilter != null) {
                PtsBracketButton(
                    label = "AUTHOR: $activeAuthorFilter X",
                    color = palette.yellow,
                    onClick = onClearAuthorFilter
                )
            }
            if (activeSeriesFilter != null) {
                PtsBracketButton(
                    label = "SERIES: $activeSeriesFilter X",
                    color = palette.purple,
                    onClick = { onSelectSeriesFilter(null) }
                )
            }
        }
    }

    // Plain-text ASCII Search Prompt
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.surfaceLine)
            .background(palette.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "> ",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = palette.green
        )
        BasicTextField(
            value = searchText,
            onValueChange = onSearchTextChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.foreground
            ),
            cursorBrush = SolidColor(palette.green),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (searchText.isEmpty()) {
                    Text(
                        text = "Filter by title, author, series, narrator...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.comment
                    )
                }
                inner()
            }
        )
        if (searchText.isNotEmpty()) {
            PtsBracketButton(label = "X", color = palette.red) { onSearchTextChange("") }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    val trackedAuthorsLower = remember(trackedItems) {
        trackedItems.filter { it.type == TrackType.AUTHOR }
            .map { it.queryValue.lowercase(Locale.US) }
            .toSet()
    }
    val trackedSeriesLower = remember(trackedItems) {
        trackedItems.filter { it.type == TrackType.SERIES }
            .map { it.queryValue.lowercase(Locale.US) }
            .toSet()
    }

    val filtered = remember(
        releases,
        filterMode,
        activeAuthorFilter,
        activeSeriesFilter,
        searchText,
        today
    ) {
        val list = releases.filter { rel ->
            val matchAuthor = activeAuthorFilter == null ||
                rel.authors.contains(activeAuthorFilter, ignoreCase = true) ||
                rel.narrators.contains(activeAuthorFilter, ignoreCase = true)
            val matchSeries = activeSeriesFilter == null ||
                rel.seriesTitle.equals(activeSeriesFilter, ignoreCase = true)
            val matchQuery = searchText.isBlank() ||
                rel.title.contains(searchText, ignoreCase = true) ||
                rel.authors.contains(searchText, ignoreCase = true) ||
                rel.seriesTitle.contains(searchText, ignoreCase = true) ||
                rel.narrators.contains(searchText, ignoreCase = true)

            val rDate = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
            val matchTab = when (filterMode) {
                ReleaseFilterMode.UPCOMING -> rDate == null || !rDate.isBefore(today)
                ReleaseFilterMode.OUT_NOW -> rDate != null && !rDate.isAfter(today)
                ReleaseFilterMode.NEW -> rel.isNewDiscovery || rel.narratorJustAnnounced
                ReleaseFilterMode.ALL -> true
            }
            matchAuthor && matchSeries && matchQuery && matchTab
        }

        when {
            activeSeriesFilter != null -> {
                list.sortedWith(
                    compareBy<ReleaseEntity> { it.seriesSequence ?: Int.MAX_VALUE }
                        .thenBy { it.releaseDateIso }
                )
            }
            filterMode == ReleaseFilterMode.UPCOMING -> {
                list.sortedBy { it.releaseDateIso }
            }
            else -> {
                list.sortedByDescending { it.releaseDateIso }
            }
        }
    }

    if (filtered.isEmpty()) {
        PlainTextBox(
            headerText = "NO RELEASES FOUND",
            headerColor = palette.comment,
            borderColor = palette.surfaceLine
        ) {
            Text(
                text = "No audiobooks match the current view.\nAdd authors or series in [ TRACKED ] or switch to [ ALL ].",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.comment
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filtered, key = { it.asin }) { rel ->
                val primaryAuthor = rel.authors.substringBefore(",").trim()
                val isAuthorTracked = primaryAuthor.lowercase(Locale.US) in trackedAuthorsLower
                val isSeriesTracked = rel.seriesTitle.isNotBlank() &&
                    rel.seriesTitle.lowercase(Locale.US) in trackedSeriesLower

                ReleaseCard(
                    rel = rel,
                    today = today,
                    alwaysShowActions = prefs.alwaysShowCardActions,
                    expanded = expandedAsin == rel.asin,
                    isAuthorTracked = isAuthorTracked,
                    isSeriesTracked = isSeriesTracked,
                    onToggleExpand = { onToggleExpand(rel.asin) },
                    onSelectSeries = { s -> onSelectSeriesFilter(s) },
                    onQuickTrackAuthor = { onQuickTrackAuthor(rel.authors) },
                    onQuickTrackSeries = { onQuickTrackSeries(rel.seriesTitle, rel.authors) },
                    onMuteBook = { onMuteBook(rel) },
                    onMuteSeries = { onMuteSeries(rel) },
                    onOpenLightbox = onOpenLightbox,
                    context = context
                )
            }
        }
    }
}

/**
 * Exact Classic Boxscore ReleaseCard matching the user's screenshot:
 * `+---[ 2026-11-05 [ OUT TODAY ] ]---+`
 * - Square CoverThumbnail on left with green border
 * - Uppercase bold Title in foreground
 * - Series in purple, Author in yellow, Narrator in orange
 * - Expanded view:
 *   `Runtime: 4h 8m  |  Publisher: Audible Originals  |  Match: [AUTHOR]` in cyan
 *   Full synopsis in foreground
 *   `[ + Track Author ]` in yellow
 *   `[ Audible ]` (green), `[ ABB Search ]` (cyan), `[ + Cal ]` (yellow), `[ DND Book ]` (red)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseCard(
    rel: ReleaseEntity,
    today: LocalDate,
    alwaysShowActions: Boolean,
    expanded: Boolean,
    isAuthorTracked: Boolean,
    isSeriesTracked: Boolean,
    onToggleExpand: () -> Unit,
    onSelectSeries: (String) -> Unit,
    onQuickTrackAuthor: () -> Unit,
    onQuickTrackSeries: () -> Unit,
    onMuteBook: () -> Unit,
    onMuteSeries: () -> Unit,
    onOpenLightbox: (String, String) -> Unit,
    context: Context
) {
    val palette = LocalPtsPalette.current
    val rDate = remember(rel.releaseDateIso) {
        runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
    }
    val deltaDays = remember(rDate, today) {
        if (rDate != null) ChronoUnit.DAYS.between(today, rDate) else null
    }

    val countdownTag = when {
        deltaDays == null -> "[ TBA ]"
        deltaDays == 0L -> "[ OUT TODAY ]"
        deltaDays == 1L -> "[ TOMORROW ]"
        deltaDays > 1L -> "[ IN ${deltaDays}d ]"
        else -> "[ ${-deltaDays}d AGO ]"
    }

    val extraTags = buildString {
        if (rel.isNewDiscovery) append(" • [ NEW ]")
        if (rel.narratorJustAnnounced) append(" • [ NARRATOR! ]")
        if (rel.previousReleaseDateIso.isNotBlank()) append(" • [ WAS ${rel.previousReleaseDateIso} ]")
    }

    val boxHeaderText = "${rel.releaseDateIso} $countdownTag$extraTags"

    val headerColor = when {
        deltaDays == 0L -> palette.green
        deltaDays != null && deltaDays in 1L..7L -> palette.yellow
        deltaDays != null && deltaDays > 7L -> palette.cyan
        else -> palette.comment
    }

    val borderColor = when {
        expanded -> palette.cyan
        deltaDays == 0L -> palette.green
        else -> palette.surfaceLine
    }

    val runtimeText = if (rel.runtimeMinutes > 0) {
        "${rel.runtimeMinutes / 60}h ${rel.runtimeMinutes % 60}m"
    } else "TBA"

    PlainTextBox(
        headerText = boxHeaderText,
        headerColor = headerColor,
        borderColor = borderColor,
        onClick = onToggleExpand
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            CoverThumbnail(
                url = rel.coverUrl,
                fallbackLabel = rel.title,
                size = 58.dp,
                borderColor = palette.green,
                onClick = if (rel.coverUrl.isNotBlank()) {
                    { onOpenLightbox(rel.coverUrl, rel.title) }
                } else null
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rel.title.uppercase(Locale.US),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = palette.foreground
                )

                if (rel.seriesTitle.isNotBlank()) {
                    val seqLabel = rel.seriesSequence?.let { " #$it" }.orEmpty()
                    Text(
                        text = "Series: ${rel.seriesTitle}$seqLabel",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.purple,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { onSelectSeries(rel.seriesTitle) }
                    )
                }

                Text(
                    text = "Author: ${rel.authors}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.yellow,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Text(
                    text = "Narrator: ${rel.narrators}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.orange,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (expanded || alwaysShowActions) {
            if (expanded) {
                PtsDivider()

                val matchBadge = rel.matchedTags.ifBlank { "AUTHOR" }
                val pubName = rel.publisher.ifBlank { "Audible Studios" }
                Text(
                    text = "Runtime: $runtimeText  |  Publisher: $pubName  |  Match: [$matchBadge]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.cyan
                )

                if (rel.synopsis.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = rel.synopsis,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = palette.foreground
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!isAuthorTracked) {
                        PtsBracketButton(
                            label = "+ Track Author",
                            color = palette.yellow,
                            onClick = onQuickTrackAuthor
                        )
                    }
                    if (rel.seriesTitle.isNotBlank() && !isSeriesTracked) {
                        PtsBracketButton(
                            label = "+ Track Series",
                            color = palette.purple,
                            onClick = onQuickTrackSeries
                        )
                    }
                }
            }

            PtsDivider()

            // Row 1: Audible & Manual ABB Search
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PtsBracketButton(
                    label = "Audible",
                    color = palette.green,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.audible.com/pd/${rel.asin}"))
                            )
                        }
                    }
                )

                PtsBracketButton(
                    label = "ABB Search",
                    color = palette.cyan,
                    onClick = {
                        val mirror = AudibleApiClient.getOrderedAbbMirrors().first()
                        val coreTitle = rel.title.substringBefore(":").trim()
                        val primaryAuthor = rel.authors.substringBefore(",").trim()
                        val q = URLEncoder.encode(
                            AudibleApiClient.normalizeForAbbComparison("$coreTitle $primaryAuthor"),
                            "UTF-8"
                        )
                        val url = "$mirror/?s=$q&cat=undefined%2Cundefined"
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: [ + Cal ] on left, [ DND Book ] / [ DND Series ] on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PtsBracketButton(
                    label = "+ Cal",
                    color = palette.yellow,
                    onClick = {
                        val startMillis = rDate?.atStartOfDay(ZoneId.systemDefault())
                            ?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis()
                        val calIntent = Intent(Intent.ACTION_INSERT).apply {
                            data = CalendarContract.Events.CONTENT_URI
                            putExtra(CalendarContract.Events.TITLE, "Audiobook: ${rel.title}")
                            putExtra(
                                CalendarContract.Events.DESCRIPTION,
                                "Author: ${rel.authors}\nNarrator: ${rel.narrators}\nAudible: https://www.audible.com/pd/${rel.asin}"
                            )
                            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 86_400_000L)
                        }
                        runCatching {
                            context.startActivity(calIntent)
                        }.onFailure {
                            Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rel.seriesTitle.isNotBlank()) {
                        PtsBracketButton(
                            label = "DND Series",
                            color = palette.red,
                            onClick = onMuteSeries
                        )
                    }
                    PtsBracketButton(
                        label = "DND Book",
                        color = palette.red,
                        onClick = onMuteBook
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackedItemsView(
    repo: ReleaseRepository,
    trackedItems: List<TrackedItemEntity>,
    releases: List<ReleaseEntity>,
    onFilterByAuthorOrNarrator: (String) -> Unit,
    onFilterBySeries: (String) -> Unit,
    onOpenLightbox: (String, String) -> Unit,
    onStatusMessage: (String) -> Unit
) {
    val palette = LocalPtsPalette.current
    val scope = rememberCoroutineScope()

    var selectedType by rememberSaveable { mutableStateOf(TrackType.AUTHOR) }
    var queryInput by rememberSaveable { mutableStateOf("") }
    var catalogPreview by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            PlainTextBox(
                headerText = "ADD TO WATCHLIST [ ${selectedType.name} ]",
                headerColor = palette.green,
                borderColor = palette.cyan
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    TrackType.entries.forEach { t ->
                        PtsBracketButton(
                            label = t.name,
                            selected = selectedType == t,
                            color = palette.yellow,
                            onClick = {
                                selectedType = t
                                catalogPreview = emptyList()
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.surfaceLine)
                        .background(palette.background)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "> ",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = palette.green
                    )
                    BasicTextField(
                        value = queryInput,
                        onValueChange = { txt ->
                            queryInput = txt
                            searchJob?.cancel()
                            if (txt.trim().length >= 2) {
                                searchJob = scope.launch {
                                    delay(320L)
                                    isSearching = true
                                    catalogPreview = withContext(Dispatchers.IO) {
                                        when (selectedType) {
                                            TrackType.AUTHOR -> AudibleApiClient.searchCatalog(author = txt.trim(), maxPages = 1)
                                            TrackType.NARRATOR -> AudibleApiClient.searchCatalog(narrator = txt.trim(), maxPages = 1)
                                            TrackType.SERIES -> AudibleApiClient.searchCatalog(titleOrSeries = txt.trim(), maxPages = 1)
                                            TrackType.BOOK -> AudibleApiClient.searchCatalog(titleOrSeries = txt.trim(), maxPages = 1)
                                        }.take(6)
                                    }
                                    isSearching = false
                                }
                            } else {
                                catalogPreview = emptyList()
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = palette.foreground
                        ),
                        cursorBrush = SolidColor(palette.green),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (queryInput.isEmpty()) {
                                Text(
                                    text = "Enter ${selectedType.name.lowercase(Locale.US)} name...",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = palette.comment
                                )
                            }
                            inner()
                        }
                    )

                    PtsBracketButton(
                        label = "+ Track",
                        color = palette.green,
                        onClick = {
                            val clean = queryInput.trim()
                            if (clean.isNotEmpty()) {
                                scope.launch {
                                    queryInput = ""
                                    catalogPreview = emptyList()
                                    onStatusMessage("Tracking $clean & syncing Audible...")
                                    repo.addTrackedItemUnique(type = selectedType, rawQuery = clean)
                                    repo.syncCatalog(forceRefresh = false)
                                    onStatusMessage("Tracked $clean")
                                }
                            }
                        }
                    )
                }

                if (isSearching) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Searching Audible catalog...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = palette.comment
                    )
                } else if (catalogPreview.isNotEmpty()) {
                    PtsDivider()
                    Text(
                        text = "AUDIBLE CATALOG MATCHES (TAP TO PIN):",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = palette.cyan,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    catalogPreview.forEach { prod ->
                        val targetName = when (selectedType) {
                            TrackType.AUTHOR -> prod.authors.firstOrNull().orEmpty().ifBlank { queryInput.trim() }
                            TrackType.NARRATOR -> prod.narrators.firstOrNull().orEmpty().ifBlank { queryInput.trim() }
                            TrackType.SERIES -> prod.seriesTitle.ifBlank { prod.title }
                            TrackType.BOOK -> prod.title
                        }
                        val extId = when (selectedType) {
                            TrackType.AUTHOR -> prod.primaryAuthorAsin.ifBlank { null }
                            TrackType.SERIES -> "${prod.seriesAsin}|${prod.authors.firstOrNull().orEmpty()}"
                            TrackType.BOOK -> "${prod.asin}|${prod.authors.firstOrNull().orEmpty()}"
                            else -> null
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        queryInput = ""
                                        catalogPreview = emptyList()
                                        onStatusMessage("Tracking $targetName...")
                                        repo.addTrackedItemUnique(
                                            type = selectedType,
                                            rawQuery = targetName,
                                            initialThumbnailUrl = if (selectedType == TrackType.SERIES || selectedType == TrackType.BOOK) prod.coverUrl else "",
                                            externalId = extId
                                        )
                                        repo.syncCatalog(forceRefresh = false)
                                        onStatusMessage("Tracked $targetName")
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverThumbnail(
                                url = prod.coverUrl,
                                fallbackLabel = targetName,
                                size = 40.dp,
                                borderColor = palette.cyan
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = targetName,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = palette.yellow,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${prod.title} (${prod.releaseDateIso})",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = palette.comment,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "[ + Pin ]",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = palette.green
                            )
                        }
                    }
                }
            }
        }

        if (trackedItems.isEmpty()) {
            item {
                PlainTextBox(
                    headerText = "WATCHLIST EMPTY",
                    headerColor = palette.comment,
                    borderColor = palette.surfaceLine
                ) {
                    Text(
                        text = "No authors, series, or narrators tracked yet.",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.comment
                    )
                }
            }
        } else {
            items(trackedItems, key = { it.id }) { item ->
                val matchCount = remember(releases, item) {
                    releases.count { rel ->
                        when (item.type) {
                            TrackType.AUTHOR -> rel.authors.contains(item.queryValue, ignoreCase = true)
                            TrackType.NARRATOR -> rel.narrators.contains(item.queryValue, ignoreCase = true)
                            TrackType.SERIES -> rel.seriesTitle.equals(item.queryValue, ignoreCase = true)
                            TrackType.BOOK -> rel.title.contains(item.queryValue, ignoreCase = true)
                        }
                    }
                }

                val headerColor = when (item.type) {
                    TrackType.AUTHOR -> palette.yellow
                    TrackType.SERIES -> palette.purple
                    TrackType.NARRATOR -> palette.orange
                    TrackType.BOOK -> palette.cyan
                }

                PlainTextBox(
                    headerText = "${item.type.name} • $matchCount RELEASE(S)",
                    headerColor = headerColor,
                    borderColor = palette.surfaceLine,
                    onClick = {
                        if (item.type == TrackType.SERIES) {
                            onFilterBySeries(item.queryValue)
                        } else {
                            onFilterByAuthorOrNarrator(item.queryValue)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverThumbnail(
                            url = item.thumbnailUrl,
                            fallbackLabel = item.queryValue,
                            size = 52.dp,
                            borderColor = headerColor,
                            onClick = if (item.thumbnailUrl.isNotBlank()) {
                                { onOpenLightbox(item.thumbnailUrl, item.queryValue) }
                            } else null
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.queryValue.uppercase(Locale.US),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = palette.foreground
                            )
                            Text(
                                text = "Tap card to filter releases",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = palette.cyan,
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            if (item.type == TrackType.SERIES) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 6.dp)
                                ) {
                                    Text(
                                        text = "Last Read: #${item.lastReadSequence}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = palette.purple
                                    )
                                    PtsBracketButton(label = "-", color = palette.yellow) {
                                        scope.launch {
                                            repo.updateSeriesReadProgress(item.id, item.lastReadSequence - 1)
                                        }
                                    }
                                    PtsBracketButton(label = "+", color = palette.green) {
                                        scope.launch {
                                            repo.updateSeriesReadProgress(item.id, item.lastReadSequence + 1)
                                        }
                                    }
                                }
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (item.type == TrackType.AUTHOR || item.type == TrackType.NARRATOR) {
                                PtsBracketButton(
                                    label = "Img",
                                    color = palette.cyan
                                ) {
                                    scope.launch {
                                        onStatusMessage("Refreshing portrait for ${item.queryValue}...")
                                        repo.refreshSingleTrackedPortrait(item)
                                        onStatusMessage("Updated portrait for ${item.queryValue}")
                                    }
                                }
                            }
                            PtsBracketButton(
                                label = "Remove",
                                color = palette.red
                            ) {
                                scope.launch {
                                    repo.removeTrackedItem(item)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarScheduleView(
    releases: List<ReleaseEntity>,
    today: LocalDate,
    onSelectRelease: (ReleaseEntity) -> Unit
) {
    val palette = LocalPtsPalette.current
    val grouped = remember(releases, today) {
        releases
            .filter { rel ->
                val d = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
                d != null && !d.isBefore(today.minusDays(7))
            }
            .sortedBy { it.releaseDateIso }
            .groupBy { it.releaseDateIso.take(7) }
    }

    if (grouped.isEmpty()) {
        PlainTextBox(
            headerText = "RELEASE CALENDAR",
            headerColor = palette.comment,
            borderColor = palette.surfaceLine
        ) {
            Text(
                text = "No upcoming releases scheduled.",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.comment
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            grouped.forEach { (monthKey, itemsInMonth) ->
                item(key = "month_$monthKey") {
                    PlainTextBox(
                        headerText = "$monthKey [ ${itemsInMonth.size} BOOK(S) ]",
                        headerColor = palette.green,
                        borderColor = palette.cyan
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsInMonth.forEachIndexed { idx, rel ->
                                if (idx > 0) {
                                    PtsDivider()
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectRelease(rel) }
                                ) {
                                    Text(
                                        text = "${rel.releaseDateIso}  —  ${rel.title.uppercase(Locale.US)}",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = palette.foreground
                                    )
                                    Text(
                                        text = "Author: ${rel.authors}  |  Narrator: ${rel.narrators}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = palette.yellow,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsView(
    repo: ReleaseRepository,
    prefs: FilterPrefs,
    dndRules: List<DndRuleEntity>,
    onUpdatePrefs: (FilterPrefs) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onStatusMessage: (String) -> Unit
) {
    val palette = LocalPtsPalette.current
    val scope = rememberCoroutineScope()
    var cacheStats by remember { mutableStateOf(CacheStats(0, 0L)) }

    LaunchedEffect(Unit) {
        cacheStats = AudibleApiClient.getCacheStats()
    }

    val lastSyncMs = remember { repo.getLastSyncEpochMs() }
    val lastSyncFormatted = remember(lastSyncMs) {
        if (lastSyncMs <= 0L) "Never"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lastSyncMs))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            PlainTextBox(
                headerText = "THEME PALETTE [ ${prefs.themeModeCode} ]",
                headerColor = palette.purple,
                borderColor = palette.cyan
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "SYSTEM" to "System Auto",
                        "DARK" to "Dracula Dark",
                        "LIGHT" to "Alucard Light"
                    ).forEach { (code, label) ->
                        PtsBracketButton(
                            label = label,
                            selected = prefs.themeModeCode.equals(code, ignoreCase = true),
                            color = palette.purple,
                            onClick = { onUpdatePrefs(prefs.copy(themeModeCode = code)) }
                        )
                    }
                }
            }
        }

        item {
            PlainTextBox(
                headerText = "CATALOG FILTERS & CARD OPTIONS",
                headerColor = palette.green,
                borderColor = palette.cyan
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("English Releases Only", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        PtsBracketButton(
                            label = if (prefs.englishOnly) "ON" else "OFF",
                            selected = prefs.englishOnly,
                            color = palette.green,
                            onClick = { onUpdatePrefs(prefs.copy(englishOnly = !prefs.englishOnly)) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Hide Abridged Editions", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        PtsBracketButton(
                            label = if (prefs.hideAbridged) "ON" else "OFF",
                            selected = prefs.hideAbridged,
                            color = palette.green,
                            onClick = { onUpdatePrefs(prefs.copy(hideAbridged = !prefs.hideAbridged)) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Hide Full-Cast / Dramatized", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        PtsBracketButton(
                            label = if (prefs.hideDramatized) "ON" else "OFF",
                            selected = prefs.hideDramatized,
                            color = palette.green,
                            onClick = { onUpdatePrefs(prefs.copy(hideDramatized = !prefs.hideDramatized)) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Always Show Action Links on Cards", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        PtsBracketButton(
                            label = if (prefs.alwaysShowCardActions) "ON" else "OFF",
                            selected = prefs.alwaysShowCardActions,
                            color = palette.green,
                            onClick = { onUpdatePrefs(prefs.copy(alwaysShowCardActions = !prefs.alwaysShowCardActions)) }
                        )
                    }
                }
            }
        }

        item {
            PlainTextBox(
                headerText = "DAILY NOTIFICATION CHECK [ %02d:%02d ]".format(prefs.notifyHour, prefs.notifyMinute),
                headerColor = palette.yellow,
                borderColor = palette.cyan
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(6, 9, 12, 18, 21).forEach { hr ->
                        PtsBracketButton(
                            label = "%02d:00".format(hr),
                            selected = prefs.notifyHour == hr,
                            color = palette.yellow,
                            onClick = { onUpdatePrefs(prefs.copy(notifyHour = hr, notifyMinute = 0)) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Last Sync: $lastSyncFormatted",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.comment
                )
            }
        }

        item {
            PlainTextBox(
                headerText = "BACKUP, RESTORE & CACHE",
                headerColor = palette.cyan,
                borderColor = palette.cyan
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PtsBracketButton(label = "Export JSON", color = palette.green, onClick = onExportBackup)
                    PtsBracketButton(label = "Import JSON", color = palette.yellow, onClick = onImportBackup)
                    PtsBracketButton(
                        label = "Clear Cache (${cacheStats.formattedSize})",
                        color = palette.orange,
                        onClick = {
                            scope.launch {
                                cacheStats = AudibleApiClient.clearAllCaches()
                                onStatusMessage("Cleared disk & memory caches")
                            }
                        }
                    )
                }
            }
        }

        if (dndRules.isNotEmpty()) {
            item {
                PlainTextBox(
                    headerText = "DND MUTED BOOKS & SERIES (${dndRules.size})",
                    headerColor = palette.red,
                    borderColor = palette.surfaceLine
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        dndRules.forEach { rule ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "[${rule.targetType.name}] ${rule.displayTitle}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = palette.foreground,
                                    modifier = Modifier.weight(1f)
                                )
                                PtsBracketButton(
                                    label = "Unmute",
                                    color = palette.green
                                ) {
                                    scope.launch {
                                        repo.removeDndRule(rule)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            PlainTextBox(
                headerText = "APPLICATION INFO",
                headerColor = palette.purple,
                borderColor = palette.surfaceLine
            ) {
                PtsBracketButton(
                    label = "ABOUT ->",
                    color = palette.cyan,
                    onClick = onOpenAbout
                )
            }
        }
    }
}

@Composable
private fun AboutView(onBack: () -> Unit) {
    val palette = LocalPtsPalette.current
    PlainTextBox(
        headerText = "ABOUT NOCTURNETXT",
        headerColor = palette.green,
        borderColor = palette.cyan
    ) {
        PtsBracketButton(label = "<- BACK TO SETTINGS", color = palette.yellow, onClick = onBack)

        PtsDivider()

        Text(
            text = "NocturneTXT",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = palette.purple
        )

        Text(
            text = "Version 3.3.0 (Build 23)",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = palette.cyan,
            modifier = Modifier.padding(top = 4.dp)
        )

        Text(
            text = "Package: com.plaintext.audible",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = palette.comment,
            modifier = Modifier.padding(top = 2.dp)
        )

        PtsDivider()

        Text(
            text = "• Plain-text boxscore UI inspired by Plain Text Sports (plaintextsports.com)\n" +
                "• Official Dracula Dark & Alucard Light color specifications (draculatheme.com/spec)\n" +
                "• Public Audible Catalog API + Audnexus Author Directory\n" +
                "• Direct 1-tap AudioBookBay manual search & Android Calendar integration",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = palette.foreground
        )
    }
}
