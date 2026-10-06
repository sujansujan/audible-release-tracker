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
import androidx.compose.foundation.text.selection.SelectionContainer
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
private val DraculaGreen = Color(0xFF50FA7B)
private val AlucardGreen = Color(0xFF006B3C)
private val Amber = Color(0xFF9A5700)
private val SquareShape = RoundedCornerShape(0.dp)

@Composable private fun PlainTextTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(background = DraculaBackground, surface = DraculaBackground, surfaceVariant = Color(0xFF44475A), primary = DraculaLink, secondary = DraculaGreen, onBackground = DraculaInk, onSurface = DraculaInk, outline = DraculaRule) else lightColorScheme(background = AlucardBackground, surface = AlucardBackground, surfaceVariant = Color(0xFFEDEDE7), primary = AlucardLink, secondary = AlucardGreen, onBackground = AlucardInk, onSurface = AlucardInk, outline = AlucardRule)
    MaterialTheme(colorScheme = scheme, typography = Typography().run { copy(headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Monospace), titleLarge = titleLarge.copy(fontFamily = FontFamily.Monospace), titleMedium = titleMedium.copy(fontFamily = FontFamily.Monospace), bodyLarge = bodyLarge.copy(fontFamily = FontFamily.Monospace), bodyMedium = bodyMedium.copy(fontFamily = FontFamily.Monospace), labelLarge = labelLarge.copy(fontFamily = FontFamily.Monospace), labelSmall = labelSmall.copy(fontFamily = FontFamily.Monospace)) }, shapes = Shapes(extraSmall = SquareShape, small = SquareShape, medium = SquareShape, large = SquareShape, extraLarge = SquareShape), content = content)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); scheduleSync(); setContent { val dark = remember { mutableStateOf(getPreferences(0).getBoolean("dark_mode", false)) }; PlainTextTheme(dark.value) { AudibleApp(dark.value, onDarkChanged = { value -> dark.value = value; getPreferences(0).edit().putBoolean("dark_mode", value).apply() }) } } }
    private fun scheduleSync() { WorkManager.getInstance(this).enqueueUniquePeriodicWork("audible-release-sync", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<com.example.audiblereleases.worker.ReleaseSyncWorker>(1, TimeUnit.DAYS).build()) }
}

@Composable private fun AudibleApp(dark: Boolean, onDarkChanged: (Boolean) -> Unit, vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<ReleaseEntity?>(null) }
    val upcomingScrollState = rememberLazyListState()
    val follows by vm.follows.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val prefs = remember { context.getSharedPreferences("audible-releases", Context.MODE_PRIVATE) }
    var onboarding by remember { mutableStateOf(!prefs.getBoolean("onboarding_done", false)) }
    LaunchedEffect(Unit) { vm.refresh(); if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    if (selected != null) { BackHandler { selected = null }; SelectionContainer { ReleaseDetail(selected!!, { selected = null }, context) } }
    else if (onboarding && follows.isEmpty()) { SelectionContainer { Onboarding { onboarding = false; prefs.edit().putBoolean("onboarding_done", true).apply() } } }
    else Scaffold(bottomBar = { TextNavigation(listOf("SORT", "FOLLOWING", "SETTINGS"), tab) { tab = it } }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
                when (tab) { 0 -> UpcomingScreen(vm, follows, { selected = it }, upcomingScrollState, context); 1 -> SelectionContainer { FollowingScreen(vm) }; else -> SelectionContainer { SettingsScreen(vm, dark, onDarkChanged) } }
            }
    }

@Composable private fun TextNavigation(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) { Column(Modifier.fillMaxWidth()) { Text("────────────────────────────────────────────────", color = MaterialTheme.colorScheme.outline); Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { labels.forEachIndexed { index, label -> TextButton(onClick = { onSelect(index) }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text(if (selected == index) "> $label" else "  $label", style = MaterialTheme.typography.labelSmall, color = if (selected == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } } } } }

@Composable private fun Onboarding(done: () -> Unit) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("AUDIBLE RELEASES", style = MaterialTheme.typography.headlineMedium); Text("────────────────────────", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(12.dp)); Text("A plain-text release tracker for authors and series you follow.", style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(20.dp)); Text("> FOLLOWING  /  ADD AUTHOR OR SERIES\n> UPCOMING   /  REVIEW RELEASES\n> DETAILS    /  READ SYNOPSIS\n> CALENDAR   /  SAVE RELEASE DATE", style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(24.dp)); Button(done, shape = SquareShape) { Text("> ADD FIRST FOLLOW") } } }

