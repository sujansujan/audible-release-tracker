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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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

private object BitmapCache {
    private val cache = LruCache<String, ImageBitmap>(80)
    fun get(url: String): ImageBitmap? = cache.get(url)
    fun put(url: String, bmp: ImageBitmap) {
        cache.put(url, bmp)
    }
}

@Composable
fun RemoteThumbnail(
    imageUrl: String?,
    fallbackInitials: String,
    size: Dp = 54.dp,
    onClick: (() -> Unit)? = null
) {
    val palette = LocalPtsColors.current
    var bitmap by remember(imageUrl) {
        mutableStateOf(imageUrl?.let { BitmapCache.get(it) })
    }

    LaunchedEffect(imageUrl) {
        if (!imageUrl.isNullOrBlank() && bitmap == null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
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
                                BitmapCache.put(imageUrl, img)
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

    val boxMod = Modifier
        .size(size)
        .background(palette.surface)
        .border(1.dp, palette.border)
        .let { m -> if (onClick != null) m.clickable { onClick() } else m }

    Box(
        modifier = boxMod,
        contentAlignment = Alignment.Center
    ) {
        val currentBmp = bitmap
        if (currentBmp != null) {
            Image(
                bitmap = currentBmp,
                contentDescription = fallbackInitials,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            Text(
                text = fallbackInitials.take(2).uppercase(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = palette.muted
            )
        }
    }
}

@Composable
fun AsciiRule(char: String = "─") {
    val palette = LocalPtsColors.current
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        thickness = 1.dp,
        color = palette.border
    )
}

@Composable
fun AsciiSectionHeader(
    title: String,
    rightLabel: String? = null
) {
    val palette = LocalPtsColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp)
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
                color = palette.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!rightLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = rightLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.muted
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        HorizontalDivider(thickness = 1.dp, color = palette.border)
    }
}

@Composable
fun BracketLink(
    label: String,
    active: Boolean = false,
    accentColor: Color? = null,
    onClick: () -> Unit
) {
    val palette = LocalPtsColors.current
    val textColor = when {
        accentColor != null -> accentColor
        active -> palette.bg
        else -> palette.accent
    }
    val bgColor = if (active) palette.accent else Color.Transparent

    Text(
        text = if (active) "[$label]" else "[ $label ]",
        fontFamily = FontFamily.Monospace,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        fontSize = 12.sp,
        color = textColor,
        modifier = Modifier
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 3.dp)
    )
}

@Composable
fun StatusTag(
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
