# PlainText Audible Release Tracker v1.4 (Android)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v1.4
- **Default Live Catalog Search in `TRACKED`**: Search Audible's live catalog by default with instant `[✓]` badges on already-followed Series, Authors, Narrators, and Books.
- **Direct Manual Add in `CFG`**: Add a Series, Author, Narrator, or Book ASIN directly from the `CFG` (Settings) tab.
- **Duplicate Prevention & Auto-Deduplication**: Prevents adding the same Author, Series, Narrator, or Book multiple times (case-insensitive) and automatically cleans up any existing duplicates via Room `Migration(2, 3)`.
- **2-Tier Metadata & Image Caching + Parallel Sync**: Caches Audible catalog JSON responses in both an in-memory `LruCache` and disk cache (12-hour TTL, bypassed on manual `[ SYNC ]`) and executes all tracked queries concurrently via Kotlin Coroutines `async`/`awaitAll`.
- **`[ Audible ]` & `[ ABB ]` Quick Actions**: Opens region-safe Audible search (`[ Audible ]`) or searches AudioBookBay (`[ ABB ]` -> `https://audiobookbay.lu/?s={search term}&cat=undefined%2Cundefined`) directly from any book card.
