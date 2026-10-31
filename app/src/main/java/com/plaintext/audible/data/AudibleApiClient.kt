package com.plaintext.audible.data

import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.GZIPInputStream

@Immutable
data class CatalogProduct(
    val asin: String,
    val title: String,
    val subtitle: String,
    val authors: List<String>,
    val primaryAuthorAsin: String,
    val narrators: List<String>,
    val seriesTitle: String,
    val seriesAsin: String,
    val seriesSequence: Int?,
    val releaseDateIso: String,
    val language: String,
    val formatType: String,
    val contentDeliveryType: String,
    val contentType: String,
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
    private const val DISK_CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val CACHE_DIR_NAME = "audible_catalog_json_v19"

    private val memoryCache = object : LruCache<String, List<CatalogProduct>>(120) {}
    private val personPortraitCache = object : LruCache<String, String>(160) {}

    private val roleSuffixRegex = Regex(
        "(\\s*[-–—,/]\\s*(translator|translated by|editor|edited by|foreword|foreword by|afterword|introduction|adapter|adapted by|illustrator|compiler|narrator|author|contributor))+\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val roleParenRegex = Regex(
        "\\s*\\((translator|translated by|editor|edited by|foreword|afterword|introduction|adapter|illustrator|compiler)\\)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val secondaryContributorRoleRegex = Regex(
        "\\b(translator|translated by|editor|edited by|foreword|afterword|introduction|illustrator|adapter)\\b",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    private var rootCacheDir: File? = null
    @Volatile
    private var diskCacheDir: File? = null

    fun initCache(baseCacheDir: File) {
        rootCacheDir = baseCacheDir
        diskCacheDir = File(baseCacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        // Clean up legacy v1.7/v1.8 JSON cache folder if present so fresh fields are parsed
        runCatching {
            File(baseCacheDir, "audible_catalog_json").listFiles()?.forEach { it.delete() }
        }
    }

    fun cleanContributorName(rawName: String): String {
        return rawName
            .replace(roleParenRegex, "")
            .replace(roleSuffixRegex, "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isSecondaryContributorRole(rawName: String): Boolean {
        return secondaryContributorRoleRegex.containsMatchIn(rawName)
    }

    fun formatCanonicalName(raw: String): String {
        val cleaned = cleanContributorName(raw)
        if (cleaned.isBlank()) return raw.trim()
        // If user typed all lowercase or mixed lowercase (e.g., "andy weir", "Brandon sanderson"), normalize to Title Case
        return cleaned.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word
            else if (word.length <= 3 && word.all { it.isUpperCase() || it == '.' }) word
            else word.lowercase(Locale.US).replaceFirstChar { ch -> ch.titlecase(Locale.US) }
        }
    }

    suspend fun getCacheStats(): CacheStats = withContext(Dispatchers.IO) {
        val base = rootCacheDir ?: return@withContext CacheStats(0, 0L)
        val folders = listOf(File(base, CACHE_DIR_NAME), File(base, "covers"))
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
        personPortraitCache.evictAll()
        val base = rootCacheDir
        if (base != null) {
            listOf(
                File(base, CACHE_DIR_NAME),
                File(base, "audible_catalog_json"),
                File(base, "covers")
            ).forEach { folder ->
                folder.listFiles()?.forEach { f -> runCatching { f.delete() } }
            }
        }
        CacheStats(0, 0L)
    }

    /**
     * Resolves the exact author thumbnail from their official Audible author page
     * (https://www.audible.com/author/{authorAsin}) via Audnexus and direct Audible author page scraping.
     */
    suspend fun fetchAudibleAuthorImageUrl(
        authorName: String,
        knownAuthorAsin: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val cleaned = cleanContributorName(authorName)
        if (cleaned.isBlank()) return@withContext null

        val cacheKey = "audible_author:${cleaned.lowercase(Locale.US)}"
        personPortraitCache.get(cacheKey)?.let { cached ->
            if (cached.isNotBlank()) return@withContext cached
        }

        val candidateAsins = linkedSetOf<String>()
        knownAuthorAsin?.trim()?.takeIf { it.isNotBlank() }?.let { candidateAsins.add(it) }

        // Step 1: Query Audnexus author search by name (returns both ASIN and sometimes image directly)
        val encodedName = URLEncoder.encode(cleaned, "UTF-8")
        val audnexSearchJson = executeGetWithRetry(URL("https://api.audnex.us/authors?name=$encodedName&region=us"))
        if (audnexSearchJson != null) {
            runCatching {
                val arr = JSONArray(audnexSearchJson)
                for (i in 0 until arr.length().coerceAtMost(5)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val directImg = obj.optString("image", "").trim()
                    if (directImg.startsWith("https://")) {
                        personPortraitCache.put(cacheKey, directImg)
                        return@withContext directImg
                    }
                    val asin = obj.optString("asin", "").trim()
                    if (asin.isNotBlank()) candidateAsins.add(asin)
                }
            }
        }

        // Step 2: Query Audible's catalog contributors array for matching author ASINs
        if (candidateAsins.isEmpty()) {
            val searchUrl = URL("$BASE_URL?author=$encodedName&num_results=10&response_groups=contributors")
            val searchJson = executeGetWithRetry(searchUrl)
            if (searchJson != null) {
                runCatching {
                    val products = JSONObject(searchJson).optJSONArray("products")
                    if (products != null) {
                        for (i in 0 until products.length()) {
                            val authors = products.optJSONObject(i)?.optJSONArray("authors") ?: continue
                            for (j in 0 until authors.length()) {
                                val aObj = authors.optJSONObject(j) ?: continue
                                val aName = cleanContributorName(aObj.optString("name", ""))
                                val asin = aObj.optString("asin", "").trim()
                                if (asin.isNotBlank() &&
                                    (aName.equals(cleaned, ignoreCase = true) ||
                                        aName.contains(cleaned, ignoreCase = true) ||
                                        cleaned.contains(aName, ignoreCase = true))
                                ) {
                                    candidateAsins.add(asin)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Step 3: Scrape Audible web search if still no author ASIN found
        if (candidateAsins.isEmpty()) {
            val webSearchUrl = URL(
                "https://www.audible.com/search?searchAuthor=$encodedName&ipRedirectOverride=true&overrideBaseCountry=true"
            )
            val searchHtml = executeGetWithRetry(webSearchUrl, acceptHtml = true)
            if (searchHtml != null) {
                val asinRegex = Regex("/author/(?:[^/\"'?]+/)?(B[A-Z0-9]{9})")
                asinRegex.findAll(searchHtml).take(3).forEach { m ->
                    m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { candidateAsins.add(it) }
                }
            }
        }

        // Step 4: For each candidate Author ASIN, check Audnexus Author Profile and Audible's /author/{asin} page
        for (authorAsin in candidateAsins) {
            // 4A: Audnexus author profile endpoint
            val audnexProfileJson = executeGetWithRetry(URL("https://api.audnex.us/authors/$authorAsin?region=us"))
            if (audnexProfileJson != null) {
                val img = runCatching {
                    JSONObject(audnexProfileJson).optString("image", "").trim()
                }.getOrDefault("")
                if (img.startsWith("https://")) {
                    personPortraitCache.put(cacheKey, img)
                    return@withContext img
                }
            }

            // 4B: Direct scrape of Audible's public author page (https://www.audible.com/author/{authorAsin})
            val authorPageUrl = URL(
                "https://www.audible.com/author/$authorAsin?ipRedirectOverride=true&overrideBaseCountry=true"
            )
            val html = executeGetWithRetry(authorPageUrl, acceptHtml = true)
            if (html != null) {
                val extracted = extractAuthorImageFromAudibleHtml(html)
                if (extracted.startsWith("https://")) {
                    personPortraitCache.put(cacheKey, extracted)
                    return@withContext extracted
                }
            }
        }

        null
    }

    private fun extractAuthorImageFromAudibleHtml(html: String): String {
        // 1. Official Amazon/Audible Author Media image URLs
        val amznAuthorMedia = Regex(
            "https://(?:m\\.media-amazon\\.com|images-na\\.ssl-images-amazon\\.com)/images/S/amzn-author-media[^\"'\\s>]*"
        ).find(html)?.value.orEmpty()
        if (amznAuthorMedia.startsWith("https://")) return amznAuthorMedia

        // 2. OpenGraph meta image on the Audible author page (when not generic Audible logo)
        val ogRegex = Regex("<meta\\s+[^>]*property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val ogReverseRegex = Regex("<meta\\s+[^>]*content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:image[\"']", RegexOption.IGNORE_CASE)
        val ogUrl = ogRegex.find(html)?.groupValues?.getOrNull(1)
            ?: ogReverseRegex.find(html)?.groupValues?.getOrNull(1)
            ?: ""
        if (isValidAudibleAuthorPortraitUrl(ogUrl)) return ogUrl

        // 3. Any image tag near author-image / Bio / Profile classes
        val imgRegex = Regex("https://(?:m\\.media-amazon\\.com|images-na\\.ssl-images-amazon\\.com)/images/[SI]/[^\"'\\s>]+\\.(?:jpg|jpeg|png)")
        for (match in imgRegex.findAll(html)) {
            val candidate = match.value
            if (isValidAudibleAuthorPortraitUrl(candidate)) {
                return candidate
            }
        }
        return ""
    }

    fun isValidAudibleAuthorPortraitUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val lower = url.lowercase(Locale.US)
        if (lower.contains("audible_logo") ||
            lower.contains("g/01/audible") ||
            lower.contains("default_author") ||
            lower.contains("avatar_placeholder") ||
            lower.contains("transparent-pixel") ||
            lower.contains("sprite")
        ) {
            return false
        }
        return lower.contains("amzn-author-media") ||
            lower.contains("media-amazon.com/images/") ||
            lower.contains("ssl-images-amazon.com/images/") ||
            lower.contains("wikimedia.org")
    }

    /**
     * Resolves a narrator portrait via Wikipedia's REST summary API when available.
     */
    suspend fun fetchNarratorPortraitUrl(narratorName: String): String? = withContext(Dispatchers.IO) {
        val cleaned = cleanContributorName(narratorName)
        if (cleaned.isBlank()) return@withContext null
        val cacheKey = "narrator:${cleaned.lowercase(Locale.US)}"
        personPortraitCache.get(cacheKey)?.let { cached ->
            return@withContext cached.ifBlank { null }
        }

        val slug = URLEncoder.encode(cleaned.replace(" ", "_"), "UTF-8")
        val wikiUrl = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$slug")
        val jsonStr = executeGetWithRetry(wikiUrl)
        val thumbUrl = if (jsonStr != null) {
            runCatching {
                JSONObject(jsonStr).optJSONObject("thumbnail")?.optString("source").orEmpty()
            }.getOrDefault("")
        } else ""

        personPortraitCache.put(cacheKey, thumbUrl)
        thumbUrl.ifBlank { null }
    }

    suspend fun searchCatalog(
        author: String? = null,
        narrator: String? = null,
        titleOrSeries: String? = null,
        asin: String? = null,
        numResults: Int = 45,
        forceRefresh: Boolean = false
    ): List<CatalogProduct> = withContext(Dispatchers.IO) {
        if (!asin.isNullOrBlank() && asin.trim().matches(Regex("^[A-Z0-9]{10}$", RegexOption.IGNORE_CASE))) {
            return@withContext fetchByAsin(asin.trim().uppercase(Locale.US), forceRefresh)
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

    private suspend fun fetchByAsin(asin: String, forceRefresh: Boolean): List<CatalogProduct> {
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

    private suspend fun fetchWithDiskCache(urlStr: String, forceRefresh: Boolean): String? {
        val dir = diskCacheDir
        val cacheFile = dir?.let { File(it, urlStr.hashCode().toUInt().toString(16) + ".json") }

        if (!forceRefresh && cacheFile != null && cacheFile.exists()) {
            val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
            if (ageMs in 0..DISK_CACHE_TTL_MS && cacheFile.length() > 0) {
                runCatching { return cacheFile.readText(Charsets.UTF_8) }
            }
        }

        val freshJson = executeGetWithRetry(URL(urlStr))
        if (freshJson != null && cacheFile != null) {
            runCatching { cacheFile.writeText(freshJson, Charsets.UTF_8) }
        } else if (freshJson == null && cacheFile != null && cacheFile.exists()) {
            runCatching { return cacheFile.readText(Charsets.UTF_8) }
        }
        return freshJson
    }

    private suspend fun executeGetWithRetry(url: URL, acceptHtml: Boolean = false): String? {
        repeat(2) { attempt ->
            var shouldRetry = false
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", if (acceptHtml) "text/html,application/xhtml+xml" else "application/json")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty(
                    "User-Agent",
                    if (acceptHtml) {
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    } else {
                        "NocturneTXT/1.9 (Android)"
                    }
                )
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val rawStream = conn.inputStream
                    val decodedStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                        GZIPInputStream(rawStream)
                    } else {
                        rawStream
                    }
                    return decodedStream.bufferedReader().use { it.readText() }
                } else if (code == 429 || code in 500..599) {
                    shouldRetry = true
                }
            } catch (_: Exception) {
                shouldRetry = true
            } finally {
                conn.disconnect()
            }
            if (shouldRetry && attempt == 0) {
                delay(450L)
            } else {
                return null
            }
        }
        return null
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

        val contentDeliveryType = obj.optString("content_delivery_type", "")
        val contentType = obj.optString("content_type", "")

        // Filter out podcasts, podcast episodes, periodicals, and radio shows immediately
        val combinedType = "$contentDeliveryType $contentType ${obj.optString("thesaurus_subject_keywords", "")}".lowercase(Locale.US)
        if (combinedType.contains("podcast") ||
            combinedType.contains("periodical") ||
            combinedType.contains("episode") ||
            combinedType.contains("radio") ||
            combinedType.contains("speech") ||
            combinedType.contains("newspaper")
        ) {
            return null
        }

        if (title.startsWith("Bonus Episode", ignoreCase = true) ||
            title.contains("Podcast Episode", ignoreCase = true)
        ) {
            return null
        }

        val primaryAuthors = mutableListOf<String>()
        val fallbackSecondaryAuthors = mutableListOf<String>()
        var primaryAuthorAsin = ""

        obj.optJSONArray("authors")?.let { arr ->
            for (i in 0 until arr.length()) {
                val aObj = arr.optJSONObject(i) ?: continue
                val rawName = aObj.optString("name", "").trim()
                if (rawName.isBlank()) continue
                val cleanedName = cleanContributorName(rawName)
                if (cleanedName.isBlank()) continue

                if (isSecondaryContributorRole(rawName)) {
                    if (cleanedName !in fallbackSecondaryAuthors) {
                        fallbackSecondaryAuthors += cleanedName
                    }
                } else {
                    if (cleanedName !in primaryAuthors) {
                        primaryAuthors += cleanedName
                    }
                    if (primaryAuthorAsin.isBlank()) {
                        primaryAuthorAsin = aObj.optString("asin", "").trim()
                    }
                }
            }
        }

        val finalAuthors = if (primaryAuthors.isNotEmpty()) primaryAuthors else fallbackSecondaryAuthors
        // Skip entries with no real author (such as podcast feeds that list Unknown Author)
        if (finalAuthors.isEmpty()) {
            return null
        }

        val narrators = mutableListOf<String>()
        obj.optJSONArray("narrators")?.let { arr ->
            for (i in 0 until arr.length()) {
                val rawNar = arr.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                val cleanedNar = cleanContributorName(rawNar)
                if (cleanedNar.isNotBlank() && cleanedNar !in narrators) {
                    narrators += cleanedNar
                }
            }
        }

        var seriesTitle = ""
        var seriesAsin = ""
        var seriesSeq: Int? = null
        obj.optJSONArray("series")?.let { sArr ->
            if (sArr.length() > 0) {
                val primarySeries = sArr.optJSONObject(0)
                if (primarySeries != null) {
                    seriesTitle = primarySeries.optString("title", "").trim()
                    seriesAsin = primarySeries.optString("asin", "").trim()
                    val rawSeq = primarySeries.optString("sequence", "").trim()
                    seriesSeq = rawSeq.toIntOrNull()
                        ?: Regex("(\\d+)").find(rawSeq)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            }
        }

        if (seriesSeq == null && seriesTitle.isNotBlank()) {
            seriesSeq = extractSequenceFromTitle(title, subtitle)
        }

        val releaseDate = obj.optString("release_date", obj.optString("issue_date", "TBA"))
            .takeIf { it.isNotBlank() } ?: "TBA"
        val language = obj.optString("language", "english")
        val formatType = obj.optString("format_type", "unabridged")

        var coverUrl = ""
        obj.optJSONObject("product_images")?.let { imgObj ->
            coverUrl = imgObj.optString("500").ifBlank { imgObj.optString("252") }
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
            authors = finalAuthors,
            primaryAuthorAsin = primaryAuthorAsin,
            narrators = narrators,
            seriesTitle = seriesTitle,
            seriesAsin = seriesAsin,
            seriesSequence = seriesSeq,
            releaseDateIso = releaseDate,
            language = language,
            formatType = formatType,
            contentDeliveryType = contentDeliveryType,
            contentType = contentType,
            coverUrl = coverUrl,
            synopsis = cleanSynopsis,
            runtimeMinutes = runtimeMinutes,
            publisher = publisher
        )
    }

    private fun extractSequenceFromTitle(title: String, subtitle: String): Int? {
        val combined = "$title $subtitle"
        val bookNumMatch = Regex("(?:Book|Vol(?:ume)?|Part|#)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(combined)
        if (bookNumMatch != null) {
            return bookNumMatch.groupValues.getOrNull(1)?.toIntOrNull()
        }
        val trailingNumMatch = Regex("\\s(\\d{1,2})$").find(title.trim())
        return trailingNumMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
