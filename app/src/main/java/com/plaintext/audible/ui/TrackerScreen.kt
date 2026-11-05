package com.plaintext.audible.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.plaintext.audible.data.*
import com.plaintext.audible.worker.ReleaseCheckWorker
import kotlinx.coroutines.delay
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

enum class TrackedSortOrder(val label: String) {
    A_TO_Z("A-Z"),
    MOST_UPCOMING("UPCOMING")
}

@Immutable
data class ReleaseCardUiModel(
    val entity: ReleaseEntity,
    val upperTitle: String,
    val headerLabel: String,
    val badgeTone: Int,
    val isFutureOrTba: Boolean,
    val seriesLine: String,
    val sameAuthorAndNarrator: Boolean,
    val detailsMetaLine: String,
    val matchedSeriesItem: TrackedItemEntity?,
    val matchedStandaloneBookItem: TrackedItemEntity?,
    val hasSeriesGap: Boolean,
    val unreadGapBookNum: Int,
    val canMarkCurrentRead: Boolean,
    val primaryAuthor: String,
    val isSeriesAlreadyTracked: Boolean,
    val isPrimaryAuthorAlreadyTracked: Boolean
)

@Immutable
data class MonthGroupUiModel(
    val monthHeader: String,
    val cards: List<ReleaseCardUiModel>
)

