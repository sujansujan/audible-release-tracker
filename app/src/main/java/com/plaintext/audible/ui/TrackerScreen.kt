package com.plaintext.audible.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.plaintext.audible.data.*
import com.plaintext.audible.worker.ReleaseCheckWorker
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ActiveTab {
    UPCOMING, TRACKED, DND, SETTINGS
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
    var isSyncing by remember { mutableStateOf(false) }
    var showTbaExpanded by remember { mutableStateOf(false) }

    val seriesReadMap = remember(trackedItems) {
        trackedItems.filter { it.type == TrackType.SERIES }
            .associateBy({ it.queryValue.lowercase() }, { it })
    }

    CompositionLocalProvider(LocalPtsPalette provides palette) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
                .systemBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoText(
                    text = "PTS // AUDIBLE US",
                    color = palette.purple,
                    fontSizeSp = 16,
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
                                    repo.syncCatalog()
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
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

            HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)
            Spacer(modifier = Modifier.height(8.dp))

            when (activeTab) {
                ActiveTab.UPCOMING -> UpcomingFeedView(
                    releases = releases,
                    trackedSeriesMap = seriesReadMap,
                    showTbaExpanded = showTbaExpanded,
                    onToggleTba = { showTbaExpanded = !showTbaExpanded },
                    onClearNewBadges = { scope.launch { repo.dao.clearNewDiscoveryFlags() } },
                    onOpenAudible = { asin ->
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.audible.com/pd/$asin")
                        )
                        context.startActivity(intent)
                    },
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
                    onAddTracked = { type, query, lastRead ->
                        scope.launch {
                            repo.dao.insertTrackedItem(
                                TrackedItemEntity(
                                    type = type,
                                    queryValue = query.trim(),
                                    lastReadSequence = lastRead
                                )
                            )
                            isSyncing = true
                            repo.syncCatalog()
                            isSyncing = false
                        }
                    },
                    onDeleteTracked = { item ->
                        scope.launch { repo.dao.deleteTrackedItem(item) }
                    },
                    onUpdateSeriesSeq = { item, newSeq ->
                        scope.launch { repo.dao.updateSeriesReadSequence(item.id, newSeq) }
                    }
                )

                ActiveTab.DND -> DndListView(
                    dndRules = dndRules,
                    onRemoveRule = { rule ->
                        scope.launch { repo.dao.deleteDndRule(rule) }
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
                    },
                    onExportBackup = onExportBackup,
                    onImportBackup = onImportBackup
                )
            }
        }
    }
}

