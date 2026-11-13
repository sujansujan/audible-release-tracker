package com.plaintext.audible.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

object ThumbnailCache {
    val memory = object : LruCache<String, ImageBitmap>(200) {}
    fun clear() {
        memory.evictAll()
    }
}

val StandardThumbnailSize = 56.dp

private const val MONO_FONT_FEATURES = "liga 0, calt 0"

@Immutable
data class AsciiGridMetrics(
    val cols: Int,
    val cellWidthPx: Int
)

@Composable
fun BoxWithConstraintsScope.rememberAsciiMetrics(
    fontSizeSp: Int = 12,
    bold: Boolean = false
): AsciiGridMetrics {
    val tm = rememberTextMeasurer()
    val style = remember(fontSizeSp, bold) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFeatureSettings = MONO_FONT_FEATURES
        )
    }
    val cellPx = remember(style) {
        tm.measure("M", style).size.width.coerceAtLeast(1)
    }
    val cols = (constraints.maxWidth / cellPx).coerceAtLeast(16)
    return AsciiGridMetrics(cols = cols, cellWidthPx = cellPx)
}

@Composable
fun BoxWithConstraintsScope.rememberAsciiCols(
    fontSizeSp: Int = 12,
    bold: Boolean = false
): Int = rememberAsciiMetrics(fontSizeSp, bold).cols

@Composable
fun rememberAsciiSpinner(active: Boolean): String {
    val context = LocalContext.current
    val animate = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }.getOrDefault(true)
    }
    val frames = remember { arrayOf("|", "/", "-", "\\") }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(active, animate) {
        if (active && animate) {
            while (true) {
                delay(120L)
                idx = (idx + 1) % frames.size
            }
        } else {
            idx = 0
        }
    }
    return if (active) frames[idx] else "*"
}

fun asciiProgressBar(done: Int, total: Int, cells: Int = 8): String {
    if (total <= 0) return "[........]"
    val ratio = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val filled = (ratio * cells).roundToInt().coerceIn(0, cells)
    return "[" + "#".repeat(filled) + "-".repeat(cells - filled) + "] $done/$total"
}

@Composable
fun CoverThumbnail(
    coverUrl: String,
    sizeDp: Dp = StandardThumbnailSize,
    fallbackText: String = "[IMG]",
    borderColor: Color = LocalPtsPalette.current.comment,
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    val context = LocalContext.current
    var imageBitmap by remember(coverUrl) {
        mutableStateOf(
            if (coverUrl.isNotBlank()) ThumbnailCache.memory.get(coverUrl) else null
        )
    }

    if (coverUrl.isNotBlank() && imageBitmap == null) {
        LaunchedEffect(coverUrl) {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val cacheDir = File(context.cacheDir, "covers").apply { mkdirs() }
                    val cacheFile = File(cacheDir, coverUrl.hashCode().toUInt().toString(16) + ".jpg")

                    if (cacheFile.exists() && cacheFile.length() > 0) {
                        val bytes = cacheFile.readBytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } else {
                        val conn = (URL(coverUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8_000
                            readTimeout = 8_000
                            setRequestProperty("User-Agent", "NocturneTXT/3.0 (Android)")
                        }
                        try {
                            if (conn.responseCode in 200..299) {
                                val rawBytes = conn.inputStream.readBytes()
                                val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                                if (decoded != null) {
                                    val scaled = if (decoded.width > 160 || decoded.height > 160) {
                                        Bitmap.createScaledBitmap(decoded, 160, 160, true)
                                    } else {
                                        decoded
                                    }
                                    runCatching {
                                        val out = ByteArrayOutputStream()
                                        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                        cacheFile.writeBytes(out.toByteArray())
                                    }
                                    scaled.asImageBitmap()
                                } else null
                            } else null
                        } finally {
                            conn.disconnect()
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (loaded != null) {
                ThumbnailCache.memory.put(coverUrl, loaded)
                imageBitmap = loaded
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp)
            .background(palette.surfaceLine.copy(alpha = 0.35f))
            .border(1.dp, borderColor)
    ) {
        val bmp = imageBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = fallbackText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = borderColor,
                style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
            )
        }
    }
}

/**
 * Full-width column-measured ASCII section banner:
 * `+=== NOVEMBER 2026 (1 RELEASE • 24H 10M) =========+`
 */
@Composable
fun AsciiSectionHeader(
    title: String,
    color: Color = LocalPtsPalette.current.purple,
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(vertical = 4.dp)
    ) {
        val cols = rememberAsciiCols(fontSizeSp = 12, bold = true)
        val clean = title.uppercase().trim()
        val prefix = "+=== $clean "
        val padCount = (cols - prefix.length - 1).coerceAtLeast(3)
        val line = prefix + "=".repeat(padCount) + "+"

        Text(
            text = line,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
        )
    }
}

/**
 * Full-width column-measured ASCII character rule (`- - - - - -`)
 * replacing Material HorizontalDivider inside cards and sections.
 */
@Composable
fun AsciiRule(
    pattern: String = "- ",
    color: Color = LocalPtsPalette.current.surfaceLine,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cols = rememberAsciiCols(fontSizeSp = 11, bold = false)
        val safePattern = pattern.ifEmpty { "-" }
        val repeatTimes = (cols / safePattern.length).coerceAtLeast(1)
        val line = safePattern.repeat(repeatTimes + 1).take(cols)
        Text(
            text = line,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = color,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
        )
    }
}

