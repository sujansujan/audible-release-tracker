package com.plaintext.audible.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private object ThumbnailCache {
    val memory = object : LruCache<String, ImageBitmap>(60) {}
}

/**
 * Lean, zero-dependency book cover thumbnail loader with memory + disk caching
 * and a 1dp Dracula/Alucard border frame.
 */
@Composable
fun CoverThumbnail(
    coverUrl: String,
    sizeDp: Dp = 58.dp,
    modifier: Modifier = Modifier
) {
    val palette = LocalPtsPalette.current
    val context = LocalContext.current
    var imageBitmap by remember(coverUrl) {
        mutableStateOf(
            if (coverUrl.isNotBlank()) ThumbnailCache.memory.get(coverUrl) else null
        )
    }

    LaunchedEffect(coverUrl) {
        if (coverUrl.isBlank() || imageBitmap != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "covers").apply { mkdirs() }
                val cacheFile = File(cacheDir, coverUrl.hashCode().toUInt().toString(16) + ".jpg")

                val bytes = if (cacheFile.exists() && cacheFile.length() > 0) {
                    cacheFile.readBytes()
                } else {
                    val conn = (URL(coverUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 8_000
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            conn.inputStream.readBytes().also { data ->
                                runCatching { cacheFile.writeBytes(data) }
                            }
                        } else null
                    } finally {
                        conn.disconnect()
                    }
                }

                if (bytes != null && bytes.isNotEmpty()) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    if (bmp != null) {
                        ThumbnailCache.memory.put(coverUrl, bmp)
                        imageBitmap = bmp
                    }
                }
            } catch (_: Exception) {
                // Keep ASCII placeholder on error
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp)
            .background(palette.surfaceLine.copy(alpha = 0.35f))
            .border(1.dp, palette.comment)
    ) {
        val bmp = imageBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Book Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "[IMG]",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = palette.comment
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
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalPtsPalette.current
    Column(modifier = modifier.fillMaxWidth()) {
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
            .defaultMinSize(minHeight = 40.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
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