@Composable
fun TrackerScreen(
    repo: ReleaseRepository,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var prefs by remember { mutableStateOf(repo.getPrefs()) }
    var syncTelemetry by remember { mutableStateOf(repo.getSyncTelemetry()) }
    val systemDark = isSystemInDarkTheme()
    val useDarkPalette = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = if (useDarkPalette) DraculaPalette else AlucardPalette

    val releases by repo.dao.observeReleases().collectAsState(initial = emptyList())
    val trackedItems by repo.dao.observeTrackedItems().collectAsState(initial = emptyList())
    val dndRules by repo.dao.observeDndRules().collectAsState(initial = emptyList())

    var activeTab by remember { mutableStateOf(ActiveTab.UPCOMING) }
    var lastNonHomeTab by remember { mutableStateOf<ActiveTab?>(null) }
    var activeFilter by remember { mutableStateOf(QuickFilter.ALL) }
    var trackedSourceFilter by remember { mutableStateOf(TrackedSourceFilter.ALL) }
    var trackedSortOrder by remember { mutableStateOf(TrackedSortOrder.A_TO_Z) }
    var focusedSourceQuery by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }
    var showTbaExpanded by remember { mutableStateOf(false) }
    var showAboutPage by remember { mutableStateOf(false) }
    var recentlyDeletedItem by remember { mutableStateOf<TrackedItemEntity?>(null) }

    LaunchedEffect(recentlyDeletedItem) {
        if (recentlyDeletedItem != null) {
            delay(5000L)
            recentlyDeletedItem = null
        }
    }

    val navigateToTab: (ActiveTab) -> Unit = { targetTab ->
        if (targetTab != activeTab) {
            if (targetTab == ActiveTab.UPCOMING) {
                lastNonHomeTab = null
            } else if (activeTab != ActiveTab.UPCOMING) {
                lastNonHomeTab = activeTab
            }
            if (targetTab != ActiveTab.SETTINGS) {
                showAboutPage = false
            }
            activeTab = targetTab
        } else if (targetTab == ActiveTab.SETTINGS && showAboutPage) {
            showAboutPage = false
        }
    }

    val shouldInterceptBack = showAboutPage ||
        focusedSourceQuery != null ||
        activeTab != ActiveTab.UPCOMING ||
        activeFilter != QuickFilter.ALL

    BackHandler(enabled = shouldInterceptBack) {
        when {
            showAboutPage -> {
                showAboutPage = false
            }
            focusedSourceQuery != null -> {
                focusedSourceQuery = null
                activeTab = ActiveTab.TRACKED
            }
            activeTab != ActiveTab.UPCOMING -> {
                val prev = lastNonHomeTab
                if (prev != null && prev != activeTab) {
                    lastNonHomeTab = null
                    activeTab = prev
                } else {
                    lastNonHomeTab = null
                    activeTab = ActiveTab.UPCOMING
                }
            }
            activeFilter != QuickFilter.ALL -> {
                activeFilter = QuickFilter.ALL
            }
        }
    }

    val today = remember { LocalDate.now() }
    val todayIso = remember { today.toString() }

    LaunchedEffect(Unit) {
        repo.syncCatalog(forceRefresh = false)
        syncTelemetry = repo.getSyncTelemetry()
    }

    val isBottomBarVisible = remember { mutableStateOf(true) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -8f && isBottomBarVisible.value) {
                    isBottomBarVisible.value = false
                } else if (available.y > 8f && !isBottomBarVisible.value) {
                    isBottomBarVisible.value = true
                }
                return Offset.Zero
            }
        }
    }

    val handleAddTracked: (TrackType, String, Int, String, String?) -> Unit = remember(repo, context) {
        { type, query, lastRead, initialThumb, externalId ->
            scope.launch {
                val cleaned = query.trim()
                val added = repo.addTrackedItemUnique(type, cleaned, lastRead, initialThumb, externalId)
                if (added) {
                    Toast.makeText(context, "[ + Tracked ${type.name}: $cleaned ]", Toast.LENGTH_SHORT).show()
                    isSyncing = true
                    repo.syncCatalog(forceRefresh = false)
                    syncTelemetry = repo.getSyncTelemetry()
                    isSyncing = false
                } else {
                    Toast.makeText(context, "[ Already Tracked: $cleaned ]", Toast.LENGTH_SHORT).show()
                }
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
                // Item #10: Context-aware top bar ([ SYNC ] shown only on UPCOMING and TRACKED)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = if (activeTab == ActiveTab.SETTINGS && showAboutPage) "[ ABOUT ]" else "[ ${activeTab.name} ]",
                        color = palette.comment,
                        fontSizeSp = 13,
                        bold = true
                    )
                    if (activeTab != ActiveTab.SETTINGS) {
                        BracketButton(
                            label = if (isSyncing) "SYNCING..." else "SYNC",
                            color = palette.green,
                            onClick = {
                                if (!isSyncing) {
                                    scope.launch {
                                        isSyncing = true
                                        repo.syncCatalog(forceRefresh = true)
                                        syncTelemetry = repo.getSyncTelemetry()
                                        isSyncing = false
                                    }
                                }
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
                            trackedItems = trackedItems,
                            dndRules = dndRules,
                            quickFilter = activeFilter,
                            focusedSourceQuery = focusedSourceQuery,
                            onClearFocusedSource = { focusedSourceQuery = null },
                            alwaysShowCardActions = prefs.alwaysShowCardActions,
                            showTbaExpanded = showTbaExpanded,
                            onToggleTba = { showTbaExpanded = !showTbaExpanded },
                            onClearNewBadges = { scope.launch { repo.dao.clearNewDiscoveryFlags() } },
                            onOpenAudibleSearch = { card ->
                                val domain = prefs.marketplace.domain
                                val q = URLEncoder.encode("${card.entity.title} ${card.primaryAuthor}".trim(), "UTF-8")
                                val url = "https://www.$domain/search?keywords=$q&ipRedirectOverride=true&overrideBaseCountry=true"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            onOpenAudioBookBay = { card ->
                                val directUrl = card.entity.abbPostUrl
                                val abbUrl = if (directUrl.startsWith("https://")) {
                                    directUrl
                                } else {
                                    val q = URLEncoder.encode("${card.entity.title} ${card.primaryAuthor}".trim().lowercase(), "UTF-8")
                                    "https://audiobookbay.lu/?s=$q&cat=undefined%2Cundefined"
                                }
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(abbUrl)))
                            },
                            onOpenOrCopyMagnet = { card ->
                                val magnet = card.entity.abbMagnetUri
                                if (magnet.startsWith("magnet:")) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("ABB Magnet URI", magnet))
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(magnet)))
                                    }.onFailure {
                                        Toast.makeText(context, "[ Magnet URI Copied to Clipboard ]", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onCopyRelease = { card ->
                                val rel = card.entity
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val seriesPart = if (rel.seriesTitle.isNotBlank()) {
                                    " (${rel.seriesTitle}${rel.seriesSequence?.let { " #$it" }.orEmpty()})"
                                } else ""
                                val summary = "${rel.title}$seriesPart — By ${rel.authors} • Narrated by ${rel.narrators} • Release: ${rel.releaseDateIso}"
                                clipboard.setPrimaryClip(ClipData.newPlainText("Audiobook Release", summary))
                                Toast.makeText(context, "[ Copied to Clipboard ]", Toast.LENGTH_SHORT).show()
                            },
                            onExportCalendar = { card ->
                                val rel = card.entity
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
                            onQuickTrack = { type, value, thumbUrl, extId ->
                                handleAddTracked(type, value, 0, thumbUrl, extId)
                            },
                            onArchiveTrackedBook = { trackedBook ->
                                scope.launch {
                                    recentlyDeletedItem = trackedBook
                                    repo.dao.deleteTrackedItem(trackedBook)
                                    repo.syncCatalog(forceRefresh = false)
                                    Toast.makeText(context, "[ Archived Book: ${trackedBook.queryValue} ]", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onMuteBook = { card ->
                                scope.launch { repo.muteBook(card.entity.asin, card.entity.title) }
                            },
                            onMuteSeries = { card ->
                                scope.launch { repo.muteSeries(card.entity.seriesTitle, card.entity.seriesTitle) }
                            },
                            onUnmuteRule = { rule ->
                                scope.launch {
                                    repo.dao.deleteDndRule(rule)
                                    repo.syncCatalog(forceRefresh = false)
                                    syncTelemetry = repo.getSyncTelemetry()
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
                            sourceFilter = trackedSourceFilter,
                            sortOrder = trackedSortOrder,
                            recentlyDeletedItem = recentlyDeletedItem,
                            onAddTracked = handleAddTracked,
                            onSelectSourceForFilter = { item ->
                                focusedSourceQuery = item.queryValue
                                activeFilter = QuickFilter.ALL
                                lastNonHomeTab = ActiveTab.TRACKED
                                activeTab = ActiveTab.UPCOMING
                            },
                            onDeleteTracked = { item ->
                                scope.launch {
                                    recentlyDeletedItem = item
                                    repo.dao.deleteTrackedItem(item)
                                    repo.syncCatalog(forceRefresh = false)
                                    syncTelemetry = repo.getSyncTelemetry()
                                }
                            },
                            onUndoDelete = { restored ->
                                scope.launch {
                                    recentlyDeletedItem = null
                                    repo.dao.insertTrackedItem(restored)
                                    repo.syncCatalog(forceRefresh = false)
                                    syncTelemetry = repo.getSyncTelemetry()
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
                                    syncTelemetry = syncTelemetry,
                                    onUpdatePrefs = { updated ->
                                        prefs = updated
                                        repo.savePrefs(updated)
                                        ReleaseCheckWorker.scheduleDailyCheck(
                                            context,
                                            updated.notifyHour,
                                            updated.notifyMinute
                                        )
                                        scope.launch {
                                            repo.syncCatalog(forceRefresh = false)
                                            syncTelemetry = repo.getSyncTelemetry()
                                        }
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

            BottomThumbBar(
                visibleState = isBottomBarVisible,
                activeTab = activeTab,
                activeFilter = activeFilter,
                trackedSourceFilter = trackedSourceFilter,
                trackedSortOrder = trackedSortOrder,
                upcomingCount = upcomingCount,
                trackedCount = trackedItems.size,
                dndCount = dndRules.size,
                onSelectFilter = {
                    focusedSourceQuery = null
                    activeFilter = it
                },
                onSelectTrackedFilter = { trackedSourceFilter = it },
                onSelectTrackedSort = { trackedSortOrder = it },
                onSelectTab = navigateToTab,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun BottomThumbBar(
    visibleState: State<Boolean>,
    activeTab: ActiveTab,
    activeFilter: QuickFilter,
    trackedSourceFilter: TrackedSourceFilter,
    trackedSortOrder: TrackedSortOrder,
    upcomingCount: Int,
    trackedCount: Int,
    dndCount: Int,
    onSelectFilter: (QuickFilter) -> Unit,
    onSelectTrackedFilter: (TrackedSourceFilter) -> Unit,
    onSelectTrackedSort: (TrackedSortOrder) -> Unit,
    onSelectTab: (ActiveTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    val density = LocalDensity.current
    val maxOffsetPx = remember(density) { with(density) { 110.dp.toPx() } }

    val translationY by animateFloatAsState(
        targetValue = if (visibleState.value) 0f else maxOffsetPx,
        animationSpec = tween(durationMillis = 180),
        label = "bottomBarSlide"
    )

    Surface(
        color = palette.background,
        border = BorderStroke(1.dp, palette.surfaceLine),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.translationY = translationY
            }
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
                            "DND ($dndCount)"
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
                            onClick = { onSelectFilter(filter) }
                        )
                    }
                }
                HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
            } else if (activeTab == ActiveTab.TRACKED) {
                // Item #5: Docked single-line filter & sort strip on TRACKED matching UPCOMING
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(text = "FLT:", color = palette.comment, fontSizeSp = 11)
                    TrackedSourceFilter.values().forEach { tf ->
                        BracketButton(
                            label = tf.label,
                            active = trackedSourceFilter == tf,
                            color = palette.purple,
                            onClick = { onSelectTrackedFilter(tf) }
                        )
                    }
                    MonoText(text = "| SRT:", color = palette.comment, fontSizeSp = 11)
                    TrackedSortOrder.values().forEach { so ->
                        BracketButton(
                            label = so.label,
                            active = trackedSortOrder == so,
                            color = palette.cyan,
                            onClick = { onSelectTrackedSort(so) }
                        )
                    }
                }
                HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
            }

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
                    onClick = { onSelectTab(ActiveTab.UPCOMING) }
                )
                BracketButton(
                    label = "TRACKED ($trackedCount)",
                    active = activeTab == ActiveTab.TRACKED,
                    onClick = { onSelectTab(ActiveTab.TRACKED) }
                )
                BracketButton(
                    label = "SETTINGS",
                    active = activeTab == ActiveTab.SETTINGS,
                    onClick = { onSelectTab(ActiveTab.SETTINGS) }
                )
            }
        }
    }
}

private val marketingSubtitleRegex = Regex(
    ":\\s*(?:a\\s+)?(?:litrpg|progression\\s+fantasy|cultivation|isekai|sci-fi|fantasy)\\s+(?:adventure|epic|novel|series|saga|story|tale).*$",
    RegexOption.IGNORE_CASE
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpcomingFeedView(
    releases: List<ReleaseEntity>,
    trackedItems: List<TrackedItemEntity>,
    dndRules: List<DndRuleEntity>,
    quickFilter: QuickFilter,
    focusedSourceQuery: String?,
    onClearFocusedSource: () -> Unit,
    alwaysShowCardActions: Boolean,
    showTbaExpanded: Boolean,
    onToggleTba: () -> Unit,
    onClearNewBadges: () -> Unit,
    onOpenAudibleSearch: (ReleaseCardUiModel) -> Unit,
    onOpenAudioBookBay: (ReleaseCardUiModel) -> Unit,
    onOpenOrCopyMagnet: (ReleaseCardUiModel) -> Unit,
    onCopyRelease: (ReleaseCardUiModel) -> Unit,
    onExportCalendar: (ReleaseCardUiModel) -> Unit,
    onQuickTrack: (TrackType, String, String, String?) -> Unit,
    onArchiveTrackedBook: (TrackedItemEntity) -> Unit,
    onMuteBook: (ReleaseCardUiModel) -> Unit,
    onMuteSeries: (ReleaseCardUiModel) -> Unit,
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
            item(key = "dnd_info", contentType = "info") {
                AsciiSectionHeader(
                    title = "DO NOT DISTURB (${dndRules.size} MUTED)",
                    color = palette.red
                )
            }
            if (dndRules.isEmpty()) {
                item(key = "dnd_empty", contentType = "empty") {
                    AsciiBox(headerLabel = "DND FILTER EMPTY") {
                        MonoText(text = "No muted books or series.", color = palette.comment)
                    }
                }
            }
            items(
                items = dndRules,
                key = { "dnd_${it.targetKey}" },
                contentType = { "dnd_card" }
            ) { rule ->
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
                            text = "${rule.displayTitle.uppercase()} (${rule.targetKey})",
                            fontSizeSp = 13,
                            bold = true,
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

    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US) }

    val (monthGroups, tbaCards, newCount) = remember(
        releases,
        trackedItems,
        quickFilter,
        focusedSourceQuery,
        todayIso
    ) {
        val seriesList = trackedItems.filter { it.type == TrackType.SERIES }
        val authorList = trackedItems.filter { it.type == TrackType.AUTHOR }
        val bookList = trackedItems.filter { it.type == TrackType.BOOK }

        fun toUiModel(rel: ReleaseEntity): ReleaseCardUiModel {
            val parsedDate = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
            val daysUntil = parsedDate?.let { ChronoUnit.DAYS.between(today, it) }

            val isFutureOrTba = daysUntil == null || daysUntil >= 0L
            val countdownBadge = when {
                daysUntil == null -> "[ TBA ]"
                daysUntil == 0L -> "[ OUT TODAY ]"
                daysUntil < 0L -> "[ ${-daysUntil}d AGO ]"
                else -> "[ T-${daysUntil}d ]"
            }
            // Item #6: Consolidate status tag ([ ABB ] or [ NEW ]) once into the ASCII frame header
            val statusHeaderSuffix = when {
                rel.abbAvailable -> " • [ ABB ]"
                rel.isNewDiscovery && isFutureOrTba -> " • [ NEW ]"
                else -> ""
            }
            val badgeTone = when {
                rel.abbAvailable || daysUntil == 0L -> 0
                daysUntil != null && daysUntil < 0L -> 2
                daysUntil != null && daysUntil in 1..7 -> 1
                else -> 3
            }

            val matchedSeries = if (rel.seriesTitle.isNotBlank()) {
                seriesList.firstOrNull { s ->
                    (s.pinnedAsin.isNotBlank() && s.pinnedAsin.equals(rel.seriesAsin, ignoreCase = true)) ||
                        rel.seriesTitle.equals(s.queryValue, ignoreCase = true) ||
                        (s.pinnedAsin.isBlank() && rel.seriesTitle.contains(s.queryValue, ignoreCase = true))
                }
            } else null

            val matchedBookItem = bookList.firstOrNull { b ->
                b.pinnedAsin.equals(rel.asin, ignoreCase = true) ||
                    b.queryValue.equals(rel.asin, ignoreCase = true) ||
                    b.queryValue.equals(rel.title, ignoreCase = true)
            }

            val primaryAuthor = rel.authors.split(",").firstOrNull()?.trim().orEmpty()
            val isAuthorTracked = primaryAuthor.isNotBlank() && authorList.any { a ->
                a.queryValue.equals(primaryAuthor, ignoreCase = true)
            }

            val seq = rel.seriesSequence
            val hasGap = matchedSeries != null && seq != null && seq > 1 && matchedSeries.lastReadSequence < (seq - 1)
            val canMarkRead = matchedSeries != null && seq != null && daysUntil != null && daysUntil <= 0L && matchedSeries.lastReadSequence < seq

            val hours = rel.runtimeMinutes / 60
            val mins = rel.runtimeMinutes % 60
            val runtimeStr = if (rel.runtimeMinutes > 0) "${hours}h ${mins}m" else "TBA"
            val pubStr = rel.publisher.ifBlank { "TBA" }
            val langSuffix = if (rel.language.equals("English", ignoreCase = true)) "" else "  |  Lang: ${rel.language}"
            // Item #8: Include Match: [...] inside detailsMetaLine (shown when expanded)
            val matchSuffix = "  |  Match: [${rel.matchedTags.replace(",", "] [")}]"

            val cleanedTitle = rel.title.replace(marketingSubtitleRegex, "").trim().ifBlank { rel.title }
            val sameAuthorAndNarrator = rel.authors.trim().equals(rel.narrators.trim(), ignoreCase = true)

            return ReleaseCardUiModel(
                entity = rel,
                upperTitle = cleanedTitle.uppercase(),
                headerLabel = "${rel.releaseDateIso} $countdownBadge$statusHeaderSuffix",
                badgeTone = badgeTone,
                isFutureOrTba = isFutureOrTba,
                seriesLine = if (rel.seriesTitle.isNotBlank()) {
                    "Series: ${rel.seriesTitle}${seq?.let { " #$it" }.orEmpty()}"
                } else "",
                sameAuthorAndNarrator = sameAuthorAndNarrator,
                detailsMetaLine = "Runtime: $runtimeStr  |  Publisher: $pubStr$langSuffix$matchSuffix",
                matchedSeriesItem = matchedSeries,
                matchedStandaloneBookItem = matchedBookItem,
                hasSeriesGap = hasGap,
                unreadGapBookNum = (seq ?: 1) - 1,
                canMarkCurrentRead = canMarkRead,
                primaryAuthor = primaryAuthor,
                isSeriesAlreadyTracked = matchedSeries != null,
                isPrimaryAuthorAlreadyTracked = isAuthorTracked
            )
        }

        val filtered = releases.filter { rel ->
            val focus = focusedSourceQuery?.trim()
            if (!focus.isNullOrBlank()) {
                val matchesFocus = rel.seriesTitle.contains(focus, ignoreCase = true) ||
                    rel.authors.contains(focus, ignoreCase = true) ||
                    rel.narrators.contains(focus, ignoreCase = true) ||
                    rel.title.contains(focus, ignoreCase = true) ||
                    rel.asin.equals(focus, ignoreCase = true)
                if (!matchesFocus) return@filter false
            }

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

        val scheduledRaw = filtered.filter { it.releaseDateIso != "TBA" }
        val sortedScheduled = if (quickFilter == QuickFilter.RECENT_30D) {
            scheduledRaw.sortedByDescending { it.releaseDateIso }
        } else {
            scheduledRaw.sortedBy { it.releaseDateIso }
        }

        val groups = sortedScheduled
            .map(::toUiModel)
            .groupBy { card ->
                runCatching {
                    val ym = YearMonth.from(LocalDate.parse(card.entity.releaseDateIso))
                    ym.format(monthFormatter).uppercase()
                }.getOrDefault("SCHEDULED")
            }
            .map { (monthName, list) ->
                val noun = if (list.size == 1) "RELEASE" else "RELEASES"
                MonthGroupUiModel(
                    monthHeader = "$monthName (${list.size} $noun)",
                    cards = list
                )
            }

        val tbas = if (quickFilter == QuickFilter.RECENT_30D) {
            emptyList()
        } else {
            filtered.filter { it.releaseDateIso == "TBA" }.map(::toUiModel)
        }

        val newlyDiscovered = releases.count { it.isNewDiscovery || it.narratorJustAnnounced }
        Triple(groups, tbas, newlyDiscovered)
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (!focusedSourceQuery.isNullOrBlank()) {
            item(key = "focused_source_banner", contentType = "banner") {
                AsciiBox(
                    headerLabel = "FILTERED BY FOLLOWED SOURCE",
                    borderColor = palette.cyan,
                    headerColor = palette.cyan
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = "Showing releases for: $focusedSourceQuery",
                            color = palette.cyan,
                            fontSizeSp = 12,
                            bold = true,
                            modifier = Modifier.weight(1f)
                        )
                        BracketButton(
                            label = "SHOW ALL",
                            color = palette.green,
                            onClick = onClearFocusedSource
                        )
                    }
                }
            }
        }

        if (newCount > 0 && quickFilter != QuickFilter.RECENT_30D && focusedSourceQuery.isNullOrBlank()) {
            item(key = "new_discovery_banner", contentType = "banner") {
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
                            fontSizeSp = 12,
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

        if (monthGroups.isEmpty()) {
            item(key = "empty_scoreboard", contentType = "empty") {
                AsciiBox(headerLabel = "SCOREBOARD EMPTY") {
                    MonoText(
                        text = if (!focusedSourceQuery.isNullOrBlank()) {
                            "No scheduled upcoming releases found for '$focusedSourceQuery'."
                        } else if (quickFilter == QuickFilter.RECENT_30D) {
                            "No releases from the past 30 days found for your followed sources."
                        } else {
                            "No scheduled upcoming releases match [${quickFilter.label}].\nTap [ TRACKED ] on the bottom bar to search the live catalog."
                        },
                        color = palette.comment,
                        fontSizeSp = 12
                    )
                }
            }
        }

        monthGroups.forEach { group ->
            stickyHeader(key = "hdr_${group.monthHeader}") {
                AsciiSectionHeader(title = group.monthHeader, color = palette.purple)
            }
            items(
                items = group.cards,
                key = { it.entity.asin },
                contentType = { "release_card" }
            ) { card ->
                ReleaseScorecard(
                    card = card,
                    alwaysShowCardActions = alwaysShowCardActions,
                    onOpenAudibleSearch = { onOpenAudibleSearch(card) },
                    onOpenAudioBookBay = { onOpenAudioBookBay(card) },
                    onOpenOrCopyMagnet = { onOpenOrCopyMagnet(card) },
                    onCopyRelease = { onCopyRelease(card) },
                    onExportCalendar = { onExportCalendar(card) },
                    onQuickTrack = onQuickTrack,
                    onArchiveTrackedBook = onArchiveTrackedBook,
                    onMuteBook = { onMuteBook(card) },
                    onMuteSeries = { onMuteSeries(card) },
                    onMarkSeriesSeqRead = onMarkSeriesSeqRead
                )
            }
        }

        if (tbaCards.isNotEmpty()) {
            item(key = "tba_toggle", contentType = "tba_toggle") {
                BracketButton(
                    label = if (showTbaExpanded) "- HIDE TBA / UNSCHEDULED (${tbaCards.size})"
                    else "+ SHOW TBA / UNSCHEDULED (${tbaCards.size})",
                    color = palette.comment,
                    onClick = onToggleTba
                )
            }
            if (showTbaExpanded) {
                items(
                    items = tbaCards,
                    key = { "tba_${it.entity.asin}" },
                    contentType = { "release_card" }
                ) { card ->
                    ReleaseScorecard(
                        card = card,
                        alwaysShowCardActions = alwaysShowCardActions,
                        onOpenAudibleSearch = { onOpenAudibleSearch(card) },
                        onOpenAudioBookBay = { onOpenAudioBookBay(card) },
                        onOpenOrCopyMagnet = { onOpenOrCopyMagnet(card) },
                        onCopyRelease = { onCopyRelease(card) },
                        onExportCalendar = { onExportCalendar(card) },
                        onQuickTrack = onQuickTrack,
                        onArchiveTrackedBook = onArchiveTrackedBook,
                        onMuteBook = { onMuteBook(card) },
                        onMuteSeries = { onMuteSeries(card) },
                        onMarkSeriesSeqRead = onMarkSeriesSeqRead
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseScorecard(
    card: ReleaseCardUiModel,
    alwaysShowCardActions: Boolean,
    onOpenAudibleSearch: () -> Unit,
    onOpenAudioBookBay: () -> Unit,
    onOpenOrCopyMagnet: () -> Unit,
    onCopyRelease: () -> Unit,
    onExportCalendar: () -> Unit,
    onQuickTrack: (TrackType, String, String, String?) -> Unit,
    onArchiveTrackedBook: (TrackedItemEntity) -> Unit,
    onMuteBook: () -> Unit,
    onMuteSeries: () -> Unit,
    onMarkSeriesSeqRead: (TrackedItemEntity, Int) -> Unit
) {
    val palette = LocalPtsPalette.current
    val rel = card.entity
    var isExpanded by remember(rel.asin) { mutableStateOf(false) }

    val badgeColor = when (card.badgeTone) {
        0 -> palette.green
        1 -> palette.orange
        2 -> palette.comment
        else -> palette.cyan
    }

    AsciiBox(
        headerLabel = card.headerLabel,
        borderColor = when {
            isExpanded -> palette.cyan
            rel.abbAvailable -> palette.green
            rel.isNewDiscovery && card.isFutureOrTba -> palette.pink
            else -> palette.surfaceLine
        },
        headerColor = badgeColor,
        onClick = { isExpanded = !isExpanded }
    ) {
        // Item #1, #2, #6, #8: Standardized 56dp thumbnail with semantic border,
        // 14sp bold uppercase title, 12sp metadata, no duplicate inner badge or Match: line
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            CoverThumbnail(
                coverUrl = rel.coverUrl,
                sizeDp = StandardThumbnailSize,
                borderColor = badgeColor
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                MonoText(
                    text = card.upperTitle,
                    color = palette.foreground,
                    fontSizeSp = 14,
                    bold = true
                )
                if (card.seriesLine.isNotEmpty()) {
                    MonoText(
                        text = card.seriesLine,
                        color = palette.purple,
                        fontSizeSp = 12
                    )
                }
                if (card.sameAuthorAndNarrator) {
                    MonoText(text = "By & Narrated: ${rel.authors}", color = palette.yellow, fontSizeSp = 12)
                } else {
                    MonoText(text = "Author: ${rel.authors}", color = palette.yellow, fontSizeSp = 12)
                    MonoText(text = "Narrator: ${rel.narrators}", color = palette.orange, fontSizeSp = 12)
                }
            }
        }

        if (card.hasSeriesGap && card.matchedSeriesItem != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                MonoText(
                    text = "[ ! GAP: Book #${card.unreadGapBookNum} Unread (Read: #${card.matchedSeriesItem.lastReadSequence}) ]",
                    color = palette.red,
                    fontSizeSp = 11,
                    bold = true
                )
                BracketButton(
                    label = "Mark #${card.unreadGapBookNum} Read",
                    color = palette.green,
                    onClick = { onMarkSeriesSeqRead(card.matchedSeriesItem, card.unreadGapBookNum) }
                )
            }
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
            Spacer(modifier = Modifier.height(6.dp))

            if (rel.previousReleaseDateIso.isNotBlank() && rel.previousReleaseDateIso != rel.releaseDateIso) {
                MonoText(
                    text = "[ DATE MOVED: ${rel.previousReleaseDateIso} -> ${rel.releaseDateIso} ]",
                    color = palette.orange,
                    fontSizeSp = 11,
                    bold = true
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            if (rel.narratorJustAnnounced) {
                MonoText(
                    text = "[ NARRATOR ANNOUNCED: ${rel.narrators} ]",
                    color = palette.green,
                    fontSizeSp = 11,
                    bold = true
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            MonoText(
                text = card.detailsMetaLine,
                color = palette.cyan,
                fontSizeSp = 11,
                bold = true
            )
            Spacer(modifier = Modifier.height(4.dp))
            MonoText(
                text = rel.synopsis.ifBlank { "No publisher summary posted yet for this listing." },
                color = palette.foreground,
                fontSizeSp = 12
            )

            val showMarkRead = card.canMarkCurrentRead && card.matchedSeriesItem != null && rel.seriesSequence != null
            val showArchiveStandaloneBook = card.matchedStandaloneBookItem != null && !card.isFutureOrTba
            val showTrackSeries = rel.seriesTitle.isNotBlank() && !card.isSeriesAlreadyTracked
            val showTrackAuthor = card.primaryAuthor.isNotBlank() && !card.isPrimaryAuthorAlreadyTracked

            if (showMarkRead || showArchiveStandaloneBook || showTrackSeries || showTrackAuthor) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showMarkRead && card.matchedSeriesItem != null && rel.seriesSequence != null) {
                        BracketButton(
                            label = "✓ Mark Book #${rel.seriesSequence} Read",
                            color = palette.green,
                            onClick = { onMarkSeriesSeqRead(card.matchedSeriesItem, rel.seriesSequence) }
                        )
                    }
                    if (showArchiveStandaloneBook && card.matchedStandaloneBookItem != null) {
                        BracketButton(
                            label = "✓ Archive Book",
                            color = palette.green,
                            onClick = { onArchiveTrackedBook(card.matchedStandaloneBookItem) }
                        )
                    }
                    if (showTrackSeries) {
                        BracketButton(
                            label = "+ Track Series",
                            color = palette.purple,
                            onClick = {
                                val ext = "${rel.seriesAsin}|${card.primaryAuthor}"
                                onQuickTrack(TrackType.SERIES, rel.seriesTitle, rel.coverUrl, ext)
                            }
                        )
                    }
                    if (showTrackAuthor) {
                        BracketButton(
                            label = "+ Track Author",
                            color = palette.yellow,
                            onClick = { onQuickTrack(TrackType.AUTHOR, card.primaryAuthor, rel.coverUrl, null) }
                        )
                    }
                }
            }
        }

        if (isExpanded || alwaysShowCardActions) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BracketButton(
                    label = "Audible",
                    color = palette.green,
                    onClick = onOpenAudibleSearch
                )
                BracketButton(
                    label = if (rel.abbAvailable) "ABB: AVAILABLE" else "ABB",
                    active = rel.abbAvailable,
                    color = if (rel.abbAvailable) palette.green else palette.orange,
                    onClick = onOpenAudioBookBay
                )
                if (rel.abbMagnetUri.startsWith("magnet:")) {
                    BracketButton(
                        label = "Magnet",
                        color = palette.green,
                        onClick = onOpenOrCopyMagnet
                    )
                }
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
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TrackedEntitiesView(
    trackedItems: List<TrackedItemEntity>,
    releases: List<ReleaseEntity>,
    sourceFilter: TrackedSourceFilter,
    sortOrder: TrackedSortOrder,
    recentlyDeletedItem: TrackedItemEntity?,
    onAddTracked: (TrackType, String, Int, String, String?) -> Unit,
    onSelectSourceForFilter: (TrackedItemEntity) -> Unit,
    onDeleteTracked: (TrackedItemEntity) -> Unit,
    onUndoDelete: (TrackedItemEntity) -> Unit,
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

    val upcomingCountsByItemId = remember(trackedItems, releases, todayIso) {
        val futureReleases = releases.filter { it.releaseDateIso == "TBA" || it.releaseDateIso >= todayIso }
        trackedItems.associate { item ->
            val q = item.queryValue.trim()
            val pAsin = item.pinnedAsin
            val count = futureReleases.count { rel ->
                when (item.type) {
                    TrackType.SERIES -> (pAsin.isNotBlank() && rel.seriesAsin.equals(pAsin, ignoreCase = true)) ||
                        rel.seriesTitle.equals(q, ignoreCase = true) ||
                        (pAsin.isBlank() && rel.seriesTitle.contains(q, ignoreCase = true))
                    TrackType.AUTHOR -> rel.authors.contains(q, ignoreCase = true)
                    TrackType.NARRATOR -> rel.narrators.contains(q, ignoreCase = true)
                    TrackType.BOOK -> (pAsin.isNotBlank() && rel.asin.equals(pAsin, ignoreCase = true)) ||
                        rel.asin.equals(q, ignoreCase = true) ||
                        rel.title.equals(q, ignoreCase = true)
                }
            }
            item.id to count
        }
    }

    val filteredTrackedItems = remember(trackedItems, sourceFilter, sortOrder, upcomingCountsByItemId) {
        val base = if (sourceFilter.trackType == null) {
            trackedItems
        } else {
            trackedItems.filter { it.type == sourceFilter.trackType }
        }
        when (sortOrder) {
            TrackedSortOrder.A_TO_Z -> base.sortedWith(
                compareBy<TrackedItemEntity> { it.type.ordinal }
                    .thenBy { it.queryValue.lowercase(Locale.US) }
            )
            TrackedSortOrder.MOST_UPCOMING -> base.sortedWith(
                compareByDescending<TrackedItemEntity> { upcomingCountsByItemId[it.id] ?: 0 }
                    .thenBy { it.queryValue.lowercase(Locale.US) }
            )
        }
    }

    val trackedKeySet = remember(trackedItems) {
        trackedItems.map { "${it.type.name}:${it.queryValue.trim().lowercase(Locale.US)}" }.toSet()
    }

    val performLiveSearch = {
        if (searchQuery.isNotBlank() && !isSearchingCatalog) {
            scope.launch {
                isSearchingCatalog = true
                catalogResults = AudibleApiClient.searchCatalog(
                    titleOrSeries = searchQuery,
                    numResults = 15,
                    maxPages = 1,
                    forceRefresh = false
                )
                isSearchingCatalog = false
            }
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (recentlyDeletedItem != null) {
            item(key = "undo_delete_banner", contentType = "undo_banner") {
                AsciiBox(
                    headerLabel = "SOURCE REMOVED",
                    borderColor = palette.orange,
                    headerColor = palette.orange
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MonoText(
                            text = "Removed ${recentlyDeletedItem.type.name}: ${recentlyDeletedItem.queryValue}",
                            color = palette.orange,
                            fontSizeSp = 12,
                            modifier = Modifier.weight(1f)
                        )
                        BracketButton(
                            label = "UNDO",
                            color = palette.green,
                            onClick = { onUndoDelete(recentlyDeletedItem) }
                        )
                    }
                }
            }
        }

        // Item #4: Framed 1dp terminal input fields (AsciiTextField)
        item(key = "search_box", contentType = "search_box") {
            AsciiBox(
                headerLabel = if (useDirectMode) "DIRECT MANUAL ADD" else "LIVE CATALOG SEARCH"
            ) {
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

                if (useDirectMode) {
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

                val placeholderStr = if (useDirectMode) {
                    if (directType == TrackType.BOOK) "Book title or 10-char Audible ASIN..."
                    else "Exact ${directType.name.lowercase().replaceFirstChar { it.uppercase() }} name..."
                } else {
                    "Search book, series, author, or narrator..."
                }

                AsciiTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = placeholderStr,
                    imeAction = ImeAction.Search,
                    onImeAction = {
                        if (useDirectMode) {
                            if (searchQuery.isNotBlank()) {
                                onAddTracked(directType, searchQuery, directLastRead.toIntOrNull() ?: 0, "", null)
                                searchQuery = ""
                            }
                        } else {
                            performLiveSearch()
                        }
                    }
                )

                if (useDirectMode && directType == TrackType.SERIES) {
                    Spacer(modifier = Modifier.height(6.dp))
                    MonoText(
                        text = "Last Completed Book # in Series:",
                        color = palette.comment,
                        fontSizeSp = 11
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    AsciiTextField(
                        value = directLastRead,
                        onValueChange = { directLastRead = it.filter { ch -> ch.isDigit() } },
                        placeholder = "0"
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (useDirectMode) {
                        BracketButton(
                            label = "+ ADD DIRECTLY",
                            color = palette.green,
                            onClick = {
                                if (searchQuery.isNotBlank()) {
                                    onAddTracked(
                                        directType,
                                        searchQuery,
                                        directLastRead.toIntOrNull() ?: 0,
                                        "",
                                        null
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
                    if (catalogResults.isNotEmpty() && !useDirectMode) {
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

        // Item #1, #2, #3: Standardized AsciiSectionHeader, 56dp thumbnail, and 14sp bold uppercase title
        if (catalogResults.isNotEmpty() && !useDirectMode) {
            stickyHeader(key = "live_hdr") {
                AsciiSectionHeader(
                    title = "LIVE CATALOG RESULTS (${catalogResults.size})",
                    color = palette.cyan
                )
            }
            items(
                items = catalogResults,
                key = { "live_${it.asin}" },
                contentType = { "live_result" }
            ) { prod ->
                val seriesTracked = prod.seriesTitle.isNotBlank() &&
                    "SERIES:${prod.seriesTitle.trim().lowercase(Locale.US)}" in trackedKeySet
                val firstAuthor = prod.authors.firstOrNull()?.trim().orEmpty()
                val authorTracked = firstAuthor.isNotBlank() &&
                    "AUTHOR:${firstAuthor.lowercase(Locale.US)}" in trackedKeySet
                val firstNarrator = prod.narrators.firstOrNull()?.trim().orEmpty()
                val narratorTracked = firstNarrator.isNotBlank() &&
                    "NARRATOR:${firstNarrator.lowercase(Locale.US)}" in trackedKeySet
                val bookTracked = "BOOK:${prod.asin.lowercase(Locale.US)}" in trackedKeySet ||
                    "BOOK:${prod.title.trim().lowercase(Locale.US)}" in trackedKeySet

                val cleanedTitle = prod.title.replace(marketingSubtitleRegex, "").trim().ifBlank { prod.title }
                val authorsStr = prod.authors.joinToString(", ")
                val narratorsStr = prod.narrators.joinToString(", ")
                val sameAuthorAndNarrator = authorsStr.trim().equals(narratorsStr.trim(), ignoreCase = true)

                AsciiBox(
                    headerLabel = "${prod.releaseDateIso} • ${prod.asin}",
                    borderColor = palette.cyan,
                    headerColor = palette.cyan
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        CoverThumbnail(
                            coverUrl = prod.coverUrl,
                            sizeDp = StandardThumbnailSize,
                            borderColor = palette.cyan
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            MonoText(text = cleanedTitle.uppercase(), bold = true, fontSizeSp = 14)
                            if (prod.seriesTitle.isNotBlank()) {
                                val seqPart = prod.seriesSequence?.let { " #$it" }.orEmpty()
                                MonoText(
                                    text = "Series: ${prod.seriesTitle}$seqPart",
                                    color = palette.purple,
                                    fontSizeSp = 12
                                )
                            }
                            if (sameAuthorAndNarrator && authorsStr.isNotBlank()) {
                                MonoText(
                                    text = "By & Narrated: $authorsStr",
                                    color = palette.yellow,
                                    fontSizeSp = 12
                                )
                            } else {
                                MonoText(
                                    text = "Author: $authorsStr",
                                    color = palette.yellow,
                                    fontSizeSp = 12
                                )
                                MonoText(
                                    text = "Narrator: $narratorsStr",
                                    color = palette.orange,
                                    fontSizeSp = 12
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (prod.seriesTitle.isNotBlank()) {
                            BracketButton(
                                label = if (seriesTracked) "✓ Series" else "+ Series",
                                active = seriesTracked,
                                color = palette.purple,
                                onClick = {
                                    val pinnedSeriesMeta = "${prod.seriesAsin}|$firstAuthor"
                                    onAddTracked(TrackType.SERIES, prod.seriesTitle, 0, prod.coverUrl, pinnedSeriesMeta)
                                }
                            )
                        }
                        if (firstAuthor.isNotBlank()) {
                            BracketButton(
                                label = if (authorTracked) "✓ Author" else "+ Author",
                                active = authorTracked,
                                color = palette.yellow,
                                onClick = {
                                    onAddTracked(
                                        TrackType.AUTHOR,
                                        firstAuthor,
                                        0,
                                        prod.coverUrl,
                                        prod.primaryAuthorAsin.ifBlank { null }
                                    )
                                }
                            )
                        }
                        if (firstNarrator.isNotBlank()) {
                            BracketButton(
                                label = if (narratorTracked) "✓ Narrator" else "+ Narrator",
                                active = narratorTracked,
                                color = palette.orange,
                                onClick = { onAddTracked(TrackType.NARRATOR, firstNarrator, 0, prod.coverUrl, null) }
                            )
                        }
                        BracketButton(
                            label = if (bookTracked) "✓ Book" else "+ Book",
                            active = bookTracked,
                            color = palette.green,
                            onClick = {
                                val pinnedBookMeta = "${prod.asin}|$firstAuthor"
                                onAddTracked(TrackType.BOOK, prod.title, 0, prod.coverUrl, pinnedBookMeta)
                            }
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

        // Item #3 & #5: Standardized AsciiSectionHeader for FOLLOWED SOURCES (filter/sort now lives in BottomThumbBar)
        stickyHeader(key = "followed_hdr") {
            AsciiSectionHeader(
                title = "FOLLOWED SOURCES (${filteredTrackedItems.size}/${trackedItems.size})",
                color = palette.purple
            )
        }

        items(
            items = filteredTrackedItems,
            key = { it.id },
            contentType = { "tracked_item" }
        ) { item ->
            val upcomingBadgeCount = upcomingCountsByItemId[item.id] ?: 0
            val typeAccent = when (item.type) {
                TrackType.SERIES -> palette.purple
                TrackType.AUTHOR -> palette.yellow
                TrackType.NARRATOR -> palette.orange
                TrackType.BOOK -> palette.green
            }
            val fallbackBadge = when (item.type) {
                TrackType.SERIES -> "[SER]"
                TrackType.AUTHOR -> "[AUT]"
                TrackType.NARRATOR -> "[NAR]"
                TrackType.BOOK -> "[BOK]"
            }

            // Item #1, #2, #7, #9: Clean header (+---[ SERIES • 1 UPCOMING ]---+),
            // 56dp thumbnail, 14sp uppercase title, Author inside body, and no duplicate [ ABB ] button
            AsciiBox(
                headerLabel = "${item.type.name} • [ $upcomingBadgeCount UPCOMING ]",
                headerColor = typeAccent,
                onClick = { onSelectSourceForFilter(item) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverThumbnail(
                            coverUrl = item.thumbnailUrl,
                            sizeDp = StandardThumbnailSize,
                            fallbackText = fallbackBadge,
                            borderColor = typeAccent
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            MonoText(
                                text = item.queryValue.uppercase(),
                                fontSizeSp = 14,
                                bold = true
                            )
                            if ((item.type == TrackType.SERIES || item.type == TrackType.BOOK) && item.pinnedAuthor.isNotBlank()) {
                                MonoText(
                                    text = "Author: ${item.pinnedAuthor}",
                                    color = palette.yellow,
                                    fontSizeSp = 12
                                )
                            }
                            if (item.type == TrackType.SERIES) {
                                MonoText(
                                    text = "Last Read: #${item.lastReadSequence}",
                                    color = palette.purple,
                                    fontSizeSp = 12
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.type == TrackType.SERIES) {
                            BracketButton(
                                label = "-1",
                                color = palette.comment,
                                onClick = {
                                    onUpdateSeriesSeq(item, (item.lastReadSequence - 1).coerceAtLeast(0))
                                }
                            )
                            BracketButton(
                                label = "+1",
                                color = palette.cyan,
                                onClick = { onUpdateSeriesSeq(item, item.lastReadSequence + 1) }
                            )
                        }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsAndBackupView(
    prefs: FilterPrefs,
    syncTelemetry: SyncTelemetry,
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
        item(key = "cfg_theme") {
            AsciiBox(headerLabel = "APPEARANCE & SYSTEM THEME") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ThemeMode.values().forEach { mode ->
                        BracketButton(
                            label = mode.label,
                            active = prefs.themeMode == mode,
                            color = palette.orange,
                            onClick = { onUpdatePrefs(prefs.copy(themeModeCode = mode.code)) }
                        )
                    }
                }
            }
        }

        item(key = "cfg_options") {
            AsciiBox(headerLabel = "CATALOG & TRACKER OPTIONS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText("English Language Only", fontSizeSp = 13)
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
                    MonoText("Hide Abridged Editions", fontSizeSp = 13)
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
                    MonoText("Hide Dramatized / Full-Cast", fontSizeSp = 13)
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
                    MonoText("Always Show Card Actions", fontSizeSp = 13)
                    BracketButton(
                        label = if (prefs.alwaysShowCardActions) "ON" else "OFF",
                        active = prefs.alwaysShowCardActions,
                        onClick = { onUpdatePrefs(prefs.copy(alwaysShowCardActions = !prefs.alwaysShowCardActions)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText("Daily AudioBookBay Check", fontSizeSp = 13)
                    BracketButton(
                        label = if (prefs.checkAbbDaily) "ON" else "OFF",
                        active = prefs.checkAbbDaily,
                        onClick = { onUpdatePrefs(prefs.copy(checkAbbDaily = !prefs.checkAbbDaily)) }
                    )
                }
            }
        }

        item(key = "cfg_region") {
            AsciiBox(headerLabel = "AUDIBLE STORE REGION") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
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

        item(key = "cfg_notif") {
            AsciiBox(headerLabel = "NOTIFICATION SCHEDULE (T-7d, T-0d & ABB)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "Alert Time: %02d:%02d Local".format(
                            prefs.notifyHour,
                            prefs.notifyMinute
                        ),
                        color = palette.cyan,
                        fontSizeSp = 13,
                        bold = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BracketButton(
                            label = "-1h",
                            onClick = {
                                val h = (prefs.notifyHour + 23) % 24
                                onUpdatePrefs(prefs.copy(notifyHour = h))
                            }
                        )
                        BracketButton(
                            label = "+1h",
                            onClick = {
                                val h = (prefs.notifyHour + 1) % 24
                                onUpdatePrefs(prefs.copy(notifyHour = h))
                            }
                        )
                    }
                }
            }
        }

        item(key = "cfg_cache") {
            AsciiBox(headerLabel = "METADATA, SYNC STATUS & CACHE") {
                MonoText(
                    text = syncTelemetry.formattedLine,
                    color = if (syncTelemetry.lastSyncUsedCachedFallback) palette.orange else palette.green,
                    fontSizeSp = 12,
                    bold = true
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MonoText(
                            text = "Cached Files: ${cacheStats.fileCount} (${cacheStats.formattedSize})",
                            color = palette.cyan,
                            fontSizeSp = 12,
                            bold = true
                        )
                        MonoText(
                            text = "Auto-pruned 30d / 15 MB LRU cap • Deep 3-page sync",
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

        item(key = "cfg_backup") {
            AsciiBox(headerLabel = "PLAIN-TEXT JSON BACKUP & RESTORE") {
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

        item(key = "cfg_about") {
            AsciiBox(headerLabel = "APPLICATION INFO") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "Version, credits & architecture",
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
                    text = "Version 2.8.0 • Plain-Text Audiobook Release Tracker",
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
                    text = "• Color Palettes: Official Dracula Dark (#282A36) and Alucard Light (#FFFBEB) specifications with System Auto theme synchronization.",
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
                    text = "• Smart Series Pinning & ABB Checker: Locks followed series to their exact author/ASIN, checks AudioBookBay daily with 1.4s throttling, and extracts magnet links automatically.",
                    color = palette.foreground,
                    fontSizeSp = 12
                )
            }
        }
    }
}
