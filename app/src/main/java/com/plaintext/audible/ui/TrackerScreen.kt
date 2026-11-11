package com.plaintext.audible.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

private enum class MainTab(val title: String) {
    RELEASES("RELEASES"),
    TRACKED("TRACKED"),
    CALENDAR("CALENDAR"),
    SETTINGS("SETTINGS")
}

private enum class ReleaseFilterMode(val label: String) {
    UPCOMING("UPCOMING"),
    OUT_NOW("OUT NOW"),
    ON_ABB("ON ABB"),
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
    var checkingAbbAsin by remember { mutableStateOf<String?>(null) }

    var lightboxUrl by remember { mutableStateOf<String?>(null) }
    var lightboxTitle by remember { mutableStateOf<String?>(null) }

    // Clear stale author filter if that author is no longer tracked
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
    val abbCount = remember(releases) { releases.count { it.abbAvailable } }
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
                // Top Telemetry & Action Bar (No app name or version in top bar)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TRACKED:${trackedItems.size}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = palette.comment
                        )
                        Text(
                            text = "UPCOMING:$upcomingCount",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = palette.cyan
                        )
                        if (abbCount > 0) {
                            Text(
                                text = "ABB:$abbCount",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = palette.green
                            )
                        }
                        if (newCount > 0) {
                            Text(
                                text = "NEW:$newCount",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = palette.orange
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color = palette.purple
                            )
                        }
                        PtsBracketButton(
                            label = if (isSyncing) "SYNCING..." else "SYNC NOW",
                            color = palette.green,
                            onClick = {
                                if (!isSyncing) {
                                    scope.launch {
                                        isSyncing = true
                                        syncStatusLine = "Polling Audible & AudioBookBay mirrors..."
                                        val batch = repo.syncCatalog(forceRefresh = true, checkAbb = true)
                                        isSyncing = false
                                        syncStatusLine =
                                            "Sync complete (${batch.abbNewlyAvailable.size} new on ABB, ${batch.releaseDay.size} out today)"
                                    }
                                }
                            }
                        )
                    }
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
                        PtsBracketButton(
                            label = tab.title,
                            selected = currentTab == tab,
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
                            checkingAbbAsin = checkingAbbAsin,
                            onCheckAbb = { rel ->
                                if (checkingAbbAsin == null) {
                                    scope.launch {
                                        checkingAbbAsin = rel.asin
                                        val match = repo.checkSingleReleaseOnAbb(rel)
                                        checkingAbbAsin = null
                                        Toast.makeText(
                                            context,
                                            match.diagnosticMessage,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            onMuteBook = { rel ->
                                scope.launch {
                                    repo.muteBook(rel.asin, rel.title)
                                    Toast.makeText(context, "[ Muted ${rel.title} ]", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onMuteSeries = { rel ->
                                if (rel.seriesTitle.isNotBlank()) {
                                    scope.launch {
                                        repo.muteSeries(rel.seriesTitle, rel.seriesTitle)
                                        Toast.makeText(context, "[ Muted Series: ${rel.seriesTitle} ]", Toast.LENGTH_SHORT).show()
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.background)
                            .border(2.dp, palette.purple)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CoverThumbnail(
                            url = lightboxUrl!!,
                            fallbackLabel = lightboxTitle ?: "IMG",
                            size = 260.dp
                        )
                        if (!lightboxTitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = lightboxTitle!!,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = palette.foreground
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        PtsBracketButton(label = "CLOSE") {
                            lightboxUrl = null
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
    checkingAbbAsin: String?,
    onCheckAbb: (ReleaseEntity) -> Unit,
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
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReleaseFilterMode.entries.forEach { mode ->
            PtsBracketButton(
                label = mode.label,
                selected = filterMode == mode,
                onClick = { onFilterModeChange(mode) }
            )
        }
        if (releases.any { it.isNewDiscovery || it.narratorJustAnnounced }) {
            PtsBracketButton(
                label = "CLEAR NEW",
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
                    label = "FILTER: $activeAuthorFilter X",
                    color = palette.pink,
                    onClick = onClearAuthorFilter
                )
            }
            if (activeSeriesFilter != null) {
                PtsBracketButton(
                    label = "SERIES: $activeSeriesFilter X",
                    color = palette.cyan,
                    onClick = { onSelectSeriesFilter(null) }
                )
            }
        }
    }

    // Search input
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.surfaceLine)
            .background(palette.surfaceLine.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FIND > ",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = palette.purple
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
            cursorBrush = SolidColor(palette.purple),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (searchText.isEmpty()) {
                    Text(
                        text = "title, author, series, narrator...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.comment
                    )
                }
                inner()
            }
        )
        if (searchText.isNotEmpty()) {
            PtsBracketButton(label = "CLR") { onSearchTextChange("") }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

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
                ReleaseFilterMode.ON_ABB -> rel.abbAvailable
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 30.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = "No audiobooks match the current filter.\nAdd authors or series in [ TRACKED ] or switch to [ ALL ].",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.comment
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.asin }) { rel ->
                ReleaseCard(
                    rel = rel,
                    today = today,
                    alwaysShowActions = prefs.alwaysShowCardActions,
                    expanded = expandedAsin == rel.asin,
                    isCheckingAbb = checkingAbbAsin == rel.asin,
                    onToggleExpand = { onToggleExpand(rel.asin) },
                    onSelectSeries = { s -> onSelectSeriesFilter(s) },
                    onCheckAbb = { onCheckAbb(rel) },
                    onMuteBook = { onMuteBook(rel) },
                    onMuteSeries = { onMuteSeries(rel) },
                    onOpenLightbox = onOpenLightbox,
                    context = context
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseCard(
    rel: ReleaseEntity,
    today: LocalDate,
    alwaysShowActions: Boolean,
    expanded: Boolean,
    isCheckingAbb: Boolean,
    onToggleExpand: () -> Unit,
    onSelectSeries: (String) -> Unit,
    onCheckAbb: () -> Unit,
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

    val statusText = when {
        deltaDays == null -> "TBA"
        deltaDays > 0L -> "IN ${deltaDays}D"
        deltaDays == 0L -> "OUT TODAY"
        else -> "${-deltaDays}D AGO"
    }
    val statusColor = when {
        deltaDays == null -> palette.comment
        deltaDays > 0L -> palette.cyan
        deltaDays == 0L -> palette.green
        else -> palette.comment
    }

    val runtimeText = if (rel.runtimeMinutes > 0) {
        "${rel.runtimeMinutes / 60}h ${rel.runtimeMinutes % 60}m"
    } else "TBA"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (rel.abbAvailable) palette.green else palette.surfaceLine)
            .background(palette.surfaceLine.copy(alpha = 0.25f))
            .clickable { onToggleExpand() }
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rel.releaseDateIso,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = palette.foreground
                )
                PtsBadge(label = statusText, color = statusColor)
                if (rel.abbAvailable) {
                    PtsBadge(label = "ON ABB", color = palette.green)
                }
                if (rel.isNewDiscovery) {
                    PtsBadge(label = "NEW", color = palette.orange)
                }
                if (rel.narratorJustAnnounced) {
                    PtsBadge(label = "NARRATOR!", color = palette.pink)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            CoverThumbnail(
                url = rel.coverUrl,
                fallbackLabel = rel.title,
                size = 56.dp,
                onClick = if (rel.coverUrl.isNotBlank()) {
                    { onOpenLightbox(rel.coverUrl, rel.title) }
                } else null
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rel.title,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = palette.foreground
                )
                if (rel.seriesTitle.isNotBlank()) {
                    val seqLabel = rel.seriesSequence?.let { " #$it" }.orEmpty()
                    Text(
                        text = "Series: ${rel.seriesTitle}$seqLabel [Filter Series]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.cyan,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { onSelectSeries(rel.seriesTitle) }
                    )
                }
                Text(
                    text = "Author: ${rel.authors}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.pink,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = "Narrator: ${rel.narrators} • Len: $runtimeText",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.comment,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (expanded || alwaysShowActions) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 1.dp, color = palette.surfaceLine)
            Spacer(modifier = Modifier.height(6.dp))

            if (expanded && rel.synopsis.isNotBlank()) {
                Text(
                    text = rel.synopsis,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.foreground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PtsBracketButton(
                    label = if (isCheckingAbb) "CHECKING ABB..." else "CHECK ABB",
                    color = palette.cyan,
                    onClick = onCheckAbb
                )

                PtsBracketButton(
                    label = "ABB SEARCH",
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

                if (rel.abbPostUrl.isNotBlank()) {
                    PtsBracketButton(
                        label = "ABB POST",
                        color = palette.green,
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.abbPostUrl)))
                            }
                        }
                    )
                }

                if (rel.abbMagnetUri.isNotBlank()) {
                    PtsBracketButton(
                        label = "MAGNET",
                        color = palette.green,
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.abbMagnetUri)))
                            }.onFailure {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("ABB Magnet", rel.abbMagnetUri))
                                Toast.makeText(
                                    context,
                                    "Magnet URI copied to clipboard",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    PtsBracketButton(
                        label = "COPY MAGNET",
                        color = palette.pink,
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("ABB Magnet", rel.abbMagnetUri))
                            Toast.makeText(context, "Magnet URI copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                PtsBracketButton(
                    label = "AUDIBLE",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.audible.com/pd/${rel.asin}"))
                            )
                        }
                    }
                )

                PtsBracketButton(
                    label = "MUTE BOOK",
                    color = palette.red,
                    onClick = onMuteBook
                )

                if (rel.seriesTitle.isNotBlank()) {
                    PtsBracketButton(
                        label = "MUTE SERIES",
                        color = palette.orange,
                        onClick = onMuteSeries
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

    Column(modifier = Modifier.fillMaxSize()) {
        PtsSectionBanner(
            title = "ADD AUTHOR / SERIES / NARRATOR",
            rightText = selectedType.name
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            TrackType.entries.forEach { t ->
                PtsBracketButton(
                    label = t.name,
                    selected = selectedType == t,
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
                .background(palette.surfaceLine.copy(alpha = 0.35f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${selectedType.name} > ",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = palette.purple
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
                                    TrackType.SERIES -> AudibleApiClient.searchCatalog(title = txt.trim(), keywords = txt.trim(), maxPages = 1)
                                    TrackType.BOOK -> AudibleApiClient.searchCatalog(title = txt.trim(), maxPages = 1)
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
                cursorBrush = SolidColor(palette.purple),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (queryInput.isEmpty()) {
                        Text(
                            text = "Type name and tap [+ TRACK]...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = palette.comment
                        )
                    }
                    inner()
                }
            )

            PtsBracketButton(
                label = "+ TRACK",
                color = palette.green,
                onClick = {
                    val clean = queryInput.trim()
                    if (clean.isNotEmpty()) {
                        scope.launch {
                            queryInput = ""
                            catalogPreview = emptyList()
                            onStatusMessage("Adding $clean & syncing Audible catalog...")
                            repo.addTrackedItemUnique(type = selectedType, rawQuery = clean)
                            repo.syncCatalog(forceRefresh = false, checkAbb = true)
                            onStatusMessage("Tracked $clean")
                        }
                    }
                }
            )
        }

        if (isSearching) {
            Text(
                text = "Searching Audible catalog...",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = palette.comment,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else if (catalogPreview.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.purple)
                    .background(palette.surfaceLine.copy(alpha = 0.4f))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "AUDIBLE CATALOG MATCHES (TAP TO TRACK):",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = palette.cyan
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
                                    repo.syncCatalog(forceRefresh = false, checkAbb = true)
                                    onStatusMessage("Tracked $targetName")
                                }
                            }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverThumbnail(url = prod.coverUrl, fallbackLabel = targetName, size = 36.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = targetName,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = palette.foreground,
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
                            text = "[+ ADD]",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = palette.green
                        )
                    }
                }
            }
        }

        PtsSectionBanner(
            title = "ACTIVE WATCHLIST (${trackedItems.size})",
            rightText = "TAP TO VIEW RELEASES"
        )

        if (trackedItems.isEmpty()) {
            Text(
                text = "Your watchlist is empty. Add an author, series, or narrator above.",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.comment,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, palette.surfaceLine)
                            .background(palette.surfaceLine.copy(alpha = 0.25f))
                            .clickable {
                                if (item.type == TrackType.SERIES) {
                                    onFilterBySeries(item.queryValue)
                                } else {
                                    onFilterByAuthorOrNarrator(item.queryValue)
                                }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverThumbnail(
                            url = item.thumbnailUrl,
                            fallbackLabel = item.queryValue,
                            size = 48.dp,
                            isCircular = item.type == TrackType.AUTHOR || item.type == TrackType.NARRATOR,
                            onClick = if (item.thumbnailUrl.isNotBlank()) {
                                { onOpenLightbox(item.thumbnailUrl, item.queryValue) }
                            } else null
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.queryValue,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = palette.foreground
                            )
                            Text(
                                text = "${item.type.name} • $matchCount book(s)",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = palette.cyan
                            )
                            if (item.type == TrackType.SERIES) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = "Last Read #${item.lastReadSequence}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = palette.comment
                                    )
                                    PtsBracketButton(label = "-") {
                                        scope.launch {
                                            repo.updateSeriesReadProgress(item.id, item.lastReadSequence - 1)
                                        }
                                    }
                                    PtsBracketButton(label = "+") {
                                        scope.launch {
                                            repo.updateSeriesReadProgress(item.id, item.lastReadSequence + 1)
                                        }
                                    }
                                }
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (item.type == TrackType.AUTHOR || item.type == TrackType.NARRATOR) {
                                PtsBracketButton(label = "IMG") {
                                    scope.launch {
                                        onStatusMessage("Refreshing portrait for ${item.queryValue}...")
                                        repo.refreshSingleTrackedPortrait(item)
                                        onStatusMessage("Updated portrait for ${item.queryValue}")
                                    }
                                }
                            }
                            PtsBracketButton(
                                label = "DEL",
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

    Column(modifier = Modifier.fillMaxSize()) {
        PtsSectionBanner(
            title = "CHRONOLOGICAL RELEASE CALENDAR",
            rightText = "${grouped.values.sumOf { it.size }} TITLES"
        )

        if (grouped.isEmpty()) {
            Text(
                text = "No upcoming releases scheduled.",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.comment,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                grouped.forEach { (monthKey, itemsInMonth) ->
                    item(key = "month_$monthKey") {
                        PtsSectionBanner(
                            title = monthKey,
                            rightText = "${itemsInMonth.size} BOOK(S)"
                        )
                    }
                    items(itemsInMonth, key = { "cal_${it.asin}" }) { rel ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, palette.surfaceLine)
                                .background(palette.surfaceLine.copy(alpha = 0.25f))
                                .clickable { onSelectRelease(rel) }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${rel.releaseDateIso} • ${rel.title}",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = palette.foreground
                                )
                                Text(
                                    text = "by ${rel.authors}" +
                                        (if (rel.seriesTitle.isNotBlank()) " (${rel.seriesTitle} #${rel.seriesSequence ?: ""})" else ""),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = palette.comment
                                )
                            }
                            if (rel.abbAvailable) {
                                PtsBadge(label = "ABB", color = palette.green)
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PtsSectionBanner(
                title = "COLOR THEME (DRACULA / ALUCARD)",
                rightText = prefs.themeModeCode
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("SYSTEM", "DARK", "LIGHT").forEach { mode ->
                    PtsBracketButton(
                        label = mode,
                        selected = prefs.themeModeCode.equals(mode, ignoreCase = true),
                        onClick = { onUpdatePrefs(prefs.copy(themeModeCode = mode)) }
                    )
                }
            }
        }

        item {
            PtsSectionBanner(title = "CATALOG & AUDIOBOOKBAY FILTERS")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("English Releases Only", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    PtsBracketButton(
                        label = if (prefs.englishOnly) "ON" else "OFF",
                        selected = prefs.englishOnly,
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
                        onClick = { onUpdatePrefs(prefs.copy(hideDramatized = !prefs.hideDramatized)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Check AudioBookBay Daily", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    PtsBracketButton(
                        label = if (prefs.checkAbbDaily) "ON" else "OFF",
                        selected = prefs.checkAbbDaily,
                        onClick = { onUpdatePrefs(prefs.copy(checkAbbDaily = !prefs.checkAbbDaily)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Always Show Action Buttons on Cards", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    PtsBracketButton(
                        label = if (prefs.alwaysShowCardActions) "ON" else "OFF",
                        selected = prefs.alwaysShowCardActions,
                        onClick = { onUpdatePrefs(prefs.copy(alwaysShowCardActions = !prefs.alwaysShowCardActions)) }
                    )
                }
            }
        }

        item {
            PtsSectionBanner(
                title = "DAILY CHECK SCHEDULE",
                rightText = "%02d:%02d".format(prefs.notifyHour, prefs.notifyMinute)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(6, 9, 12, 18, 21).forEach { hr ->
                    PtsBracketButton(
                        label = "%02d:00".format(hr),
                        selected = prefs.notifyHour == hr,
                        onClick = { onUpdatePrefs(prefs.copy(notifyHour = hr, notifyMinute = 0)) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Last Sync: $lastSyncFormatted",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = palette.comment
            )
        }

        item {
            PtsSectionBanner(title = "BACKUP, RESTORE & CACHE")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PtsBracketButton(label = "EXPORT JSON", onClick = onExportBackup)
                PtsBracketButton(label = "IMPORT JSON", onClick = onImportBackup)
                PtsBracketButton(
                    label = "CLEAR CACHE (${cacheStats.formattedSize})",
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

        if (dndRules.isNotEmpty()) {
            item {
                PtsSectionBanner(title = "MUTED BOOKS & SERIES (${dndRules.size})")
            }
            items(dndRules, key = { it.targetKey }) { rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.surfaceLine)
                        .padding(6.dp),
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
                        label = "UNMUTE",
                        color = palette.green
                    ) {
                        scope.launch {
                            repo.removeDndRule(rule)
                        }
                    }
                }
            }
        }

        item {
            PtsSectionBanner(title = "APPLICATION INFO")
            PtsBracketButton(
                label = "ABOUT ->",
                color = palette.cyan,
                onClick = onOpenAbout
            )
        }
    }
}

@Composable
private fun AboutView(onBack: () -> Unit) {
    val palette = LocalPtsPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, palette.surfaceLine)
            .background(palette.surfaceLine.copy(alpha = 0.25f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PtsBracketButton(label = "<- BACK TO SETTINGS", onClick = onBack)

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
            color = palette.cyan
        )

        Text(
            text = "Package: com.plaintext.audible",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = palette.comment
        )

        PtsDivider()

        Text(
            text = "Architecture & Credits:\n" +
                "• Plain-text boxscore UI inspired by Plain Text Sports (plaintextsports.com)\n" +
                "• Official Dracula Dark & Alucard Light color palettes (draculatheme.com/spec)\n" +
                "• Audible Catalog API + Audnexus Author Directory\n" +
                "• AudioBookBay availability verification with WebView Cloudflare fallback & BitTorrent magnet synthesis",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = palette.foreground
        )
    }
}
