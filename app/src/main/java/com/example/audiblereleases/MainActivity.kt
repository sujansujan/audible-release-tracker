package com.example.audiblereleases

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.audiblereleases.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

private val AlucardBackground = Color(0xFFF8F8F2)
private val AlucardInk = Color(0xFF282A36)
private val AlucardLink = Color(0xFFA4005D)
private val AlucardRule = Color(0xFF9B9BA3)
private val DraculaBackground = Color(0xFF282A36)
private val DraculaInk = Color(0xFFF8F8F2)
private val DraculaLink = Color(0xFFBD93F9)
private val DraculaRule = Color(0xFF6272A4)
private val Green = Color(0xFF50FA7B)
private val Amber = Color(0xFFFFB86C)
private val SquareShape = RoundedCornerShape(0.dp)

@Composable private fun PlainTextTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(background = DraculaBackground, surface = DraculaBackground, surfaceVariant = Color(0xFF44475A), primary = DraculaLink, secondary = Green, onBackground = DraculaInk, onSurface = DraculaInk, outline = DraculaRule) else lightColorScheme(background = AlucardBackground, surface = AlucardBackground, surfaceVariant = Color(0xFFEDEDE7), primary = AlucardLink, secondary = Color(0xFF006B3C), onBackground = AlucardInk, onSurface = AlucardInk, outline = AlucardRule)
    MaterialTheme(colorScheme = scheme, typography = Typography().run { copy(headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Monospace), titleLarge = titleLarge.copy(fontFamily = FontFamily.Monospace), titleMedium = titleMedium.copy(fontFamily = FontFamily.Monospace), bodyLarge = bodyLarge.copy(fontFamily = FontFamily.Monospace), bodyMedium = bodyMedium.copy(fontFamily = FontFamily.Monospace), labelLarge = labelLarge.copy(fontFamily = FontFamily.Monospace)) }, shapes = Shapes(extraSmall = SquareShape, small = SquareShape, medium = SquareShape, large = SquareShape, extraLarge = SquareShape), content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); scheduleSync(); setContent { val dark = remember { mutableStateOf(getPreferences(0).getBoolean("dark_mode", false)) }; PlainTextTheme(dark.value) { AudibleApp(dark.value, onDarkChanged = { value -> dark.value = value; getPreferences(0).edit().putBoolean("dark_mode", value).apply() }) } } }
    private fun scheduleSync() { WorkManager.getInstance(this).enqueueUniquePeriodicWork("audible-release-sync", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<com.example.audiblereleases.worker.ReleaseSyncWorker>(1, TimeUnit.DAYS).build()) }
}

@Composable private fun AudibleApp(dark: Boolean, onDarkChanged: (Boolean) -> Unit, vm: AppViewModel = viewModel()) {
    val context = LocalContext.current; var tab by rememberSaveable { mutableIntStateOf(0) }; var selected by remember { mutableStateOf<ReleaseEntity?>(null) }; val upcomingScrollState = rememberLazyListState()
    val follows by vm.follows.collectAsState(); val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val prefs = remember { context.getSharedPreferences("audible-releases", Context.MODE_PRIVATE) }; var onboarding by remember { mutableStateOf(!prefs.getBoolean("onboarding_done", false)) }
    LaunchedEffect(Unit) { vm.refresh(); if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    if (selected != null) { BackHandler { selected = null }; ReleaseDetail(selected!!, { selected = null }, context); return }
    if (onboarding && follows.isEmpty()) { Onboarding({ onboarding = false; prefs.edit().putBoolean("onboarding_done", true).apply() }) ; return }
    val navLabels = listOf("[UPCOMING]", "[FOLLOWING]", "[SETTINGS]")
    Scaffold(bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { navLabels.forEachIndexed { i, label -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(if (tab == i) "> $label" else "  $label", style = MaterialTheme.typography.labelSmall) }, label = null) } } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp)); Text("audible-releases", style = MaterialTheme.typography.headlineMedium); Text("US / UPCOMING AUDIOBOOKS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); SyncLine(vm); Spacer(Modifier.height(10.dp))
            when (tab) { 0 -> UpcomingScreen(vm, { selected = it }, upcomingScrollState); 1 -> FollowingScreen(vm); else -> SettingsScreen(vm, dark, onDarkChanged) }
        }
    }
}