@Composable
private fun UpcomingFeedView(
    releases: List<ReleaseEntity>,
    trackedSeriesMap: Map<String, TrackedItemEntity>,
    showTbaExpanded: Boolean,
    onToggleTba: () -> Unit,
    onClearNewBadges: () -> Unit,
    onOpenAudible: (String) -> Unit,
    onMuteBook: (ReleaseEntity) -> Unit,
    onMuteSeries: (ReleaseEntity) -> Unit,
    onMarkPriorRead: (TrackedItemEntity, Int) -> Unit
) {
    val palette = LocalPtsPalette.current
    val scheduled = remember(releases) { releases.filter { it.releaseDateIso != "TBA" } }
    val tbaList = remember(releases) { releases.filter { it.releaseDateIso == "TBA" } }
    val newCount = remember(releases) { releases.count { it.isNewDiscovery } }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        text = "No scheduled upcoming releases yet.\nTap [ TRACKED ] above to add a Series, Author, Narrator, or Book ASIN, then tap [ SYNC ].",
                        color = palette.comment
                    )
                }
            }
        }

        items(scheduled, key = { it.asin }) { rel ->
            ReleaseScorecard(
                rel = rel,
                trackedSeriesMap = trackedSeriesMap,
                onOpenAudible = { onOpenAudible(rel.asin) },
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
                        onOpenAudible = { onOpenAudible(rel.asin) },
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
    onOpenAudible: () -> Unit,
    onMuteBook: () -> Unit,
    onMuteSeries: () -> Unit,
    onMarkPriorRead: (TrackedItemEntity, Int) -> Unit
) {
    val palette = LocalPtsPalette.current
    val today = remember { LocalDate.now() }
    val daysUntil = remember(rel.releaseDateIso) {
        runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(rel.releaseDateIso)) }.getOrNull()
    }

    val countdownBadge = when {
        daysUntil == null -> "[ TBA ]"
        daysUntil == 0L -> "[ OUT TODAY ]"
        daysUntil < 0L -> "[ RELEASED ]"
        daysUntil <= 7L -> "[ T-${daysUntil}d ]"
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
            MonoText(text = "[ NEW PRE-ORDER ]", color = palette.pink, fontSizeSp = 12, bold = true)
        }

        MonoText(text = rel.title.uppercase(), color = palette.foreground, fontSizeSp = 15, bold = true)

        if (rel.seriesTitle.isNotBlank()) {
            val seqLabel = rel.seriesSequence?.let { " #$it" }.orEmpty()
            MonoText(
                text = "Series: ${rel.seriesTitle}$seqLabel",
                color = palette.purple,
                fontSizeSp = 13
            )
        }

        MonoText(text = "Author: ${rel.authors}", color = palette.yellow, fontSizeSp = 13)
        MonoText(text = "Narrator: ${rel.narrators}", color = palette.orange, fontSizeSp = 13)
        MonoText(
            text = "Match: [${rel.matchedTags.replace(",", "] [")}] • ASIN: ${rel.asin}",
            color = palette.comment,
            fontSizeSp = 12
        )

        if (hasSeriesGap && matchedTrackedSeries != null && seq != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                MonoText(
                    text = "[ ! GAP: Book #${seq - 1} Unread (Read: #${matchedTrackedSeries.lastReadSequence}) ]",
                    color = palette.red,
                    fontSizeSp = 12,
                    bold = true
                )
                BracketButton(
                    label = "Mark #${seq - 1} Read",
                    color = palette.green,
                    onClick = { onMarkPriorRead(matchedTrackedSeries, seq - 1) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = palette.surfaceLine, thickness = 1.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BracketButton(
                label = "Audible ->",
                color = palette.cyan,
                onClick = onOpenAudible
            )
            Row {
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

@Composable
private fun TrackedEntitiesView(
    trackedItems: List<TrackedItemEntity>,
    onAddTracked: (TrackType, String, Int) -> Unit,
    onDeleteTracked: (TrackedItemEntity) -> Unit,
    onUpdateSeriesSeq: (TrackedItemEntity, Int) -> Unit
) {
    val palette = LocalPtsPalette.current
    var selectedType by remember { mutableStateOf(TrackType.SERIES) }
    var inputQuery by remember { mutableStateOf("") }
    var lastReadInput by remember { mutableStateOf("0") }

    Column(modifier = Modifier.fillMaxSize()) {
        AsciiBox(headerLabel = "ADD TRACKED SOURCE") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TrackType.values().forEach { t ->
                    BracketButton(
                        label = t.name,
                        active = selectedType == t,
                        onClick = { selectedType = t }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            MonoText(
                text = if (selectedType == TrackType.BOOK) "Enter Book Title or 10-char Audible ASIN:"
                else "Enter ${selectedType.name.lowercase().replaceFirstChar { it.uppercase() }} Name:",
                color = palette.comment,
                fontSizeSp = 12
            )

            BasicTextField(
                value = inputQuery,
                onValueChange = { inputQuery = it },
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

            if (selectedType == TrackType.SERIES) {
                Spacer(modifier = Modifier.height(6.dp))
                MonoText(
                    text = "Last Completed Book # in Series (for Gap Detector):",
                    color = palette.comment,
                    fontSizeSp = 12
                )
                BasicTextField(
                    value = lastReadInput,
                    onValueChange = { lastReadInput = it.filter { ch -> ch.isDigit() } },
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
                label = "+ ADD TO TRACKER",
                color = palette.green,
                onClick = {
                    if (inputQuery.isNotBlank()) {
                        onAddTracked(
                            selectedType,
                            inputQuery,
                            lastReadInput.toIntOrNull() ?: 0
                        )
                        inputQuery = ""
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    label = "+1 Read",
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
}

@Composable
private fun DndListView(
    dndRules: List<DndRuleEntity>,
    onRemoveRule: (DndRuleEntity) -> Unit
) {
    val palette = LocalPtsPalette.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit
) {
    val palette = LocalPtsPalette.current
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        AsciiBox(headerLabel = "CATALOG AUTO-FILTERS (US MARKETPLACE)") {
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
