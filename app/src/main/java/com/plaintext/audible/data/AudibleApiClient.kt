package com.plaintext.audible.data

import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

object AudibleApiClient {
    private const val BASE_URL = "https://api.audible.com/1.0/catalog/products"
    private const val RESPONSE_GROUPS =
        "contributors,product_attrs,product_desc,product_extended_attrs,series,relationships,media"
    private const val IMAGE_SIZES = "252,500"

    suspend fun searchCatalog(
        author: String? = null,
        narrator: String? = null,
        titleOrSeries: String? = null,
        asin: String? = null,
        numResults: Int = 40
    ): List<CatalogProduct> = withContext(Dispatchers.IO) {
        if (!asin.isNullOrBlank() && asin.trim().matches(Regex("^[A-Z0-9]{10}$", RegexOption.IGNORE_CASE))) {
            return@withContext fetchByAsin(asin.trim().uppercase())
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

        val url = URL("$BASE_URL?${params.joinToString("&")}")
        val jsonStr = executeGet(url) ?: return@withContext emptyList()
        parseProductsArray(JSONObject(jsonStr))
    }

    private fun fetchByAsin(asin: String): List<CatalogProduct> {
        val url = URL("$BASE_URL/$asin?response_groups=$RESPONSE_GROUPS&image_sizes=$IMAGE_SIZES")
        val jsonStr = executeGet(url) ?: return emptyList()
        val root = JSONObject(jsonStr)
        val prodObj = root.optJSONObject("product") ?: return emptyList()
        return listOfNotNull(parseProductObject(prodObj))
    }

    private fun executeGet(url: URL): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PlainTextAudibleTracker/1.1 (Android)")
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

        // Extract square cover thumbnail URL from product_images
        var coverUrl = ""
        obj.optJSONObject("product_images")?.let { imgObj ->
            coverUrl = imgObj.optString("252").ifBlank { imgObj.optString("500") }
        }

        // Extract and strip HTML from synopsis (Feature #1)
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
