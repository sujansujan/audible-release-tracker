package com.example.audiblereleases.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val repo = AudibleRepository()
    val follows = db.follows().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val releases = db.releases().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val query = MutableStateFlow("")
    val lastError = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val lastRefreshAt = MutableStateFlow<Long?>(null)

    val upcoming = combine(releases, follows) { items, saved ->
        val authors = saved.filter { it.kind == "author" }.map { normalize(it.value) }
        val series = saved.filter { it.kind == "series" }.map { normalize(it.value) }
        if (authors.isEmpty() && series.isEmpty()) items else items.filter { item -> authors.any { matches(it, normalize(item.author)) } || series.any { matches(it, normalize(item.series)) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val searchResults = combine(releases, query) { items, q -> if (q.isBlank()) items else items.filter { val s = q.lowercase(); listOf(it.title, it.author, it.series, it.narrator).any { f -> f.lowercase().contains(s) } } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() { viewModelScope.launch { loading.value = true; lastError.value = null; runCatching { (repo.fetchComingSoon() + repo.fetchNewReleases() + repo.fetchForFollowed(follows.value)).distinctBy { it.asin } }.onSuccess { db.releases().upsertAll(it); lastRefreshAt.value = System.currentTimeMillis() }.onFailure { lastError.value = it.message ?: "Could not reach Audible" }; loading.value = false } }
    fun addFollow(kind: String, value: String) { if (value.isNotBlank()) viewModelScope.launch { db.follows().add(Follow(kind, value.trim())) } }
    fun importFollow(kind: String, value: String) { addFollow(kind, value) }
    fun removeFollow(item: Follow) { viewModelScope.launch { db.follows().remove(item) } }

    private fun normalize(value: String): String = value.lowercase().replace("&", "and").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun matches(followed: String, catalog: String): Boolean = followed.isNotBlank() && catalog.isNotBlank() && (catalog.contains(followed) || followed.contains(catalog))
}
