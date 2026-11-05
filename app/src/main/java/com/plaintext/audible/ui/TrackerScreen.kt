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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val matchLine: String,
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
                // Item #1 & #6: Clean top header with [ TAB ] on left and only [ SYNC ] on right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "[ ${activeTab.name} ]",
                        color = palette.comment,
                        fontSizeSp = 13,
                        bold = true
                    )
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
<<<<<<< HEAD
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
=======
                            onQuickTrack = { type, value, thumbUrl ->
                                handleAddTracked(type, value, 0, thumbUrl, null)
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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
                upcomingCount = upcomingCount,
                trackedCount = trackedItems.size,
                dndCount = dndRules.size,
                onSelectFilter = {
                    focusedSourceQuery = null
                    activeFilter = it
                },
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
    upcomingCount: Int,
    trackedCount: Int,
    dndCount: Int,
    onSelectFilter: (QuickFilter) -> Unit,
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

<<<<<<< HEAD
private val marketingSubtitleRegex = Regex(
    ":\\s*(?:a\\s+)?(?:litrpg|progression\\s+fantasy|cultivation|isekai|sci-fi|fantasy)\\s+(?:adventure|epic|novel|series|saga|story|tale).*$",
    RegexOption.IGNORE_CASE
)

=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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
                MonoText(
                    text = "DO NOT DISTURB LIST (${dndRules.size}) — Suppressed from all Author, Narrator, and Series syncs:",
                    color = palette.comment,
                    fontSizeSp = 12
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
<<<<<<< HEAD
        val bookList = trackedItems.filter { it.type == TrackType.BOOK }
=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16

        fun toUiModel(rel: ReleaseEntity): ReleaseCardUiModel {
            val parsedDate = runCatching { LocalDate.parse(rel.releaseDateIso) }.getOrNull()
            val daysUntil = parsedDate?.let { ChronoUnit.DAYS.between(today, it) }

            val countdownBadge = when {
                daysUntil == null -> "[ TBA ]"
                daysUntil == 0L -> "[ OUT TODAY ]"
                daysUntil < 0L -> "[ ${-daysUntil}d AGO ]"
                else -> "[ T-${daysUntil}d ]"
            }
            val abbHeaderSuffix = if (rel.abbAvailable) " • [ ABB ]" else ""
            val badgeTone = when {
                rel.abbAvailable || daysUntil == 0L -> 0
                daysUntil != null && daysUntil < 0L -> 2
                daysUntil != null && daysUntil in 1..7 -> 1
                else -> 3
            }

            val matchedSeries = if (rel.seriesTitle.isNotBlank()) {
                seriesList.firstOrNull { s ->
<<<<<<< HEAD
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
=======
                    rel.seriesTitle.contains(s.queryValue, ignoreCase = true) ||
                        s.queryValue.contains(rel.seriesTitle, ignoreCase = true)
                }
            } else null

            val primaryAuthor = rel.authors.split(",").firstOrNull()?.trim().orEmpty()
            val isAuthorTracked = primaryAuthor.isNotBlank() && authorList.any { a ->
                a.queryValue.equals(primaryAuthor, ignoreCase = true) ||
                    primaryAuthor.contains(a.queryValue, ignoreCase = true) ||
                    a.queryValue.contains(primaryAuthor, ignoreCase = true)
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
            }

            val seq = rel.seriesSequence
            val hasGap = matchedSeries != null && seq != null && seq > 1 && matchedSeries.lastReadSequence < (seq - 1)
            val canMarkRead = matchedSeries != null && seq != null && daysUntil != null && daysUntil <= 0L && matchedSeries.lastReadSequence < seq

            val hours = rel.runtimeMinutes / 60
            val mins = rel.runtimeMinutes % 60
            val runtimeStr = if (rel.runtimeMinutes > 0) "${hours}h ${mins}m" else "TBA"
            val pubStr = rel.publisher.ifBlank { "TBA" }
            val langSuffix = if (rel.language.equals("English", ignoreCase = true)) "" else "  |  Lang: ${rel.language}"

            // Item #6: Strip repetitive LitRPG / Progression Fantasy marketing subtitle and check if Author == Narrator
            val cleanedTitle = rel.title.replace(marketingSubtitleRegex, "").trim().ifBlank { rel.title }
            val sameAuthorAndNarrator = rel.authors.trim().equals(rel.narrators.trim(), ignoreCase = true)

            return ReleaseCardUiModel(
                entity = rel,
                upperTitle = cleanedTitle.uppercase(),
                headerLabel = "${rel.releaseDateIso} $countdownBadge$abbHeaderSuffix",
                badgeTone = badgeTone,
                isFutureOrTba = daysUntil == null || daysUntil >= 0L,
                seriesLine = if (rel.seriesTitle.isNotBlank()) {
                    "Series: ${rel.seriesTitle}${seq?.let { " #$it" }.orEmpty()}"
                } else "",
                sameAuthorAndNarrator = sameAuthorAndNarrator,
                matchLine = "Match: [${rel.matchedTags.replace(",", "] [")}]",
                detailsMetaLine = "Runtime: $runtimeStr  |  Publisher: $pubStr$langSuffix",
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
                    monthHeader = "+=== $monthName (${list.size} $noun) ===+",
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
                        color = palette.comment
                    )
                }
            }
        }

        monthGroups.forEach { group ->
            stickyHeader(key = "hdr_${group.monthHeader}") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.background)
                        .padding(vertical = 4.dp)
                ) {
                    MonoText(
                        text = group.monthHeader,
                        color = palette.purple,
                        fontSizeSp = 13,
                        bold = true
                    )
                }
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
        if (rel.abbAvailable) {
            MonoText(text = "[ ABB: AVAILABLE ]", color = palette.green, fontSizeSp = 11, bold = true)
            Spacer(modifier = Modifier.height(2.dp))
        } else if (rel.isNewDiscovery && card.isFutureOrTba) {
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
                MonoText(
                    text = card.matchLine,
                    color = palette.comment,
                    fontSizeSp = 11
                )
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

<<<<<<< HEAD
            val showMarkRead = card.canMarkCurrentRead && card.matchedSeriesItem != null && rel.seriesSequence != null
            val showArchiveStandaloneBook = card.matchedStandaloneBookItem != null && !card.isFutureOrTba
            val showTrackSeries = rel.seriesTitle.isNotBlank() && !card.isSeriesAlreadyTracked
            val showTrackAuthor = card.primaryAuthor.isNotBlank() && !card.isPrimaryAuthorAlreadyTracked

            if (showMarkRead || showArchiveStandaloneBook || showTrackSeries || showTrackAuthor) {
=======
            // Item #4: Only show + Track Series / + Track Author if NOT already tracked
            val showMarkRead = card.canMarkCurrentRead && card.matchedSeriesItem != null && rel.seriesSequence != null
            val showTrackSeries = rel.seriesTitle.isNotBlank() && !card.isSeriesAlreadyTracked
            val showTrackAuthor = card.primaryAuthor.isNotBlank() && !card.isPrimaryAuthorAlreadyTracked

            if (showMarkRead || showTrackSeries || showTrackAuthor) {
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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
<<<<<<< HEAD
                    // Item #3: 1-tap Archive Book button for released standalone BOOK trackers
                    if (showArchiveStandaloneBook && card.matchedStandaloneBookItem != null) {
                        BracketButton(
                            label = "✓ Archive Book",
                            color = palette.green,
                            onClick = { onArchiveTrackedBook(card.matchedStandaloneBookItem) }
                        )
                    }
=======
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
                    if (showTrackSeries) {
                        BracketButton(
                            label = "+ Track Series",
                            color = palette.purple,
<<<<<<< HEAD
                            onClick = {
                                val ext = "${rel.seriesAsin}|${card.primaryAuthor}"
                                onQuickTrack(TrackType.SERIES, rel.seriesTitle, rel.coverUrl, ext)
                            }
=======
                            onClick = { onQuickTrack(TrackType.SERIES, rel.seriesTitle, rel.coverUrl) }
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
                        )
                    }
                    if (showTrackAuthor) {
                        BracketButton(
                            label = "+ Track Author",
                            color = palette.yellow,
<<<<<<< HEAD
                            onClick = { onQuickTrack(TrackType.AUTHOR, card.primaryAuthor, rel.coverUrl, null) }
=======
                            onClick = { onQuickTrack(TrackType.AUTHOR, card.primaryAuthor, rel.coverUrl) }
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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
                // Item #5: 1-tap Magnet button when Info Hash was extracted from ABB
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackedEntitiesView(
    trackedItems: List<TrackedItemEntity>,
    releases: List<ReleaseEntity>,
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

    var sourceFilter by remember { mutableStateOf(TrackedSourceFilter.ALL) }
    var sortOrder by remember { mutableStateOf(TrackedSortOrder.A_TO_Z) }

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
        contentPadding = PaddingValues(bottom = 84.dp),
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

<<<<<<< HEAD
=======
        // Item #2 & #5: Direct [ LIVE SEARCH ] / [ DIRECT ADD ] switch inside TRACKED search box,
        // and [ CLEAR ] shown only when catalogResults is non-empty
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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

                MonoText(
                    text = if (useDirectMode) {
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
                        if (useDirectMode) {
                            if (searchQuery.isNotBlank()) {
                                onAddTracked(directType, searchQuery, directLastRead.toIntOrNull() ?: 0, "", null)
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

                if (useDirectMode && directType == TrackType.SERIES) {
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
<<<<<<< HEAD
=======
                    // Item #5: Only show [ CLEAR ] when live catalog results are open
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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

        if (catalogResults.isNotEmpty() && !useDirectMode) {
            item(key = "live_hdr", contentType = "header") {
                MonoText(
                    text = "LIVE CATALOG RESULTS (${catalogResults.size}) - TAP TO FOLLOW:",
                    color = palette.cyan,
                    fontSizeSp = 12,
                    bold = true
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
<<<<<<< HEAD
                                    // Item #1: Pin both seriesAsin and primary author when following a Series
                                    val pinnedSeriesMeta = "${prod.seriesAsin}|$firstAuthor"
                                    onAddTracked(TrackType.SERIES, prod.seriesTitle, 0, prod.coverUrl, pinnedSeriesMeta)
=======
                                    onAddTracked(TrackType.SERIES, prod.seriesTitle, 0, prod.coverUrl, prod.seriesAsin)
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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
<<<<<<< HEAD
                            onClick = {
                                val pinnedBookMeta = "${prod.asin}|$firstAuthor"
                                onAddTracked(TrackType.BOOK, prod.title, 0, prod.coverUrl, pinnedBookMeta)
                            }
=======
                            onClick = { onAddTracked(TrackType.BOOK, prod.asin, 0, prod.coverUrl, prod.asin) }
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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

        item(key = "followed_hdr", contentType = "followed_hdr") {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MonoText(
                        text = "FOLLOWED SOURCES (${filteredTrackedItems.size}/${trackedItems.size}):",
                        color = palette.purple,
                        fontSizeSp = 12,
                        bold = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoText(text = "SORT:", color = palette.comment, fontSizeSp = 11)
                        TrackedSortOrder.values().forEach { so ->
                            BracketButton(
                                label = so.label,
                                active = sortOrder == so,
                                color = palette.cyan,
                                onClick = { sortOrder = so }
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
            val authorTag = if ((item.type == TrackType.SERIES || item.type == TrackType.BOOK) && item.pinnedAuthor.isNotBlank()) {
                " • ${item.pinnedAuthor}"
            } else ""

            AsciiBox(
<<<<<<< HEAD
                headerLabel = "${item.type.name}$authorTag • [ $upcomingBadgeCount upcoming ]",
=======
                headerLabel = "${item.type.name} • [ $upcomingBadgeCount upcoming ]",
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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
                            sizeDp = 52.dp,
                            fallbackText = fallbackBadge,
                            borderColor = typeAccent
                        )
                        Spacer(modifier = Modifier.width(10.dp))
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
                            label = "ABB",
                            color = palette.orange,
                            onClick = { onOpenAbbQuery("${item.queryValue} ${item.pinnedAuthor}".trim()) }
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
<<<<<<< HEAD
=======
                MonoText(
                    text = "Follow Android system Dark/Light mode automatically or lock to Dracula / Alucard:",
                    color = palette.comment,
                    fontSizeSp = 12
                )
                Spacer(modifier = Modifier.height(4.dp))
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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

<<<<<<< HEAD
=======
        // Item #2: Removed redundant Direct Manual Add Mode toggle from SETTINGS
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
        item(key = "cfg_options") {
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
                    MonoText("Always Show Card Actions")
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
                    MonoText("Daily AudioBookBay Check")
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

        // Item #3: Removed [ Reset 09:00 ] button from NOTIFICATION SCHEDULE
        item(key = "cfg_notif") {
            AsciiBox(headerLabel = "NOTIFICATION SCHEDULE (T-7d, T-0d & ABB)") {
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
<<<<<<< HEAD
                    text = "Version 2.6.0 • Plain-Text Audiobook Release Tracker",
=======
                    text = "Version 2.5.0 • Plain-Text Audiobook Release Tracker",
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
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
<<<<<<< HEAD
                    text = "• Smart Series Pinning & ABB Checker: Locks followed series to their exact author/ASIN, checks AudioBookBay daily with 1.5s throttling, and extracts magnet links automatically.",
=======
                    text = "• Smart Deduplication & Reliability: Deep paginated catalog sync, release date shift & narrator announcement detection, automatic 15 MB LRU cache pruning, and T-7d / T-0d notifications.",
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
                    color = palette.foreground,
                    fontSizeSp = 12
                )
            }
        }
    }
}