@Composable private fun SyncLine(vm: AppViewModel) { val loading by vm.loading.collectAsState(); val error by vm.lastError.collectAsState(); val stamp by vm.lastRefreshAt.collectAsState(); val status = when { loading -> "SYNCING AUDIBLE US..."; error != null -> "OFFLINE: ${error ?: "UNKNOWN ERROR"}"; else -> "SYNC READY" }; val detail = stamp?.let { "  ·  LAST SYNC ${DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}" } ?: "  ·  NO SYNC YET"; Text(status + detail, style = MaterialTheme.typography.labelSmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) }

@Composable private fun UpcomingScreen(vm: AppViewModel, follows: List<Follow>, onOpen: (ReleaseEntity) -> Unit, scrollState: LazyListState, context: Context) {
    val items by vm.upcoming.collectAsState(); var filter by rememberSaveable { mutableStateOf("UPCOMING") }; val today = LocalDate.now(); val filtered = items.filter { filterDate(it, filter, today) }.sortedWith(compareBy<ReleaseEntity> { releaseBucket(it, today) }.thenBy { parseDate(it.releaseDate) ?: LocalDate.MAX }); val futureCount = items.count { parseDate(it.releaseDate)?.isAfter(today) == true || parseDate(it.releaseDate) == today }; val releasedCount = items.count { parseDate(it.releaseDate)?.isBefore(today) == true }
    Text("$futureCount UPCOMING  ·  $releasedCount RELEASED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(6.dp)); FilterRow(filter) { filter = it }; Spacer(Modifier.height(6.dp)); if (filtered.isEmpty()) EmptyState() else GroupedReleaseList(filtered, follows, onOpen, scrollState, context)
}

private fun filterDate(item: ReleaseEntity, filter: String, today: LocalDate): Boolean { val date = parseDate(item.releaseDate) ?: return false; return when (filter) { "UPCOMING" -> !date.isBefore(today); "7 DAYS" -> !date.isBefore(today) && !date.isAfter(today.plusDays(7)); "MONTH" -> date.month == today.month && date.year == today.year && !date.isBefore(today); "RELEASED" -> !date.isAfter(today); else -> true } }
private fun releaseBucket(item: ReleaseEntity, today: LocalDate): Int { val date = parseDate(item.releaseDate) ?: return 0; return if (date.isBefore(today)) 2 else if (date.isAfter(today)) 1 else 0 }

@Composable private fun FilterRow(selected: String, onSelect: (String) -> Unit) { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("UPCOMING", "7 DAYS", "MONTH", "RELEASED", "ALL").forEach { FilterChip(selected == it, { onSelect(it) }, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) } } }

@Composable private fun FollowingScreen(vm: AppViewModel) { var kind by rememberSaveable { mutableStateOf("author") }; var value by rememberSaveable { mutableStateOf("") }; val follows by vm.follows.collectAsState(); Text("FOLLOWING", style = MaterialTheme.typography.titleLarge); Text("────────────────────────────────", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(6.dp)); Text("AUTHORS (${follows.count { it.kind == "author" }})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("SERIES (${follows.count { it.kind == "series" }})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp)); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(kind == "author", { kind = "author" }, label = { Text("AUTHOR") }); FilterChip(kind == "series", { kind = "series" }, label = { Text("SERIES") }) }; OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text("> ADD ${kind.uppercase()}") }, singleLine = true, shape = SquareShape, trailingIcon = { TextButton({ vm.addFollow(kind, value); value = "" }) { Text("[ADD]") } }); Spacer(Modifier.height(12.dp)); FollowSection("AUTHORS", follows.filter { it.kind == "author" }, vm); Spacer(Modifier.height(12.dp)); FollowSection("SERIES", follows.filter { it.kind == "series" }, vm) }

@Composable private fun FollowSection(title: String, items: List<Follow>, vm: AppViewModel) { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("--------------------------------", color = MaterialTheme.colorScheme.outline); if (items.isEmpty()) Text("none  /  use ADD above", color = MaterialTheme.colorScheme.outline) else items.forEach { item -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("> ${item.value}", modifier = Modifier.weight(1f)); TextButton({ vm.removeFollow(item) }, contentPadding = PaddingValues(0.dp)) { Text("[REMOVE]") } } } }