@Composable private fun Onboarding(done: () -> Unit) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("AUDIBLE RELEASES", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("Track upcoming audiobook releases from authors and series you follow.", style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(20.dp)); Text("1. ADD AN AUTHOR OR SERIES\n2. REFRESH THE CATALOG\n3. GET NOTIFIED WHEN RELEASES GO LIVE", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(24.dp)); Button(done, shape = SquareShape) { Text("ADD FIRST FOLLOW") } } }

@Composable private fun SyncLine(vm: AppViewModel) { val loading by vm.loading.collectAsState(); val error by vm.lastError.collectAsState(); val stamp by vm.lastRefreshAt.collectAsState(); val status = when { loading -> "○ SYNCING"; error != null -> "× OFFLINE"; else -> "● LIVE" }; val detail = stamp?.let { " · DATA LOADED ${java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" } ?: " · SOURCE AUDIBLE US"; Text(status + detail, style = MaterialTheme.typography.labelLarge, color = if (error != null) Amber else Green) }

@Composable private fun UpcomingScreen(vm: AppViewModel, onOpen: (ReleaseEntity) -> Unit, scrollState: LazyListState) {
    val items by vm.upcoming.collectAsState(); var filter by rememberSaveable { mutableStateOf("ALL") }; val today = LocalDate.now(); val filtered = items.filter { filterDate(it, filter, today) }.sortedWith(compareBy<ReleaseEntity> { releaseBucket(it, today) }.thenBy { parseDate(it.releaseDate) ?: LocalDate.MAX })
    Header(vm, "UPCOMING"); FilterRow(filter) { filter = it }; Spacer(Modifier.height(4.dp)); if (filtered.isEmpty()) EmptyState("No matching releases. Add an author or series in FOLLOWING.") else GroupedReleaseList(filtered, onOpen, scrollState)
}

private fun filterDate(item: ReleaseEntity, filter: String, today: LocalDate): Boolean { val date = parseDate(item.releaseDate) ?: return filter == "ALL"; return when (filter) { "7 DAYS" -> !date.isBefore(today) && !date.isAfter(today.plusDays(7)); "MONTH" -> date.month == today.month && date.year == today.year; "RELEASED" -> !date.isAfter(today); else -> true } }
private fun releaseBucket(item: ReleaseEntity, today: LocalDate): Int { val date = parseDate(item.releaseDate) ?: return 0; return if (date.isBefore(today)) 2 else 1 }

@Composable private fun FilterRow(selected: String, onSelect: (String) -> Unit) { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("ALL", "7 DAYS", "MONTH", "RELEASED").forEach { FilterChip(selected == it, { onSelect(it) }, label = { Text(it) }) } } }

@Composable private fun FollowingScreen(vm: AppViewModel) { var kind by rememberSaveable { mutableStateOf("author") }; var value by rememberSaveable { mutableStateOf("") }; val follows by vm.follows.collectAsState(); Text("FOLLOWING", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(kind == "author", { kind = "author" }, label = { Text("AUTHOR") }); FilterChip(kind == "series", { kind = "series" }, label = { Text("SERIES") }) }; Spacer(Modifier.height(8.dp)); OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("ADD NAME") }, singleLine = true, shape = SquareShape, trailingIcon = { IconButton({ vm.addFollow(kind, value); value = "" }) { Icon(Icons.Default.Add, "Add") } }); Spacer(Modifier.height(12.dp)); FollowSection("AUTHORS", follows.filter { it.kind == "author" }, vm); Spacer(Modifier.height(12.dp)); FollowSection("SERIES", follows.filter { it.kind == "series" }, vm) }

@Composable private fun FollowSection(title: String, items: List<Follow>, vm: AppViewModel) { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("────────────────────────────────", color = MaterialTheme.colorScheme.outline); items.forEach { item -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(item.value); TextButton({ vm.removeFollow(item) }) { Text("[×]") } } }; if (items.isEmpty()) Text("none", color = MaterialTheme.colorScheme.outline) }

@Composable private fun SettingsScreen(vm: AppViewModel, dark: Boolean, onDarkChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val follows by vm.follows.collectAsState()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.appendLine("# audible-releases follows v1")
            follows.forEach { writer.appendLine("${it.kind}\t${it.value}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2 && (parts[0] == "author" || parts[0] == "series")) vm.importFollow(parts[0], parts[1])
            }
        }
    }
    Text("SETTINGS", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(6.dp)); SettingRow("DARK TERMINAL MODE", dark, onDarkChanged); Spacer(Modifier.height(12.dp)); Text("AUTOMATIC REFRESH", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("Daily background check of Audible Coming Soon and New Releases.", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(8.dp)); Button({ vm.refresh() }, shape = SquareShape) { Text("REFRESH NOW") }; Spacer(Modifier.height(12.dp)); Text("FOLLOW LIST BACKUP", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("Export or import followed authors and series as a plain text file.", style = MaterialTheme.typography.bodyMedium); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ exportLauncher.launch("audible-follows.txt") }, shape = SquareShape) { Text("EXPORT") }; OutlinedButton({ importLauncher.launch(arrayOf("text/plain", "text/*")) }, shape = SquareShape) { Text("IMPORT") } }; Spacer(Modifier.height(12.dp)); Text("DATA SOURCE", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("Audible US public catalog pages. Local follows and release metadata are stored on this device.", style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Switch(checked, onChange) } }