/**
 * Column-measured dotted-leader key/value row:
 * `Runtime ................................. 24h 15m`
 */
@Composable
fun AsciiLeaderRow(
    key: String,
    value: String,
    keyColor: Color = LocalPtsPalette.current.comment,
    valueColor: Color = LocalPtsPalette.current.foreground,
    fontSizeSp: Int = 12,
    boldValue: Boolean = true,
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$key: $value" }
    ) {
        val cols = rememberAsciiCols(fontSizeSp = fontSizeSp, bold = boldValue)
        val cleanKey = key.trim()
        val cleanVal = value.trim()
        val maxValLen = (cols - cleanKey.length - 4).coerceAtLeast(6)
        val displayVal = if (cleanVal.length > maxValLen) {
            cleanVal.take((maxValLen - 2).coerceAtLeast(4)) + ".."
        } else {
            cleanVal
        }
        val dotCount = (cols - cleanKey.length - displayVal.length - 2).coerceAtLeast(2)
        val dots = ".".repeat(dotCount)

        val annotated = buildAnnotatedString {
            withStyle(SpanStyle(color = keyColor, fontWeight = FontWeight.Normal)) {
                append(cleanKey)
            }
            withStyle(SpanStyle(color = palette.comment.copy(alpha = 0.55f), fontWeight = FontWeight.Normal)) {
                append(" $dots ")
            }
            withStyle(
                SpanStyle(
                    color = valueColor,
                    fontWeight = if (boldValue) FontWeight.Bold else FontWeight.Normal
                )
            ) {
                append(displayVal)
            }
        }

        Text(
            text = annotated,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = TextStyle(
                fontFeatureSettings = MONO_FONT_FEATURES,
                lineHeight = (fontSizeSp + 5).sp
            )
        )
    }
}

/**
 * Full-width dotted-leader interactive boolean switch row for Settings:
 * `English Language Only ................... [x] on `
 */
@Composable
fun AsciiToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val stateToken = if (checked) "[x] on " else "[ ] off"
    val stateColor = if (checked) palette.green else palette.comment
    val rowBg = if (isPressed) palette.surfaceLine.copy(alpha = 0.55f) else Color.Transparent

    BoxWithConstraints(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 38.dp)
            .background(rowBg)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Checkbox,
                onClick = onToggle
            )
            .padding(vertical = 3.dp)
            .clearAndSetSemantics {
                contentDescription = "$label, ${if (checked) "on" else "off"}"
            }
    ) {
        val cols = rememberAsciiCols(fontSizeSp = 12, bold = false)
        val cleanLabel = label.trim()
        val dotCount = (cols - cleanLabel.length - stateToken.length - 2).coerceAtLeast(2)
        val dots = ".".repeat(dotCount)

        val annotated = buildAnnotatedString {
            withStyle(SpanStyle(color = palette.foreground, fontWeight = FontWeight.Normal)) {
                append(cleanLabel)
            }
            withStyle(SpanStyle(color = palette.comment.copy(alpha = 0.55f), fontWeight = FontWeight.Normal)) {
                append(" $dots ")
            }
            withStyle(SpanStyle(color = stateColor, fontWeight = FontWeight.Bold)) {
                append(stateToken)
            }
        }

        Text(
            text = annotated,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
        )
    }
}

/**
 * Interactive ASCII Radio Option (`(*) LABEL` / `( ) LABEL`) with inverse press state.
 */
@Composable
fun AsciiRadioButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = LocalPtsPalette.current.cyan
) {
    val palette = LocalPtsPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val ink = if (selected) activeColor else palette.comment
    val prefix = if (selected) "(*)" else "( )"
    val text = "$prefix $label"

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .background(if (isPressed) ink else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .clearAndSetSemantics {
                contentDescription = "$label, ${if (selected) "selected" else "not selected"}"
            }
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (isPressed) palette.background else ink,
            style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
        )
    }
}

@Composable
fun AsciiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) palette.cyan else palette.comment

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surfaceLine.copy(alpha = 0.25f))
            .border(1.dp, borderColor)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Text(
            text = "> ",
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isFocused) palette.cyan else palette.comment,
            style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = palette.comment.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onSearch = { onImeAction() },
                    onDone = { onImeAction() }
                ),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = palette.foreground,
                    fontFeatureSettings = MONO_FONT_FEATURES
                ),
                cursorBrush = SolidColor(palette.cyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused }
            )
        }
    }
}