@Composable private fun SettingsScreen(vm: AppViewModel, dark: Boolean, onDarkChanged: (Boolean) -> Unit) { val context = LocalContext.current; val prefs = remember { context.getSharedPreferences("audible-releases", Context.MODE_PRIVATE) }; var dayBefore by remember { mutableStateOf(prefs.getBoolean("notify_day_before", false)) }; var weekBefore by remember { mutableStateOf(prefs.getBoolean("notify_week_before", false)) }; val follows by vm.follows.collectAsState(); val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer -> writer.appendLine("# audible-releases follows v1"); follows.forEach { writer.appendLine("${it.kind}\t${it.value}") } } }; val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line -> val parts = line.split("\t", limit = 2); if (parts.size == 2 && (parts[0] == "author" || parts[0] == "series")) vm.importFollow(parts[0], parts[1]) } } }
    Text("SETTINGS", style = MaterialTheme.typography.titleLarge); Text("────────────────────────────────", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(8.dp)); Text("THEME", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text(if (dark) "> DRACULA" else "> ALUCARD"); SettingRow("DARK TERMINAL MODE", dark, onDarkChanged); Spacer(Modifier.height(10.dp)); Text("REFRESH", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("> AUTOMATIC: DAILY", style = MaterialTheme.typography.bodyMedium); Button({ vm.refresh() }, shape = SquareShape) { Text("> REFRESH NOW") }; Spacer(Modifier.height(10.dp)); Text("NOTIFICATIONS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); SettingRow("ON RELEASE DAY", true) {}; SettingRow("ONE DAY BEFORE", dayBefore) { dayBefore = it; prefs.edit().putBoolean("notify_day_before", it).apply() }; SettingRow("SEVEN DAYS BEFORE", weekBefore) { weekBefore = it; prefs.edit().putBoolean("notify_week_before", it).apply() }; Spacer(Modifier.height(10.dp)); Text("FOLLOW LIST", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ exportLauncher.launch("audible-follows.txt") }, shape = SquareShape) { Text("[EXPORT]") }; OutlinedButton({ importLauncher.launch(arrayOf("text/plain", "text/*")) }, shape = SquareShape) { Text("[IMPORT]") } }; Spacer(Modifier.height(10.dp)); Text("DATA SOURCE", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("PUBLIC CATALOG API: US EDITION", style = MaterialTheme.typography.bodyMedium) }

@Composable private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) { TextButton({ onChange(!checked) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 2.dp, horizontal = 0.dp)) { Text(if (checked) "[x] $label" else "[ ] $label", modifier = Modifier.fillMaxWidth()) } }
@Composable private fun Header(vm: AppViewModel, title: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, style = MaterialTheme.typography.titleLarge); TextButton({ vm.refresh() }, contentPadding = PaddingValues(0.dp)) { Text("[REFRESH]") } } }
@Composable private fun EmptyState() { Text("UPCOMING IS EMPTY\n\nNo releases match your followed authors or series.\n\n> FOLLOWING  /  ADD AUTHOR\n> FOLLOWING  /  ADD SERIES\n> SETTINGS   /  REFRESH NOW", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 20.dp)) }

@Composable private fun GroupedReleaseList(items: List<ReleaseEntity>, follows: List<Follow>, onOpen: (ReleaseEntity) -> Unit, scrollState: LazyListState, context: Context) { LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp), userScrollEnabled = true) { var lastGroup = ""; items(items, key = { it.asin }, contentType = { "release-card" }) { item -> val group = releaseGroup(item); if (group != lastGroup) { Text(group, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text("────────────────────────────────", color = MaterialTheme.colorScheme.outline); lastGroup = group }; ReleaseCard(item, follows, onOpen, context) } } }

private fun releaseGroup(item: ReleaseEntity): String { val date = parseDate(item.releaseDate) ?: return "DATE TBD"; val today = LocalDate.now(); return when { date == today -> "TODAY"; date.isAfter(today) && !date.isAfter(today.plusDays(7)) -> "NEXT 7 DAYS"; date.isAfter(today) -> "LATER"; else -> "RELEASED" } }

@Composable private fun ReleaseCard(item: ReleaseEntity, follows: List<Follow>, onOpen: (ReleaseEntity) -> Unit, context: Context) { val date = parseDate(item.releaseDate); val days = date?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }; val status = when { days == null -> "[DATE TBD]"; days < 0 -> "[RELEASED ${-days}D AGO]"; days == 0L -> "[RELEASED TODAY]"; else -> "[IN ${days}D]" }; val statusColor = if (days != null && days <= 0) MaterialTheme.colorScheme.secondary else Amber; Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { SelectionContainer { Column(Modifier.padding(10.dp)) { Text(status, style = MaterialTheme.typography.labelLarge, color = statusColor); Text(item.title, style = MaterialTheme.typography.titleMedium); Text("MATCHED BY: ${matchReason(item, follows)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); MetaLine("Series", item.series.ifBlank { "Standalone" }); MetaLine("Author", item.author.ifBlank { "Not listed" }); MetaLine("Narrator", item.narrator.ifBlank { "Not listed" }); MetaLine("Release", item.releaseDate); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton({ onOpen(item) }, contentPadding = PaddingValues(0.dp)) { Text("> DETAILS") }; TextButton({ addToCalendar(context, item) }, contentPadding = PaddingValues(0.dp)) { Text("> CALENDAR") } } } } } }
@Composable private fun MetaLine(label: String, value: String) { Row(Modifier.fillMaxWidth()) { Text(label.padEnd(9, ' '), color = MaterialTheme.colorScheme.primary); Text(value) } }
private fun matchReason(item: ReleaseEntity, follows: List<Follow>): String { val author = follows.any { it.kind == "author" && normalizeUi(it.value).let { f -> normalizeUi(item.author).contains(f) || f.contains(normalizeUi(item.author)) } }; val series = follows.any { it.kind == "series" && normalizeUi(it.value).let { f -> normalizeUi(item.series).contains(f) || f.contains(normalizeUi(item.series)) } }; return when { author && series -> "AUTHOR + SERIES"; author -> "AUTHOR"; series -> "SERIES"; else -> "CATALOG" } }
private fun normalizeUi(value: String) = value.lowercase().replace("&", "and").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

@Composable private fun ReleaseDetail(item: ReleaseEntity, back: () -> Unit, context: Context) { var showCalendar by remember { mutableStateOf(false) }; Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize().padding(16.dp)) { TextButton(back, contentPadding = PaddingValues(0.dp)) { Text("< BACK TO RELEASES") }; Text(item.title, style = MaterialTheme.typography.headlineMedium); Text("────────────────────────────────", color = MaterialTheme.colorScheme.outline); Spacer(Modifier.height(8.dp)); if (item.series.isNotBlank()) Text("Series: ${item.series}", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(10.dp)); Text("Author: ${item.author}\nNarrator: ${item.narrator.ifBlank { "Not listed" }}\nRelease date: ${item.releaseDate}\nASIN: ${item.asin}", style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(14.dp)); Text("SYNOPSIS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text(item.synopsis.ifBlank { "Synopsis not yet available from the Audible catalog listing." }, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(14.dp)); TextButton({ showCalendar = true }, contentPadding = PaddingValues(0.dp)) { Text("> ADD TO CALENDAR") }; if (showCalendar) AlertDialog(onDismissRequest = { showCalendar = false }, title = { Text("ADD TO CALENDAR") }, text = { Text("${item.title}\n${item.releaseDate}\n\nSave an all-day release event?") }, confirmButton = { TextButton({ showCalendar = false; addToCalendar(context, item) }) { Text("ADD EVENT") } }, dismissButton = { TextButton({ showCalendar = false }) { Text("CANCEL") } }) } } }

private fun parseDate(raw: String): LocalDate? = try { LocalDate.parse(raw, DateTimeFormatter.ofPattern("MM-dd-yy")) } catch (_: DateTimeParseException) { null }
private fun addToCalendar(context: Context, item: ReleaseEntity) { val date = parseDate(item.releaseDate) ?: return; val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(); context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, "${item.title} — Audible release").putExtra(CalendarContract.Events.DESCRIPTION, "${item.author}\nhttps://www.audible.com/pd/${item.asin}").putExtra(CalendarContract.Events.ALL_DAY, true).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 86_400_000L)) }
