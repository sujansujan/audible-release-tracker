package com.plaintext.audible.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ThumbnailCache {
    val memory = object : LruCache<String, ImageBitmap>(200) {}
    fun clear() {
        memory.evictAll()
    }
}

val StandardThumbnailSize = 56.dp

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
                            setRequestProperty("User-Agent", "NocturneTXT/2.8 (Android)")
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
                color = borderColor
            )
        }
    }
}

@Composable
fun AsciiSectionHeader(
    title: String,
    color: Color = LocalPtsPalette.current.purple,
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(vertical = 4.dp)
    ) {
        MonoText(
            text = "+=== ${title.uppercase()} ===+",
            color = color,
            fontSizeSp = 13,
            bold = true
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
            color = if (isFocused) palette.cyan else palette.comment
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = palette.comment.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                    color = palette.foreground
                ),
                cursorBrush = SolidColor(palette.cyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused }
            )
        }
    }
}

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
    val clickableModifier = if (onClick != null) {
        modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Column(modifier = clickableModifier) {
        if (!headerLabel.isNullOrBlank()) {
            Text(
                text = "+---[ $headerLabel ]---+",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = headerColor,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
        Surface(
            color = palette.background,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                content = content
            )
        }
    }
}

@Composable
fun BracketButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalPtsPalette.current.cyan,
    active: Boolean = false
) {
    val palette = LocalPtsPalette.current
    val text = if (active) "[*$label*]" else "[ $label ]"
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) palette.green else color
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
        lineHeight = (fontSizeSp + 5).sp
    )
}