/**
 * Single-frame ASCII box (`+---[ HEADER ]---+` top border, aligned vertical side rails,
 * and `+----------------+` bottom border) with NO redundant inner Surface top/bottom rectangle.
 */
@Composable
fun AsciiBox(
    modifier: Modifier = Modifier,
    headerLabel: String? = null,
    borderColor: Color = LocalPtsPalette.current.comment,
    headerColor: Color = LocalPtsPalette.current.purple,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalPtsPalette.current
    val density = LocalDensity.current
    val clickableModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }

    BoxWithConstraints(
        modifier = clickableModifier.background(palette.background)
    ) {
        val metrics = rememberAsciiMetrics(fontSizeSp = 12, bold = true)
        val cols = metrics.cols
        val cellPx = metrics.cellWidthPx

        val topLine = remember(headerLabel, cols) {
            if (!headerLabel.isNullOrBlank()) {
                val cleanHeader = headerLabel.trim()
                val prefix = "+---[ $cleanHeader ]"
                val padCount = (cols - prefix.length - 1).coerceAtLeast(2)
                prefix + "-".repeat(padCount) + "+"
            } else {
                "+" + "-".repeat((cols - 2).coerceAtLeast(2)) + "+"
            }
        }
        val bottomLine = remember(cols) {
            "+" + "-".repeat((cols - 2).coerceAtLeast(2)) + "+"
        }

        // Exact pixel X centers of the leading '+' and trailing '+'
        val leftRailX = cellPx * 0.5f
        val rightRailX = (cols - 0.5f) * cellPx

        Column(modifier = Modifier.fillMaxWidth()) {
            if (!headerLabel.isNullOrBlank()) {
                val cleanHeader = headerLabel.trim()
                val bracketTitle = "[ $cleanHeader ]"
                val dashRightCount = (cols - 4 - bracketTitle.length - 1).coerceAtLeast(2)
                val annotatedTop = buildAnnotatedString {
                    withStyle(SpanStyle(color = borderColor, fontWeight = FontWeight.Bold)) {
                        append("+---")
                    }
                    withStyle(SpanStyle(color = headerColor, fontWeight = FontWeight.Bold)) {
                        append(bracketTitle)
                    }
                    withStyle(SpanStyle(color = borderColor, fontWeight = FontWeight.Bold)) {
                        append("-".repeat(dashRightCount) + "+")
                    }
                }
                Text(
                    text = annotatedTop,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = TextStyle(
                        fontFeatureSettings = MONO_FONT_FEATURES,
                        lineHeight = 14.sp
                    )
                )
            } else {
                Text(
                    text = topLine,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = borderColor,
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    style = TextStyle(
                        fontFeatureSettings = MONO_FONT_FEATURES,
                        lineHeight = 14.sp
                    )
                )
            }

            // Body with ONLY left and right vertical rails aligned directly under the '+' corners
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val strokePx = with(density) { 1.dp.toPx() }
                        val rx = rightRailX.coerceAtMost(size.width - strokePx)
                        drawLine(
                            color = borderColor,
                            start = Offset(leftRailX, 0f),
                            end = Offset(leftRailX, size.height),
                            strokeWidth = strokePx
                        )
                        drawLine(
                            color = borderColor,
                            start = Offset(rx, 0f),
                            end = Offset(rx, size.height),
                            strokeWidth = strokePx
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                content = content
            )

            Text(
                text = bottomLine,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = borderColor,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                style = TextStyle(
                    fontFeatureSettings = MONO_FONT_FEATURES,
                    lineHeight = 14.sp
                )
            )
        }
    }
}

/**
 * Interactive ASCII bracket button with inverse-video press feedback (`bg = ink, fg = bg`)
 * and `[x] label` or `[ label ]` formatting.
 */
@Composable
fun BracketButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalPtsPalette.current.cyan,
    active: Boolean = false
) {
    val palette = LocalPtsPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val ink = if (active) palette.green else color
    val text = when {
        label.startsWith("[x]") || label.startsWith("[+") || label.startsWith("[ ]") -> label
        active -> "[x] $label"
        else -> "[ $label ]"
    }

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .background(if (isPressed) ink else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = label }
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (active || isPressed) FontWeight.Bold else FontWeight.Medium,
            color = if (isPressed) palette.background else ink,
            style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
        )
    }
}

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPtsPalette.current.foreground,
    fontSizeSp: Int = 14,
    bold: Boolean = false
) {
    Text(
        text = text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSizeSp.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
        lineHeight = (fontSizeSp + 5).sp,
        style = TextStyle(fontFeatureSettings = MONO_FONT_FEATURES)
    )
}
