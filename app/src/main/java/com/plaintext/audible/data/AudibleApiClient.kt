package com.plaintext.audible.data

import androidx.compose.runtime.Immutable

import android.util.LruCache
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Immutable
data class CatalogProduct(
    val asin: String,
    val title: String,
    val subtitle: String,
    val authors: List<String>,
    val narrators: List<String>,
    val seriesTitle: String,
    val seriesAsin: String,
    val seriesSequence: Int?,
    val releaseDateIso: String,
    val language: String,
    val formatType: String,
    val coverUrl: String,
    val synopsis: String,
    val runtimeMinutes: Int,
    val publisher: String
)

@Immutable
data class CacheStats(
    val fileCount: Int,
    val totalBytes: Long
) {
    val formattedSize: String
        get() {
            val kb = totalBytes / 1024.0
            return if (kb >= 1024.0) {
                "%.2f MB".format(kb / 1024.0)
            } else {
                "%.0f KB".format(kb.coerceAtLeast(0.0))
            }
        }
}

object AudibleApiClient {
    private const val BASE_URL = "https://api.audible.com/1.0/catalog/products"
    private const val RESPONSE_GROUPS =
        "contributors,product_attrs,product_desc,product_extended_attrs,series,relationships,media"
    private const val IMAGE_SIZES = "252,500"
    private const val DISK_CACHE_TTL_MS = 12L * 60L * 60L * 1000L // 12 hours

    private val memoryCache = object : LruCache<String, List<CatalogProduct>>(120) {}
    @Volatile
    private var rootCacheDir: File? = null
    @Volatile
    private var diskCacheDir: File? = null

    fun initCache(baseCacheDir: File) {
        rootCacheDir = baseCacheDir
        diskCacheDir = File(baseCacheDir, "audible_catalog_json").apply { mkdirs() }
    }

    suspend fun getCacheStats(): CacheStats = withContext(Dispatchers.IO) {
        val base = rootCacheDir ?: return@withContext CacheStats(0, 0L)
        val folders = listOf(File(base, "audible_catalog_json"), File(base, "covers"))
        var count = 0
        var bytes = 0L
        for (folder in folders) {
            folder.listFiles()?.forEach { f ->
                if (f.isFile) {
                    count++
                    bytes += f.length()
                }
            }
        }
        CacheStats(count, bytes)
    }

    suspend fun clearAllCaches(): CacheStats = withContext(Dispatchers.IO) {
        memoryCache.evictAll()
        val base = rootCacheDir
        if (base != null) {
            listOf(File(base, "audible_catalog_json"), File(base, "covers")).forEach { folder ->
                folder.listFiles()?.forEach { f -> runCatching { f.delete() } }
            }
        }
        CacheStats(0, 0L)
    }

    suspend fun searchCatalog(
        author: String? = null,
        narrator: String? = null,
        titleOrSeries: String? = null,
        asin: String? = null,
        numResults: Int = 40,
        forceRefresh: Boolean = false
    ): List<CatalogProduct> = withContext(Dispatchers.IO) {
        if (!asin.isNullOrBlank() && asin.trim().matches(Regex("^[A-Z0-9]{10}$", RegexOption.IGNORE_CASE))) {
            return@withContext fetchByAsin(asin.trim().uppercase(), forceRefresh)
        }

        val params = mutableListOf(
            "response_groups=$RESPONSE_GROUPS",
            "image_sizes=$IMAGE_SIZES",
            "products_sort_by=-ReleaseDate",
            "num_results=$numResults"
        )
        author?.takeIf { it.isNotBlank() }?.let {
            params += "author=${URLEncoder.encode(it.trim(), "UTF-8")}"
        }
        narrator?.takeIf { it.isNotBlank() }?.let {
            params += "narrator=${URLEncoder.encode(it.trim(), "UTF-8")}"
        }
        titleOrSeries?.takeIf { it.isNotBlank() }?.let {
            params += "keywords=${URLEncoder.encode(it.trim(), "UTF-8")}"
        }

        val urlStr = "$BASE_URL?${params.joinToString("&")}"
        if (!forceRefresh) {
            memoryCache.get(urlStr)?.let { return@withContext it }
        }

        val jsonStr = fetchWithDiskCache(urlStr, forceRefresh) ?: return@withContext emptyList()
        val parsed = parseProductsArray(JSONObject(jsonStr))
        if (parsed.isNotEmpty()) {
            memoryCache.put(urlStr, parsed)
        }
        parsed
    }

