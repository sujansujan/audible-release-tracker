package com.plaintext.audible.data

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
    val formatType: String
)

object AudibleApiClient {
    private const val BASE_URL = "https://api.audible.com/1.0/catalog/products"
    private const val RESPONSE_GROUPS =
        "contributors,product_attrs,product_desc,product_extended_attrs,series,relationships"

    suspend fun searchCatalog(
        author: String? = null,
        narrator: String? = null,
        titleOrSeries: String? = null,
        asin: String? = null
    ): List<CatalogProduct> = withContext(Dispatchers.IO) {
        if (!asin.isNullOrBlank() && asin.matches(Regex("^[A-Z0-9]{10}$", RegexOption.IGNORE_CASE))) {
            return@withContext fetchByAsin(asin.uppercase())
        }

        val params = mutableListOf(
            "response_groups=$RESPONSE_GROUPS",
            "products_sort_by=-ReleaseDate",
            "num_results=40"
        )
        author?.takeIf { it.isNotBlank() }?.let {
            params += "author=${URLEncoder.encode(it, "UTF-8")}"
        }
        narrator?.takeIf { it.isNotBlank() }?.let {
            params += "narrator=${URLEncoder.encode(it, "UTF-8")}"
        }
        titleOrSeries?.takeIf { it.isNotBlank() }?.let {
            params += "keywords=${URLEncoder.encode(it, "UTF-8")}"
        }

        val url = URL("$BASE_URL?${params.joinToString("&")}")
        val jsonStr = executeGet(url) ?: return@withContext emptyList()
        parseProductsArray(JSONObject(jsonStr))
    }

    private fun fetchByAsin(asin: String): List<CatalogProduct> {
        val url = URL("$BASE_URL/$asin?response_groups=$RESPONSE_GROUPS")
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
            setRequestProperty("User-Agent", "PlainTextAudibleTracker/1.0 (Android)")
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
            formatType = formatType
        )
    }
}