@Composable private fun Header(vm: AppViewModel, title: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, style = MaterialTheme.typography.titleLarge); IconButton({ vm.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") } } }
@Composable private fun EmptyState(message: String) { Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 20.dp)) }

@Composable private fun GroupedReleaseList(items: List<ReleaseEntity>, onOpen: (ReleaseEntity) -> Unit, scrollState: LazyListState) { LazyColumn(state = scrollState, modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) { var last: LocalDate? = null; items(items, key = { it.asin }) { item -> val d = parseDate(item.releaseDate); if (d != last) { Text((d?.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")).orEmpty()).uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text("────────────────────────────────", color = MaterialTheme.colorScheme.outline); last = d }; ReleaseCard(item, onOpen) } } }

@Composable private fun ReleaseCard(item: ReleaseEntity, onOpen: (ReleaseEntity) -> Unit) { val date = parseDate(item.releaseDate); val days = date?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }; val status = when { days == null -> "DATE TBD"; days < 0 -> "RELEASED ${-days}D AGO"; days == 0L -> "RELEASED TODAY"; else -> "IN ${days}D" }; Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Column(Modifier.padding(10.dp)) { Text("+------------------------------+", color = MaterialTheme.colorScheme.outline); Text(status, style = MaterialTheme.typography.labelLarge, color = if (days != null && days <= 0) MaterialTheme.colorScheme.secondary else Amber); Text(item.title, style = MaterialTheme.typography.titleMedium); if (item.series.isNotBlank()) Text("Series: ${item.series}", color = MaterialTheme.colorScheme.primary); Text("Author: ${item.author}"); Text("Narrator: ${item.narrator.ifBlank { "Not listed" }}"); Text("Release date: ${item.releaseDate}", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(2.dp)); TextButton(contentPadding = PaddingValues(0.dp), onClick = { onOpen(item) }) { Text("SHOW MORE  >") } } } }

@Composable private fun ReleaseDetail(item: ReleaseEntity, back: () -> Unit, context: Context) {
    var showCalendar by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(back) { Text("< BACK TO RELEASES") }
            Text("RELEASE DETAIL", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(item.title, style = MaterialTheme.typography.headlineMedium)
            if (item.series.isNotBlank()) Text("Series: ${item.series}", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Author: ${item.author}\nNarrator: ${item.narrator.ifBlank { "Not listed" }}\nRelease date: ${item.releaseDate}\nASIN: ${item.asin}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Text("SYNOPSIS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(item.synopsis.ifBlank { "Synopsis not yet available from the Audible catalog listing. Open Audible for the full description." }, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.audible.com/pd/${item.asin}"))) }, shape = SquareShape) { Text("OPEN AUDIBLE") }
                OutlinedButton({ showCalendar = true }, shape = SquareShape) { Text("ADD TO CALENDAR") }
            }
            if (showCalendar) AlertDialog(onDismissRequest = { showCalendar = false }, title = { Text("ADD TO CALENDAR") }, text = { Text("${item.title}\n${item.releaseDate}\n\nThis opens your calendar app with an all-day release event.") }, confirmButton = { TextButton({ showCalendar = false; addToCalendar(context, item) }) { Text("ADD EVENT") } }, dismissButton = { TextButton({ showCalendar = false }) { Text("CANCEL") } })
        }
    }
}

private fun parseDate(raw: String): LocalDate? = try { LocalDate.parse(raw, DateTimeFormatter.ofPattern("MM-dd-yy")) } catch (_: DateTimeParseException) { null }
private fun addToCalendar(context: Context, item: ReleaseEntity) { val date = parseDate(item.releaseDate) ?: return; val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, "${item.title} — Audible release").putExtra(CalendarContract.Events.DESCRIPTION, "${item.author}\n${item.url}").putExtra(CalendarContract.Events.ALL_DAY, true).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 86_400_000L); context.startActivity(intent) }
