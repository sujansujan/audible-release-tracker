package com.plaintext.audible.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.plaintext.audible.data.AuthorCatalogSuggestion
import com.plaintext.audible.data.AuthorEntity
import com.plaintext.audible.data.BackupManager
import com.plaintext.audible.data.ReleaseEntity
import com.plaintext.audible.data.ReleaseRepository
import com.plaintext.audible.worker.ReleaseCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

enum class TrackerTab(val label: String) {
    RELEASES("RELEASES"),
    AUTHORS("AUTHORS"),
    CALENDAR("CALENDAR"),
    SETTINGS("SETTINGS")
}

enum class ReleaseSubFilter(val label: String) {
    UPCOMING("UPCOMING"),
    RECENT("RECENT"),
    PINNED("PINNED"),
    ON_ABB("ON ABB"),
    ALL("ALL")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackerScreen(
    repo: ReleaseRepository,
    themeMode: String,
    onThemeModeChange: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalPtsColors.current
    val scope = rememberCoroutineScope()

    val authors by repo.authorsFlow.collectAsState(initial = emptyList())
    val releases by repo.releasesFlow.collectAsState(initial = emptyList())
    val syncLogs by repo.syncLogsFlow.collectAsState(initial = emptyList())

    var activeTab by rememberSaveable { mutableStateOf(TrackerTab.RELEASES) }
    var releaseFilter by rememberSaveable { mutableStateOf(ReleaseSubFilter.UPCOMING) }
    var selectedAuthorFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSeriesFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedAsin by rememberSaveable { mutableStateOf<String?>(null) }
    var lightboxImageUrl by remember { mutableStateOf<String?>(null) }
    var lightboxCaption by remember { mutableStateOf<String?>(null) }

    var isSyncing by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var checkingAbbAsin by remember { mutableStateOf<String?>(null) }
    var showAboutSubpage by rememberSaveable { mutableStateOf(false) }

    // Ensure removed authors never leave a ghost filter active
    LaunchedEffect(authors, selectedAuthorFilter) {
        if (selectedAuthorFilter != null &&
            authors.none { it.name.equals(selectedAuthorFilter, ignoreCase = true) }
        ) {
            selectedAuthorFilter = null
        }
    }

    // Back button behavior for sub-filters / sub-screens
    BackHandler(
        enabled = showAboutSubpage ||
            selectedSeriesFilter != null ||
            selectedAuthorFilter != null ||
            activeTab != TrackerTab.RELEASES
    ) {
        when {
            showAboutSubpage -> showAboutSubpage = false
            selectedSeriesFilter != null -> selectedSeriesFilter = null
            selectedAuthorFilter != null -> selectedAuthorFilter = null
            activeTab != TrackerTab.RELEASES -> activeTab = TrackerTab.RELEASES
        }
    }

    val todayEpoch = remember { LocalDate.now(ZoneId.systemDefault()).toEpochDay() }
    val upcomingCount = remember(releases, todayEpoch) {
        releases.count { it.releaseEpochDay >= todayEpoch }
    }
    val abbReadyCount = remember(releases) {
        releases.count { it.abbAvailable }
    }

    Scaffold(
        containerColor = palette.bg,
        contentColor = palette.fg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.bg)
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Top Utility Bar (No app name or version in header per design spec)
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
                        text = "AUTHORS:${authors.size}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = palette.muted
                    )
                    Text(
                        text = "UPCOMING:$upcomingCount",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = palette.preorderBadge
                    )
                    if (abbReadyCount > 0) {
                        Text(
                            text = "ABB:$abbReadyCount",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = palette.outNowBadge
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = palette.accent
                        )
                    }
                    BracketLink(
                        label = if (isSyncing) "SYNCING..." else "SYNC NOW",
                        accentColor = palette.outNowBadge,
                        onClick = {
                            if (!isSyncing) {
                                scope.launch {
                                    isSyncing = true
                                    statusBanner = "Polling Audible & AudioBookBay mirrors..."
                                    val summary = withContext(Dispatchers.IO) {
                                        repo.syncAllAuthors(checkAbb = true)
                                    }
                                    isSyncing = false
                                    statusBanner = "Synced ${summary.authorsChecked} authors (${summary.totalReleases} books, ${summary.newlyAvailableOnAbb.size} new on ABB)"
                                }
                            }
                        }
                    )
                }
            }

            if (!statusBanner.isNullOrBlank()) {
                Text(
                    text = statusBanner!!,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.secondaryAccent,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
            }

            AsciiRule()

            // Primary Navigation Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TrackerTab.entries.forEach { tab ->
                    BracketLink(
                        label = tab.label,
                        active = activeTab == tab,
                        onClick = {
                            activeTab = tab
                            showAboutSubpage = false
                        }
                    )
                }
            }

            AsciiRule()

            // Active Tab Body
            when (activeTab) {
                TrackerTab.RELEASES -> {
                    ReleasesTabContent(
                        releases = releases,
                        todayEpoch = todayEpoch,
                        releaseFilter = releaseFilter,
                        onReleaseFilterChange = { releaseFilter = it },
                        selectedAuthorFilter = selectedAuthorFilter,
                        onClearAuthorFilter = { selectedAuthorFilter = null },
                        selectedSeriesFilter = selectedSeriesFilter,
                        onSelectSeriesFilter = { selectedSeriesFilter = it },
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        expandedAsin = expandedAsin,
                        onToggleExpand = { asin ->
                            expandedAsin = if (expandedAsin == asin) null else asin
                        },
                        checkingAbbAsin = checkingAbbAsin,
                        onTogglePin = { rel ->
                            scope.launch(Dispatchers.IO) {
                                repo.toggleReleasePin(rel.asin, rel.isPinned)
                            }
                        },
                        onToggleMute = { rel ->
                            scope.launch(Dispatchers.IO) {
                                repo.setBookAlertMute(rel.asin, !rel.muteBookAlerts)
                            }
                        },
                        onAutoCheckAbb = { rel ->
                            if (checkingAbbAsin == null) {
                                scope.launch {
                                    checkingAbbAsin = rel.asin
                                    val match = withContext(Dispatchers.IO) {
                                        repo.checkSingleReleaseOnAbb(rel)
                                    }
                                    checkingAbbAsin = null
                                    Toast.makeText(
                                        context,
                                        match.diagnosticMessage,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onOpenAbbBrowserSearch = { rel ->
                            scope.launch {
                                val url = withContext(Dispatchers.IO) {
                                    repo.buildAbbSearchUrl(rel.title, rel.authorName)
                                }
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }
                        },
                        onShowLightbox = { url, caption ->
                            lightboxImageUrl = url
                            lightboxCaption = caption
                        }
                    )
                }

                TrackerTab.AUTHORS -> {
                    AuthorsTabContent(
                        repo = repo,
                        authors = authors,
                        releases = releases,
                        onSelectAuthorReleases = { authorName ->
                            selectedAuthorFilter = authorName
                            selectedSeriesFilter = null
                            releaseFilter = ReleaseSubFilter.ALL
                            activeTab = TrackerTab.RELEASES
                        },
                        onShowLightbox = { url, caption ->
                            lightboxImageUrl = url
                            lightboxCaption = caption
                        },
                        onStatusUpdate = { statusBanner = it }
                    )
                }

                TrackerTab.CALENDAR -> {
                    CalendarTabContent(
                        releases = releases,
                        todayEpoch = todayEpoch,
                        onSelectRelease = { rel ->
                            selectedAuthorFilter = null
                            selectedSeriesFilter = null
                            releaseFilter = ReleaseSubFilter.ALL
                            expandedAsin = rel.asin
                            activeTab = TrackerTab.RELEASES
                        }
                    )
                }

                TrackerTab.SETTINGS -> {
                    if (showAboutSubpage) {
                        AboutSubscreen(onBack = { showAboutSubpage = false })
                    } else {
                        SettingsTabContent(
                            repo = repo,
                            syncLogs = syncLogs,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            onOpenAbout = { showAboutSubpage = true },
                            onStatusUpdate = { statusBanner = it }
                        )
                    }
                }
            }
        }
    }

    // Full-screen Cover / Author Portrait Lightbox Dialog
    if (!lightboxImageUrl.isNullOrBlank()) {
        Dialog(onDismissRequest = { lightboxImageUrl = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bg)
                    .border(2.dp, palette.accent)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RemoteThumbnail(
                    imageUrl = lightboxImageUrl,
                    fallbackInitials = "IMG",
                    size = 260.dp
                )
                if (!lightboxCaption.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = lightboxCaption!!,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = palette.fg
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                BracketLink(label = "CLOSE") {
                    lightboxImageUrl = null
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleasesTabContent(
    releases: List<ReleaseEntity>,
    todayEpoch: Long,
    releaseFilter: ReleaseSubFilter,
    onReleaseFilterChange: (ReleaseSubFilter) -> Unit,
    selectedAuthorFilter: String?,
    onClearAuthorFilter: () -> Unit,
    selectedSeriesFilter: String?,
    onSelectSeriesFilter: (String?) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    expandedAsin: String?,
    onToggleExpand: (String) -> Unit,
    checkingAbbAsin: String?,
    onTogglePin: (ReleaseEntity) -> Unit,
    onToggleMute: (ReleaseEntity) -> Unit,
    onAutoCheckAbb: (ReleaseEntity) -> Unit,
    onOpenAbbBrowserSearch: (ReleaseEntity) -> Unit,
    onShowLightbox: (String, String) -> Unit
) {
    val palette = LocalPtsColors.current
    val context = LocalContext.current

    // Sub-filter bar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ReleaseSubFilter.entries.forEach { sub ->
            BracketLink(
                label = sub.label,
                active = releaseFilter == sub,
                onClick = { onReleaseFilterChange(sub) }
            )
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // Active Author or Series drill-down chips
    if (selectedAuthorFilter != null || selectedSeriesFilter != null) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (selectedAuthorFilter != null) {
                BracketLink(
                    label = "AUTHOR: $selectedAuthorFilter ✕",
                    accentColor = palette.secondaryAccent,
                    onClick = onClearAuthorFilter
                )
            }
            if (selectedSeriesFilter != null) {
                BracketLink(
                    label = "SERIES: $selectedSeriesFilter ✕",
                    accentColor = palette.preorderBadge,
                    onClick = { onSelectSeriesFilter(null) }
                )
            }
        }
    }

    // Search box
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.border)
            .background(palette.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FILTER > ",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = palette.accent
        )
        BasicTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.fg
            ),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                if (searchQuery.isEmpty()) {
                    Text(
                        text = "title, author, series, narrator...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.muted
                    )
                }
                innerTextField()
            }
        )
        if (searchQuery.isNotEmpty()) {
            BracketLink(label = "CLR") { onSearchQueryChange("") }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    val filtered = remember(
        releases,
        releaseFilter,
        selectedAuthorFilter,
        selectedSeriesFilter,
        searchQuery,
        todayEpoch
    ) {
        val base = releases.filter { rel ->
            val matchAuthor = selectedAuthorFilter == null ||
                rel.authorName.equals(selectedAuthorFilter, ignoreCase = true)
            val matchSeries = selectedSeriesFilter == null ||
                rel.seriesTitle.equals(selectedSeriesFilter, ignoreCase = true)
            val matchQuery = searchQuery.isBlank() ||
                rel.title.contains(searchQuery, ignoreCase = true) ||
                rel.authorName.contains(searchQuery, ignoreCase = true) ||
                (rel.seriesTitle?.contains(searchQuery, ignoreCase = true) == true) ||
                rel.narratorName.contains(searchQuery, ignoreCase = true)

            val matchTab = when (releaseFilter) {
                ReleaseSubFilter.UPCOMING -> rel.releaseEpochDay >= todayEpoch
                ReleaseSubFilter.RECENT -> rel.releaseEpochDay in (todayEpoch - 90)..todayEpoch
                ReleaseSubFilter.PINNED -> rel.isPinned
                ReleaseSubFilter.ON_ABB -> rel.abbAvailable
                ReleaseSubFilter.ALL -> true
            }
            matchAuthor && matchSeries && matchQuery && matchTab
        }

        when {
            selectedSeriesFilter != null -> {
                base.sortedWith(
                    compareBy<ReleaseEntity> {
                        it.seriesPosition?.toDoubleOrNull() ?: Double.MAX_VALUE
                    }.thenBy { it.releaseEpochDay }
                )
            }
            releaseFilter == ReleaseSubFilter.UPCOMING -> {
                base.sortedBy { it.releaseEpochDay }
            }
            else -> {
                base.sortedByDescending { it.releaseEpochDay }
            }
        }
    }

    if (filtered.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = "No audiobooks match the current filter.
Add authors in [ AUTHORS ] or switch to [ ALL ].",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.muted
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.asin }) { rel ->
                ReleaseBoxscoreCard(
                    rel = rel,
                    todayEpoch = todayEpoch,
                    expanded = expandedAsin == rel.asin,
                    isCheckingAbb = checkingAbbAsin == rel.asin,
                    onToggleExpand = { onToggleExpand(rel.asin) },
                    onTogglePin = { onTogglePin(rel) },
                    onToggleMute = { onToggleMute(rel) },
                    onSelectSeries = { series -> onSelectSeriesFilter(series) },
                    onAutoCheckAbb = { onAutoCheckAbb(rel) },
                    onOpenAbbBrowserSearch = { onOpenAbbBrowserSearch(rel) },
                    onShowLightbox = onShowLightbox,
                    context = context
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseBoxscoreCard(
    rel: ReleaseEntity,
    todayEpoch: Long,
    expanded: Boolean,
    isCheckingAbb: Boolean,
    onToggleExpand: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onSelectSeries: (String) -> Unit,
    onAutoCheckAbb: () -> Unit,
    onOpenAbbBrowserSearch: () -> Unit,
    onShowLightbox: (String, String) -> Unit,
    context: Context
) {
    val palette = LocalPtsColors.current
    val deltaDays = rel.releaseEpochDay - todayEpoch

    val countdownLabel = when {
        deltaDays > 0L -> "IN ${deltaDays}D"
        deltaDays == 0L -> "OUT TODAY"
        else -> "${-deltaDays}D AGO"
    }
    val badgeColor = when {
        deltaDays > 0L -> palette.preorderBadge
        deltaDays == 0L -> palette.outNowBadge
        else -> palette.muted
    }

    val hours = rel.runtimeMinutes / 60
    val mins = rel.runtimeMinutes % 60
    val runtimeFormatted = if (rel.runtimeMinutes > 0) "${hours}h ${mins}m" else "TBA"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (rel.abbAvailable) palette.outNowBadge else palette.border)
            .background(palette.surface)
            .clickable { onToggleExpand() }
            .padding(10.dp)
    ) {
        // Header Row: Date | Status Badges | Pin Marker
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
                    color = palette.fg
                )
                StatusTag(label = countdownLabel, color = badgeColor)
                if (rel.abbAvailable) {
                    StatusTag(label = "ON ABB", color = palette.outNowBadge)
                }
                if (rel.muteBookAlerts) {
                    StatusTag(label = "MUTED", color = palette.warning)
                }
            }

            if (rel.isPinned) {
                Text(
                    text = "[★ PINNED]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = palette.warning
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Main Info Row: Cover Thumbnail + Title/Author/Series/Narrator
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            RemoteThumbnail(
                imageUrl = rel.imageUrl,
                fallbackInitials = rel.title,
                size = 56.dp,
                onClick = if (!rel.imageUrl.isNullOrBlank()) {
                    { onShowLightbox(rel.imageUrl, rel.title) }
                } else null
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rel.title,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = palette.fg
                )

                if (!rel.seriesTitle.isNullOrBlank()) {
                    val posStr = rel.seriesPosition?.let { " #$it" } ?: ""
                    Text(
                        text = "Series: ${rel.seriesTitle}$posStr [View Series]",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = palette.preorderBadge,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { onSelectSeries(rel.seriesTitle) }
                    )
                }

                Text(
                    text = "Author: ${rel.authorName}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.secondaryAccent,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Text(
                    text = "Narrator: ${rel.narratorName} • Len: $runtimeFormatted",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // Expanded Details Drawer
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 1.dp, color = palette.border)
            Spacer(modifier = Modifier.height(6.dp))

            if (!rel.summary.isNullOrBlank()) {
                Text(
                    text = rel.summary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.fg,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Text(
                text = "ASIN: ${rel.asin} • Marketplace: ${rel.marketplace.uppercase()}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = palette.muted,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BracketLink(
                    label = if (rel.isPinned) "UNPIN" else "PIN",
                    onClick = onTogglePin
                )
                BracketLink(
                    label = if (rel.muteBookAlerts) "UNMUTE ALERTS" else "MUTE BOOK",
                    onClick = onToggleMute
                )
                BracketLink(
                    label = "AUDIBLE",
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.productUrl)))
                        }
                    }
                )
                BracketLink(
                    label = if (isCheckingAbb) "CHECKING ABB..." else "CHECK ABB",
                    accentColor = palette.preorderBadge,
                    onClick = onAutoCheckAbb
                )
                BracketLink(
                    label = "ABB SEARCH",
                    onClick = onOpenAbbBrowserSearch
                )

                if (!rel.abbUrl.isNullOrBlank()) {
                    BracketLink(
                        label = "ABB POST",
                        accentColor = palette.outNowBadge,
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.abbUrl)))
                            }
                        }
                    )
                }

                if (!rel.abbMagnetUri.isNullOrBlank()) {
                    BracketLink(
                        label = "OPEN MAGNET",
                        accentColor = palette.outNowBadge,
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.abbMagnetUri)))
                            }.onFailure {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("ABB Magnet", rel.abbMagnetUri))
                                Toast.makeText(
                                    context,
                                    "No torrent app installed; Magnet URI copied to clipboard",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    BracketLink(
                        label = "COPY MAGNET",
                        accentColor = palette.secondaryAccent,
                        onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("ABB Magnet", rel.abbMagnetUri))
                            Toast.makeText(context, "Magnet URI copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorsTabContent(
    repo: ReleaseRepository,
    authors: List<AuthorEntity>,
    releases: List<ReleaseEntity>,
    onSelectAuthorReleases: (String) -> Unit,
    onShowLightbox: (String, String) -> Unit,
    onStatusUpdate: (String) -> Unit
) {
    val palette = LocalPtsColors.current
    val scope = rememberCoroutineScope()

    var authorInput by rememberSaveable { mutableStateOf("") }
    var selectedMarket by rememberSaveable {
        mutableStateOf(repo.getSetting("default_marketplace", "us"))
    }
    var suggestions by remember { mutableStateOf<List<AuthorCatalogSuggestion>>(emptyList()) }
    var isSearchingSuggestions by remember { mutableStateOf(false) }
    var isAddingAuthor by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        AsciiSectionHeader(
            title = "TRACK NEW AUDIBLE AUTHOR",
            rightLabel = "MKT: ${selectedMarket.uppercase()}"
        )

        // Marketplace Picker Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AudibleApiClient.MARKETPLACES.forEach { mkt ->
                BracketLink(
                    label = mkt.flagLabel,
                    active = selectedMarket == mkt.code,
                    onClick = { selectedMarket = mkt.code }
                )
            }
        }

        // Author Search Input Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, palette.border)
                .background(palette.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "AUTHOR > ",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = palette.accent
            )
            BasicTextField(
                value = authorInput,
                onValueChange = { newText ->
                    authorInput = newText
                    searchJob?.cancel()
                    if (newText.trim().length >= 2) {
                        searchJob = scope.launch {
                            delay(300L)
                            isSearchingSuggestions = true
                            suggestions = withContext(Dispatchers.IO) {
                                repo.searchCatalogAuthors(newText.trim(), selectedMarket)
                            }
                            isSearchingSuggestions = false
                        }
                    } else {
                        suggestions = emptyList()
                    }
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.fg
                ),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (authorInput.isEmpty()) {
                        Text(
                            text = "e.g. Shirtaloon, Matt Dinniman, Zogarth...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = palette.muted
                        )
                    }
                    inner()
                }
            )

            BracketLink(
                label = if (isAddingAuthor) "ADDING..." else "+ ADD",
                accentColor = palette.outNowBadge,
                onClick = {
                    val trimmed = authorInput.trim()
                    if (trimmed.isNotEmpty() && !isAddingAuthor) {
                        scope.launch {
                            isAddingAuthor = true
                            suggestions = emptyList()
                            onStatusUpdate("Fetching Audible catalog & portrait for $trimmed...")
                            val count = withContext(Dispatchers.IO) {
                                repo.addAuthorAndSync(trimmed, selectedMarket)
                            }
                            authorInput = ""
                            isAddingAuthor = false
                            onStatusUpdate("Added $trimmed ($count new titles synced)")
                        }
                    }
                }
            )
        }

        // Live Autocomplete Suggestions Dropdown
        if (isSearchingSuggestions) {
            Text(
                text = "Searching Audible & Audnexus author directory...",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else if (suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.accent)
                    .background(palette.surface)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "MATCHING AUTHORS (TAP TO TRACK):",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = palette.preorderBadge
                )
                suggestions.forEach { sug ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!isAddingAuthor) {
                                    scope.launch {
                                        isAddingAuthor = true
                                        suggestions = emptyList()
                                        authorInput = ""
                                        onStatusUpdate("Adding ${sug.name}...")
                                        val count = withContext(Dispatchers.IO) {
                                            repo.addAuthorAndSync(
                                                nameInput = sug.name,
                                                marketplaceCode = selectedMarket,
                                                explicitAsin = sug.asin,
                                                initialImageUrl = sug.imageUrl
                                            )
                                        }
                                        isAddingAuthor = false
                                        onStatusUpdate("Tracked ${sug.name} ($count books indexed)")
                                    }
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RemoteThumbnail(
                            imageUrl = sug.imageUrl,
                            fallbackInitials = sug.name,
                            size = 38.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sug.name,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = palette.fg
                            )
                            Text(
                                text = "Sample: ${sug.sampleBook}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = palette.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "[+ TRACK]",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = palette.outNowBadge
                        )
                    }
                }
            }
        }

        AsciiSectionHeader(
            title = "TRACKED AUTHORS (${authors.size})",
            rightLabel = "TAP AUTHOR TO FILTER BOOKS"
        )

        if (authors.isEmpty()) {
            Text(
                text = "No authors tracked yet. Search above to begin tracking.",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(authors, key = { it.id }) { author ->
                    val bookCount = releases.count {
                        it.authorName.equals(author.name, ignoreCase = true)
                    }
                    LaunchedEffect(author.id, author.imageUrl) {
                        if (author.imageUrl.isNullOrBlank()) {
                            withContext(Dispatchers.IO) {
                                repo.refreshAuthorPortrait(author)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, palette.border)
                            .background(palette.surface)
                            .clickable { onSelectAuthorReleases(author.name) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RemoteThumbnail(
                            imageUrl = author.imageUrl,
                            fallbackInitials = author.name,
                            size = 52.dp,
                            onClick = if (!author.imageUrl.isNullOrBlank()) {
                                { onShowLightbox(author.imageUrl, author.name) }
                            } else null
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = author.name,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = palette.fg
                            )
                            Text(
                                text = "Books: $bookCount • Mkt: ${author.marketplace.uppercase()}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = palette.preorderBadge
                            )
                            if (!author.bio.isNullOrBlank()) {
                                Text(
                                    text = author.bio,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = palette.muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BracketLink(label = "SYNC") {
                                scope.launch {
                                    onStatusUpdate("Syncing ${author.name}...")
                                    withContext(Dispatchers.IO) {
                                        repo.syncSingleAuthor(author)
                                    }
                                    onStatusUpdate("Synced ${author.name}")
                                }
                            }
                            BracketLink(
                                label = "DEL",
                                accentColor = palette.urgent
                            ) {
                                scope.launch(Dispatchers.IO) {
                                    repo.removeAuthor(author)
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
private fun CalendarTabContent(
    releases: List<ReleaseEntity>,
    todayEpoch: Long,
    onSelectRelease: (ReleaseEntity) -> Unit
) {
    val palette = LocalPtsColors.current

    val upcomingGrouped = remember(releases, todayEpoch) {
        releases
            .filter { it.releaseEpochDay >= todayEpoch - 7 }
            .sortedBy { it.releaseEpochDay }
            .groupBy { it.releaseDateIso.take(7) } // YYYY-MM
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AsciiSectionHeader(
            title = "RELEASE SCHEDULE MATRIX",
            rightLabel = "CHRONOLOGICAL"
        )

        if (upcomingGrouped.isEmpty()) {
            Text(
                text = "No upcoming releases scheduled.",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                upcomingGrouped.forEach { (yearMonth, monthItems) ->
                    item(key = "hdr_$yearMonth") {
                        AsciiSectionHeader(
                            title = yearMonth,
                            rightLabel = "${monthItems.size} BOOK(S)"
                        )
                    }
                    items(monthItems, key = { "cal_${it.asin}" }) { rel ->
                        val delta = ChronoUnit.DAYS.between(
                            LocalDate.ofEpochDay(todayEpoch),
                            LocalDate.ofEpochDay(rel.releaseEpochDay)
                        )
                        val tag = when {
                            delta > 0 -> "T-${delta}D"
                            delta == 0L -> "TODAY"
                            else -> "${-delta}D AGO"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, palette.border)
                                .background(palette.surface)
                                .clickable { onSelectRelease(rel) }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${rel.releaseDateIso} [$tag] ${rel.title}",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = palette.fg
                                )
                                Text(
                                    text = "by ${rel.authorName}" +
                                        (rel.seriesTitle?.let { " • $it #${rel.seriesPosition ?: ""}" } ?: ""),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = palette.muted
                                )
                            }
                            if (rel.abbAvailable) {
                                StatusTag(label = "ABB", color = palette.outNowBadge)
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
private fun SettingsTabContent(
    repo: ReleaseRepository,
    syncLogs: List<com.plaintext.audible.data.SyncLogEntity>,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    onOpenAbout: () -> Unit,
    onStatusUpdate: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalPtsColors.current
    val scope = rememberCoroutineScope()

    var intervalHours by remember {
        mutableIntStateOf(repo.getSetting("sync_interval_hours", "6").toIntOrNull() ?: 6)
    }
    var wifiOnly by remember {
        mutableStateOf(repo.getSetting("sync_wifi_only", "false") == "true")
    }
    var notifyPreorders by remember {
        mutableStateOf(repo.getSetting("notify_preorders", "true") == "true")
    }
    var notifyReleaseDay by remember {
        mutableStateOf(repo.getSetting("notify_release_day", "true") == "true")
    }
    var notifyAbb by remember {
        mutableStateOf(repo.getSetting("notify_abb", "true") == "true")
    }
    var abbEnabled by remember {
        mutableStateOf(repo.getSetting("abb_check_enabled", "true") == "true")
    }
    var abbMirrorsInput by remember {
        mutableStateOf(
            repo.getSetting(
                "abb_mirrors",
                AudibleApiClient.DEFAULT_ABB_MIRRORS.joinToString(",")
            )
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    val json = BackupManager.exportBackupJson(
                        AppDatabase.getInstance(context)
                    )
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    json.length
                }
                onStatusUpdate("Exported backup JSON ($count bytes)")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val restored = withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: return@withContext 0
                    BackupManager.importBackupJson(
                        AppDatabase.getInstance(context),
                        raw
                    )
                }
                onStatusUpdate("Restored $restored authors from backup")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            AsciiSectionHeader(title = "THEME PALETTE (DRACULA / ALUCARD)")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "system" to "AUTO SYSTEM",
                    "dark" to "DRACULA DARK",
                    "light" to "ALUCARD LIGHT"
                ).forEach { (code, label) ->
                    BracketLink(
                        label = label,
                        active = themeMode == code,
                        onClick = { onThemeModeChange(code) }
                    )
                }
            }
        }

        item {
            AsciiSectionHeader(title = "AUDIOBOOKBAY (ABB) MONITOR")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Check AudioBookBay during sync",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = palette.fg
                )
                BracketLink(
                    label = if (abbEnabled) "ENABLED" else "DISABLED",
                    active = abbEnabled,
                    onClick = {
                        abbEnabled = !abbEnabled
                        repo.setSetting("abb_check_enabled", abbEnabled.toString())
                    }
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ABB Mirrors (comma-separated):",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = palette.muted
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.border)
                    .background(palette.surface)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = abbMirrorsInput,
                    onValueChange = { abbMirrorsInput = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = palette.fg
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier.weight(1f)
                )
                BracketLink(label = "SAVE") {
                    repo.setSetting("abb_mirrors", abbMirrorsInput.trim())
                    Toast.makeText(context, "Saved ABB mirrors", Toast.LENGTH_SHORT).show()
                }
            }
        }

        item {
            AsciiSectionHeader(title = "BACKGROUND WORKMANAGER SCHEDULE")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(2, 6, 12, 24).forEach { hrs ->
                    BracketLink(
                        label = "EVERY ${hrs}H",
                        active = intervalHours == hrs,
                        onClick = {
                            intervalHours = hrs
                            repo.setSetting("sync_interval_hours", hrs.toString())
                            ReleaseCheckWorker.schedulePeriodicSync(context, hrs.toLong(), wifiOnly)
                        }
                    )
                }
                BracketLink(
                    label = if (wifiOnly) "WIFI ONLY: ON" else "WIFI ONLY: OFF",
                    active = wifiOnly,
                    onClick = {
                        wifiOnly = !wifiOnly
                        repo.setSetting("sync_wifi_only", wifiOnly.toString())
                        ReleaseCheckWorker.schedulePeriodicSync(context, intervalHours.toLong(), wifiOnly)
                    }
                )
            }
        }

        item {
            AsciiSectionHeader(title = "NOTIFICATION CHANNELS")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("New Pre-Order Announcements", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    BracketLink(
                        label = if (notifyPreorders) "ON" else "OFF",
                        active = notifyPreorders,
                        onClick = {
                            notifyPreorders = !notifyPreorders
                            repo.setSetting("notify_preorders", notifyPreorders.toString())
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Release Day Alerts", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    BracketLink(
                        label = if (notifyReleaseDay) "ON" else "OFF",
                        active = notifyReleaseDay,
                        onClick = {
                            notifyReleaseDay = !notifyReleaseDay
                            repo.setSetting("notify_release_day", notifyReleaseDay.toString())
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("AudioBookBay Availability Alerts", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    BracketLink(
                        label = if (notifyAbb) "ON" else "OFF",
                        active = notifyAbb,
                        onClick = {
                            notifyAbb = !notifyAbb
                            repo.setSetting("notify_abb", notifyAbb.toString())
                        }
                    )
                }
            }
        }

        item {
            AsciiSectionHeader(title = "DATABASE BACKUP & RESTORE")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BracketLink(label = "EXPORT JSON BACKUP") {
                    exportLauncher.launch("nocturnetxt-backup.json")
                }
                BracketLink(label = "IMPORT JSON BACKUP") {
                    importLauncher.launch(arrayOf("application/json", "text/*"))
                }
            }
        }

        item {
            AsciiSectionHeader(title = "APPLICATION METADATA")
            BracketLink(
                label = "ABOUT ->",
                accentColor = palette.preorderBadge,
                onClick = onOpenAbout
            )
        }

        item {
            AsciiSectionHeader(title = "RECENT SYNC TELEMETRY (${syncLogs.size})")
        }

        items(syncLogs) { log ->
            val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
            val ts = fmt.format(Date(log.timestampEpochMillis))
            Text(
                text = "[$ts] ${log.statusMessage} (New alerts: ${log.newAlertsCount})",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = palette.muted
            )
        }
    }
}

@Composable
private fun AboutSubscreen(onBack: () -> Unit) {
    val palette = LocalPtsColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, palette.border)
            .background(palette.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BracketLink(label = "<- BACK TO SETTINGS", onClick = onBack)

        AsciiRule()

        Text(
            text = "NocturneTXT",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = palette.accent
        )

        Text(
            text = "Version 3.3.0 (Build 23)",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = palette.preorderBadge
        )

        Text(
            text = "Package: com.plaintext.audible",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = palette.muted
        )

        AsciiRule()

        Text(
            text = "Design & Architecture:
" +
                "• Inspired by Plain Text Sports (plaintextsports.com)
" +
                "• Official Dracula Dark & Alucard Light color specifications (draculatheme.com/spec)
" +
                "• Public Audible Catalog API + Audnexus Author Directory
" +
                "• AudioBookBay availability verification with WebView Cloudflare fallback & BitTorrent magnet synthesis",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = palette.fg
        )
    }
}
