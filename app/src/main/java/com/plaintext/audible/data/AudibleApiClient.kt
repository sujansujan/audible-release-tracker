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
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
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
data class AbbMatchResult(
    val available: Boolean,
    val postUrl: String = "",
    val magnetUri: String = ""
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
    private const val PRUNE_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    private const val PRUNE_MAX_BYTES = 15L * 1024L * 1024L
    private const val CACHE_DIR_NAME = "audible_catalog_json_v26"

    private val ABB_MIRRORS = listOf(
        "https://audiobookbay.lu",
        "https://audiobookbay.is",
        "https://audiobookbay.fi",
        "https://theaudiobookbay.se"
    )

    private val STOP_WORDS = setOf(
        "the", "and", "with", "for", "from", "into", "that", "this", "who", "what",
        "when", "where", "why", "how", "book", "volume", "vol", "part", "series", "novel", "audiobook"
    )

    private val memoryCache = object : LruCache<String, List<CatalogProduct>>(140) {}
    private val personPortraitCache = object : LruCache<String, String>(160) {}

    val syncNetworkErrorCount = AtomicInteger(0)
    val syncUsedStaleCacheCount = AtomicInteger(0)

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
        runCatching {
            listOf("audible_catalog_json", "audible_catalog_json_v19", "audible_catalog_json_v24").forEach { oldName ->
                File(baseCacheDir, oldName).listFiles()?.forEach { it.delete() }
            }
        }
    }

    suspend fun pruneStaleDiskCache() = withContext(Dispatchers.IO) {
        val base = rootCacheDir ?: return@withContext
        val now = System.currentTimeMillis()
        val folders = listOf(File(base, CACHE_DIR_NAME), File(base, "covers"))
        val survivingFiles = mutableListOf<File>()

        for (folder in folders) {
            folder.listFiles()?.forEach { f ->
                if (f.isFile) {
                    if (now - f.lastModified() > PRUNE_MAX_AGE_MS) {
                        runCatching { f.delete() }
                    } else {
                        survivingFiles += f
                    }
                }
            }
        }

        var totalBytes = survivingFiles.sumOf { it.length() }
        if (totalBytes > PRUNE_MAX_BYTES) {
            val sortedOldestFirst = survivingFiles.sortedBy { it.lastModified() }
            for (f in sortedOldestFirst) {
                if (totalBytes <= PRUNE_MAX_BYTES) break
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    totalBytes -= len
                }
            }
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
        return cleaned.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word
            else if (word.length <= 3 && word.all { it.isUpperCase() || it == '.' }) word
            else word.lowercase(Locale.US).replaceFirstChar { ch -> ch.titlecase(Locale.US) }
        }
    }

    /**
     * Checks AudioBookBay for a released audiobook (`He Who Fights with Monsters 13`, etc.):
     * 1) Searches by the clean core book title (`?s=he+who+fights+with+monsters+13`) so dual pen-names
     *    (`Shirtaloon` vs `Travis Deverell`) don't cause ABB's strict keyword search to return 0 results.
     * 2) Sends `Origin`, `Referer`, and `Sec-Fetch-*` headers required by ABB's anti-bot filter.
     * 3) Parses `<div class="postTitle">...<a href="...">Title</a>` and `<h2><a href="...">Title</a>` blocks
     *    (supporting both `/audio-books/...` and `/abss/...` URLs and nested tags).
     * 4) Verifies that significant title words + any sequence number match the post title, and that at least
     *    one author or narrator token (e.g. `shirtaloon`, `deverell`, or `miller`) appears in the post block.
     * 5) Extracts the 40-char BitTorrent Info Hash from the matched post page to build a ready-to-use `magnet:` URI.
     */
    suspend fun checkAudioBookBayAvailability(
        bookTitle: String,
        allAuthors: String,
        allNarrators: String = "",
        seriesTitle: String = "",
        seriesSequence: Int? = null
    ): AbbMatchResult = withContext(Dispatchers.IO) {
        val coreTitle = bookTitle
            .substringBefore(":")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (coreTitle.isBlank()) return@withContext AbbMatchResult(false)

        val titleNumbers = Regex("\\b(\\d+)\\b").findAll(coreTitle)
            .map { it.groupValues[1] }
            .toMutableSet()
        if (seriesSequence != null && seriesSequence > 0) {
            titleNumbers.add(seriesSequence.toString())
        }

        val significantTitleWords = coreTitle.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 && it !in STOP_WORDS && !it.all { ch -> ch.isDigit() } }

        // Build candidate contributor tokens from all authors and narrators (e.g. ["shirtaloon", "travis", "deverell", "heath", "miller"])
        val contributorTokens = "$allAuthors, $allNarrators"
            .lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .toSet()

        val primaryAuthorFirst = allAuthors.split(",").firstOrNull()?.trim().orEmpty()

        // Try clean coreTitle query first (most reliable on ABB), then fallback to coreTitle + primaryAuthor
        val candidateQueries = linkedSetOf<String>()
        candidateQueries.add(coreTitle.lowercase(Locale.US))
        if (seriesTitle.isNotBlank() && seriesSequence != null) {
            candidateQueries.add("${seriesTitle.trim()} $seriesSequence".lowercase(Locale.US))
        }
        if (primaryAuthorFirst.isNotBlank()) {
            candidateQueries.add("$coreTitle $primaryAuthorFirst".lowercase(Locale.US))
        }

        // Matches <div class="postTitle"><h2><a href="...">...</a></h2></div> OR <h2><a href="...">...</a></h2>
        val h2LinkRegex = Regex(
            "<h[12][^>]*>\\s*<a\\s+[^>]*href=[\"']([^\"'#?]+)[\"'][^>]*>(.*?)</a>\\s*</h[12]>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val fallbackBookLinkRegex = Regex(
            "<a\\s+[^>]*href=[\"']((?:https?://[^\"'/]+)?/(?:audio-books|abss)/[^\"'#?]+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val infoHashRegex = Regex(
            "Info\\s*Hash\\s*:?\\s*</t[dh]>\\s*<td[^>]*>\\s*([a-fA-F0-9]{40})\\s*</td>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val fallbackHashRegex = Regex("\\b([a-fA-F0-9]{40})\\b")

        for (mirror in ABB_MIRRORS) {
            var mirrorResponded = false
            for (qRaw in candidateQueries) {
                val encodedQ = URLEncoder.encode(qRaw, "UTF-8")
                val searchUrl = URL("$mirror/?s=$encodedQ")
                val searchHtml = executeGetWithRetry(
                    url = searchUrl,
                    acceptHtml = true,
                    originUrl = mirror
                ) ?: continue

                if (searchHtml.contains("<html", ignoreCase = true)) {
                    mirrorResponded = true
                }

                val linkMatches = h2LinkRegex.findAll(searchHtml).toList().ifEmpty {
                    fallbackBookLinkRegex.findAll(searchHtml).toList()
                }

                for (match in linkMatches) {
                    val rawHref = match.groupValues.getOrNull(1)?.trim().orEmpty()
                    if (rawHref.isBlank() || rawHref == "/" || rawHref.contains("/page/") || rawHref.contains("/category/")) {
                        continue
                    }

                    val rawAnchorHtml = match.groupValues.getOrNull(2).orEmpty()
                    val linkTitle = HtmlCompat.fromHtml(rawAnchorHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
                        .toString()
                        .lowercase(Locale.US)

                    // Surrounding 1200 chars of the post card on the search results page (contains Category, Author, Narrator, Language)
                    val windowStart = match.range.first.coerceAtLeast(0)
                    val windowEnd = (match.range.last + 1200).coerceAtMost(searchHtml.length)
                    val postContextLower = HtmlCompat.fromHtml(
                        searchHtml.substring(windowStart, windowEnd),
                        HtmlCompat.FROM_HTML_MODE_COMPACT
                    ).toString().lowercase(Locale.US)

                    // 1. Check that significant title words appear in the post title
                    val matchedWordCount = significantTitleWords.count { w -> linkTitle.contains(w) }
                    val requiredWordCount = if (significantTitleWords.size >= 2) {
                        (significantTitleWords.size - 1).coerceAtLeast(1)
                    } else {
                        significantTitleWords.size
                    }
                    val wordsMatch = significantTitleWords.isEmpty() || matchedWordCount >= requiredWordCount

                    // 2. If the book has a number (e.g. "13" in "He Who Fights with Monsters 13"), require that exact number as a word boundary
                    val numbersMatch = titleNumbers.all { num ->
                        val padded = num.padStart(2, '0')
                        Regex("\\b(?:$num|$padded)\\b").containsMatchIn(linkTitle)
                    }

                    // 3. Check that at least one contributor token (e.g. "shirtaloon" OR "deverell" OR "miller") appears in the title or post block
                    val contributorMatches = contributorTokens.isEmpty() || contributorTokens.any { tok ->
                        linkTitle.contains(tok) || postContextLower.contains(tok)
                    }

                    if (wordsMatch && numbersMatch && contributorMatches) {
                        val fullPostUrl = when {
                            rawHref.startsWith("http") -> rawHref
                            rawHref.startsWith("/") -> "$mirror$rawHref"
                            else -> "$mirror/$rawHref"
                        }

                        var magnetUri = ""
                        val postHtml = executeGetWithRetry(
                            url = URL(fullPostUrl),
                            acceptHtml = true,
                            originUrl = mirror
                        )
                        if (postHtml != null) {
                            val hash = infoHashRegex.find(postHtml)?.groupValues?.getOrNull(1)
                                ?: fallbackHashRegex.find(postHtml)?.groupValues?.getOrNull(1)
                                ?: ""
                            if (hash.length == 40) {
                                val dn = URLEncoder.encode("$coreTitle - $primaryAuthorFirst", "UTF-8")
                                magnetUri = "magnet:?xt=urn:btih:${hash.lowercase(Locale.US)}&dn=$dn"
                            }
                        }

                        return@withContext AbbMatchResult(
                            available = true,
                            postUrl = fullPostUrl,
                            magnetUri = magnetUri
                        )
                    }
                }
            }
            if (mirrorResponded) {
                break
            }
        }

        AbbMatchResult(false)
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
                File(base, "audible_catalog_json_v19"),
                File(base, "audible_catalog_json_v24"),
                File(base, "covers")
            ).forEach { folder ->
                folder.listFiles()?.forEach { f -> runCatching { f.delete() } }
            }
        }
        CacheStats(0, 0L)
    }

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

        for (authorAsin in candidateAsins) {
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
        val amznAuthorMedia = Regex(
            "https://(?:m\\.media-amazon\\.com|images-na\\.ssl-images-amazon\\.com)/images/S/amzn-author-media[^\"'\\s>]*"
        ).find(html)?.value.orEmpty()
        if (amznAuthorMedia.startsWith("https://")) return amznAuthorMedia

        val ogRegex = Regex("<meta\\s+[^>]*property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val ogReverseRegex = Regex("<meta\\s+[^>]*content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:image[\"']", RegexOption.IGNORE_CASE)
        val ogUrl = ogRegex.find(html)?.groupValues?.getOrNull(1)
            ?: ogReverseRegex.find(html)?.groupValues?.getOrNull(1)
            ?: ""
        if (isValidAudibleAuthorPortraitUrl(ogUrl)) return ogUrl

        val imgRegex = Regex("https://(?:m\\.media-amazon\\.com|images-na\\.ssl-images-amazon\\.com)/images/I/[^\"'\\s>]*_RO\\d+[^\"'\\s>]*\\.(?:jpg|jpeg|png)")
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
        numResults: Int = 50,
        maxPages: Int = 3,
        forceRefresh: Boolean = false
    ): List<CatalogProduct> = withContext(Dispatchers.IO) {
        if (!asin.isNullOrBlank() && asin.trim().matches(Regex("^[A-Z0-9]{10}$", RegexOption.IGNORE_CASE))) {
            return@withContext fetchByAsin(asin.trim().uppercase(Locale.US), forceRefresh)
        }

        val baseParams = mutableListOf(
            "response_groups=$RESPONSE_GROUPS",
            "image_sizes=$IMAGE_SIZES",
            "products_sort_by=-ReleaseDate",
            "num_results=${numResults.coerceAtMost(50)}"
        )
        author?.takeIf { it.isNotBlank() }?.let {
            baseParams += "author=${URLEncoder.encode(it.trim(), "UTF-8")}"
        }
        narrator?.takeIf { it.isNotBlank() }?.let {
            baseParams += "narrator=${URLEncoder.encode(it.trim(), "UTF-8")}"
        }
        titleOrSeries?.takeIf { it.isNotBlank() }?.let {
            baseParams += "keywords=${URLEncoder.encode(it.trim(), "UTF-8")}"
        }

        val combinedKey = "$BASE_URL?${baseParams.joinToString("&")}&maxPages=$maxPages"
        if (!forceRefresh) {
            memoryCache.get(combinedKey)?.let { return@withContext it }
        }

        val cutoffIso = LocalDate.now().minusDays(30).toString()
        val accumulated = LinkedHashMap<String, CatalogProduct>()

        for (page in 0 until maxPages.coerceAtLeast(1)) {
            val pageUrlStr = "$BASE_URL?${baseParams.joinToString("&")}&page=$page"
            val jsonStr = fetchWithDiskCache(pageUrlStr, forceRefresh) ?: break
            val rootObj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: break
            val rawProductsArr = rootObj.optJSONArray("products") ?: break
            val rawCount = rawProductsArr.length()
            val parsedPage = parseProductsArray(rootObj)

            for (prod in parsedPage) {
                accumulated.putIfAbsent(prod.asin, prod)
            }

            val reachedPastCutoff = parsedPage.any { it.releaseDateIso != "TBA" && it.releaseDateIso < cutoffIso }
            if (rawCount < numResults.coerceAtMost(50) || reachedPastCutoff) {
                break
            }
        }

        val finalList = accumulated.values.toList()
        if (finalList.isNotEmpty()) {
            memoryCache.put(combinedKey, finalList)
        }
        finalList
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
        } else if (freshJson == null) {
            syncNetworkErrorCount.incrementAndGet()
            if (cacheFile != null && cacheFile.exists()) {
                syncUsedStaleCacheCount.incrementAndGet()
                runCatching { return cacheFile.readText(Charsets.UTF_8) }
            }
        }
        return freshJson
    }

    private suspend fun executeGetWithRetry(
        url: URL,
        acceptHtml: Boolean = false,
        originUrl: String? = null
    ): String? {
        repeat(2) { attempt ->
            var shouldRetry = false
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty(
                    "Accept",
                    if (acceptHtml) "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" else "application/json"
                )
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept-Encoding", "gzip")
                if (originUrl != null) {
                    setRequestProperty("Origin", originUrl)
                    setRequestProperty("Referer", "$originUrl/")
                    setRequestProperty("Upgrade-Insecure-Requests", "1")
                    setRequestProperty("Sec-Fetch-Dest", "document")
                    setRequestProperty("Sec-Fetch-Mode", "navigate")
                    setRequestProperty("Sec-Fetch-Site", "same-origin")
                }
                setRequestProperty(
                    "User-Agent",
                    if (acceptHtml) {
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    } else {
                        "NocturneTXT/2.7 (Android)"
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
