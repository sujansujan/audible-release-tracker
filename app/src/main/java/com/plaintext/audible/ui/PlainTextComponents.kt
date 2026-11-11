package com.plaintext.audible.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private object ImageMemoryCache {
    private val lru = LruCache<String, ImageBitmap>(90)
    fun get(url: String): ImageBitmap? = lru.get(url)
    fun put(url: String, bmp: ImageBitmap) {
        lru.put(url, bmp)
    }
}

@Composable
fun CoverThumbnail(
    url: String,
    fallbackLabel: String,
    size: Dp = 54.dp,
    isCircular: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val palette = LocalPtsPalette.current
    var bitmap by remember(url) {
        mutableStateOf(if (url.isNotBlank()) ImageMemoryCache.get(url) else null)
    }

    LaunchedEffect(url) {
        if (url.isNotBlank() && bitmap == null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 7000
                        readTimeout = 7000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (decoded != null) {
                                val img = decoded.asImageBitmap()
                                ImageMemoryCache.put(url, img)
                                bitmap = img
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        }
    }

    val shape = if (isCircular) CircleShape else RectangleShape
    val modifier = Modifier
        .size(size)
        .clip(shape)
        .background(palette.surfaceLine)
        .border(1.dp, palette.comment, shape)
        .let { m -> if (onClick != null) m.clickable { onClick() } else m }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = fallbackLabel,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            val initials = fallbackLabel
                .trim()
                .split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.take(1).uppercase() }
                .ifBlank { "BK" }
            Text(
                text = initials,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = palette.comment
            )
        }
    }
}

@Composable
fun PtsDivider() {
    val palette = LocalPtsPalette.current
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        thickness = 1.dp,
        color = palette.surfaceLine
    )
}

@Composable
fun PtsSectionBanner(
    title: String,
    rightText: String = ""
) {
    val palette = LocalPtsPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "=== $title ===",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = palette.purple,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (rightText.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = rightText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.comment
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        HorizontalDivider(thickness = 1.dp, color = palette.surfaceLine)
    }
}

@Composable
fun PtsBracketButton(
    label: String,
    selected: Boolean = false,
    color: Color? = null,
    onClick: () -> Unit
) {
    val palette = LocalPtsPalette.current
    val fgColor = when {
        selected -> palette.background
        color != null -> color
        else -> palette.cyan
    }
    val bgColor = if (selected) (color ?: palette.purple) else Color.Transparent

    Text(
        text = if (selected) "[$label]" else "[ $label ]",
        fontFamily = FontFamily.Monospace,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        fontSize = 12.sp,
        color = fgColor,
        modifier = Modifier
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 3.dp)
    )
}

@Composable
fun PtsBadge(
    label: String,
    color: Color
) {
    Text(
        text = "[$label]",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = color
    )
}
