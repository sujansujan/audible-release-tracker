package com.example.audiblereleases.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Uses Audible's undocumented catalog endpoint, the same public catalog API
 * approach documented by MediaTracker and audible.readthedocs.io.
 */
class AudibleRepository {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val responseGroups = "contributors,media,product_attrs,product_desc,series"

    suspend fun fetchComingSoon(): List<ReleaseEntity> = withContext(Dispatchers.IO) { fetchCatalog(sort = "ReleaseDate") }
    suspend fun fetchNewReleases(): List<ReleaseEntity> = withContext(Dispatchers.IO) { fetchCatalog(sort = "-ReleaseDate") }

    suspend fun fetchForFollowed(follows: List<Follow>): List<ReleaseEntity> = withContext(Dispatchers.IO) {
        follows.flatMap { follow ->
            when (follow.kind) {
                "author" -> fetchCatalog(author = follow.value)
                "series" -> fetchCatalog(keywords = follow.value)
                else -> emptyList()
            }
        }.distinctBy { it.asin }
    }

    private fun fetchCatalog(
        author: String? = null,
        keywords: String? = null,
        sort: String = "-ReleaseDate"
    ): List<ReleaseEntity> {
        val url = "https://api.audible.com/1.0/catalog/products".toHttpUrl().newBuilder()
            .addQueryParameter("num_results", "50")
            .addQueryParameter("products_sort_by", sort)
            .addQueryParameter("response_groups", responseGroups)
            .apply {
                if (!author.isNullOrBlank()) addQueryParameter("author", author)
                if (!keywords.isNullOrBlank()) addQueryParameter("keywords", keywords)
            }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "AudibleReleaseTracker/1.0 (personal use)").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Audible catalog API returned HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val products = root.optJSONArray("products") ?: return emptyList()
            buildList {
                for (index in 0 until products.length()) {
                    parseProduct(products.optJSONObject(index))?.let { add(it) }
                }
            }
        }
    }

    private fun parseProduct(product: JSONObject?): ReleaseEntity? {
        if (product == null) return null
        val asin = product.optString("asin").trim()
        val title = product.optString("title").trim()
        val rawDate = product.optString("release_date").trim().ifBlank { product.optString("issue_date").trim() }
        if (asin.isBlank() || title.isBlank() || rawDate.isBlank()) return null
        val date = formatDate(rawDate)
        val authors = names(product.optJSONArray("authors"))
        val narrators = names(product.optJSONArray("narrators"))
        val seriesArray = product.optJSONArray("series")
        val series = buildList {
            if (seriesArray != null) for (i in 0 until seriesArray.length()) {
                val item = seriesArray.optJSONObject(i)
                val name = item?.optString("title").orEmpty()
                val sequence = item?.optString("sequence").orEmpty()
                if (name.isNotBlank()) add(if (sequence.isNotBlank()) "$name #$sequence" else name)
            }
        }.joinToString("; ")
        val runtime = product.optInt("runtime_length_min", 0).let { minutes ->
            if (minutes <= 0) "" else "${minutes / 60}h ${minutes % 60}m"
        }
        val synopsis = product.optString("merchandising_summary").ifBlank { product.optString("publisher_summary") }.trim().take(2000)
        return ReleaseEntity(
            asin = asin,
            title = title,
            author = authors.joinToString(", "),
            narrator = narrators.joinToString(", "),
            series = series,
            length = runtime,
            releaseDate = date,
            url = "https://audible.com/pd/$asin?overrideBaseCountry=true&ipRedirectOverride=true",
            synopsis = synopsis
        )
    }

    private fun names(array: org.json.JSONArray?): List<String> = buildList {
        if (array != null) for (i in 0 until array.length()) {
            val name = array.optJSONObject(i)?.optString("name").orEmpty().trim()
            if (name.isNotBlank()) add(name)
        }
    }

    private fun formatDate(raw: String): String {
        val date = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return raw
        return date.format(DateTimeFormatter.ofPattern("MM-dd-yy", Locale.US))
    }
}