    private fun fetchByAsin(asin: String, forceRefresh: Boolean): List<CatalogProduct> {
        val urlStr = "$BASE_URL/$asin?response_groups=$RESPONSE_GROUPS&image_sizes=$IMAGE_SIZES"
        if (!forceRefresh) {
            memoryCache.get(urlStr)?.let { return it }
        }
        val jsonStr = fetchWithDiskCache(urlStr, forceRefresh) ?: return emptyList()
        val root = JSONObject(jsonStr)
        val prodObj = root.optJSONObject("product") ?: return emptyList()
        val parsed = listOfNotNull(parseProductObject(prodObj))
        if (parsed.isNotEmpty()) {
            memoryCache.put(urlStr, parsed)
        }
        return parsed
    }

    private fun fetchWithDiskCache(urlStr: String, forceRefresh: Boolean): String? {
        val dir = diskCacheDir
        val cacheFile = dir?.let { File(it, urlStr.hashCode().toUInt().toString(16) + ".json") }

        if (!forceRefresh && cacheFile != null && cacheFile.exists()) {
            val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
            if (ageMs in 0..DISK_CACHE_TTL_MS && cacheFile.length() > 0) {
                runCatching { return cacheFile.readText(Charsets.UTF_8) }
            }
        }

        val freshJson = executeGet(URL(urlStr))
        if (freshJson != null && cacheFile != null) {
            runCatching { cacheFile.writeText(freshJson, Charsets.UTF_8) }
        } else if (freshJson == null && cacheFile != null && cacheFile.exists()) {
            runCatching { return cacheFile.readText(Charsets.UTF_8) }
        }
        return freshJson
    }

    private fun executeGet(url: URL): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NocturneTXT/1.6 (Android)")
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseProductsArray(root: JSONObject): List<CatalogProduct> {
        val arr = root.optJSONArray("products") ?: return emptyList()
        val list = mutableListOf<CatalogProduct>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseProductObject(item)?.let { list += it }
        }
        return list
    }

    private fun parseProductObject(obj: JSONObject): CatalogProduct? {
        val asin = obj.optString("asin").takeIf { it.isNotBlank() } ?: return null
        val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return null
        val subtitle = obj.optString("subtitle", "")

        val authors = mutableListOf<String>()
        obj.optJSONArray("authors")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { authors += it }
            }
        }

        val narrators = mutableListOf<String>()
        obj.optJSONArray("narrators")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { narrators += it }
            }
        }

        var seriesTitle = ""
        var seriesAsin = ""
        var seriesSeq: Int? = null
        obj.optJSONArray("series")?.let { sArr ->
            if (sArr.length() > 0) {
                val primarySeries = sArr.optJSONObject(0)
                if (primarySeries != null) {
                    seriesTitle = primarySeries.optString("title", "")
                    seriesAsin = primarySeries.optString("asin", "")
                    val rawSeq = primarySeries.optString("sequence", "")
                    seriesSeq = rawSeq.toIntOrNull()
                }
            }
        }

        val releaseDate = obj.optString("release_date", obj.optString("issue_date", "TBA"))
            .takeIf { it.isNotBlank() } ?: "TBA"
        val language = obj.optString("language", "english")
        val formatType = obj.optString("format_type", "unabridged")

        var coverUrl = ""
        obj.optJSONObject("product_images")?.let { imgObj ->
            coverUrl = imgObj.optString("252").ifBlank { imgObj.optString("500") }
        }

        val rawHtmlSummary = obj.optString("publisher_summary")
            .ifBlank { obj.optString("merchandising_summary", "") }
        val cleanSynopsis = if (rawHtmlSummary.isNotBlank()) {
            HtmlCompat.fromHtml(rawHtmlSummary, HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString()
                .replace(Regex("\\s+"), " ")
                .trim()
        } else {
            ""
        }

        val runtimeMinutes = obj.optInt("runtime_length_min", 0)
        val publisher = obj.optString("publisher_name", "")

        return CatalogProduct(
            asin = asin,
            title = title,
            subtitle = subtitle,
            authors = authors,
            narrators = narrators,
            seriesTitle = seriesTitle,
            seriesAsin = seriesAsin,
            seriesSequence = seriesSeq,
            releaseDateIso = releaseDate,
            language = language,
            formatType = formatType,
            coverUrl = coverUrl,
            synopsis = cleanSynopsis,
            runtimeMinutes = runtimeMinutes,
            publisher = publisher
        )
    }
}
