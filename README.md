# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.1)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.1
- **Cross-Date & Regional Duplicate Merging**: Collapses multi-region and multi-date duplicate ASINs (such as US vs. UK/Commonwealth listings spaced days apart) into a single scorecard, automatically keeping the earliest release date and real cover art over missing/placeholder covers.
- **Strict Podcast & Non-Audiobook Blocker + Series Precision**: Filters out `PodcastParent`, `PodcastEpisode`, `Periodical`, radio shows, bonus episodes, and `Unknown Author` entries, and requires followed `SERIES` items to match Audible's `series` metadata rather than unrelated podcast episode titles.
- **Official Audible Author Page Thumbnails**: Resolves author portraits directly from Audible's author pages (`audible.com/author/{asin}`) and [Audnexus](https://audnex.us/) (`api.audnex.us/authors`), automatically replacing old cached book-cover thumbnails on sync.
- **System Theme Sync (`SYSTEM AUTO` / `DRACULA` / `ALUCARD`)**: Follows Android's system dark/light theme automatically by default (`isSystemInDarkTheme()`), with manual override options in `SETTINGS` and the top bar.
- **All 6 Quality-of-Life Upgrades**:
  1. Strips contributor role clutter (`- Translator - translator`, `- editor`, `- foreword`) and ignores anthology/foreword-only credits when matching followed authors.
  2. Prefers real cover art over missing or generic `"Coming Soon"` placeholder covers when merging editions.
  3. Pinned sticky month headers (`stickyHeader`) in `UPCOMING` so the current month header never disappears while scrolling.
  4. Auto-normalizes followed source names to canonical Title Case (e.g., `andy weir` -> `Andy Weir`) and adds `A-Z` vs. `MOST UPCOMING` sorting in `TRACKED`.
  5. Tap any followed card in `TRACKED` to jump straight to `UPCOMING` filtered to that exact Series, Author, Narrator, or Book.
  6. Smart Re-Issue Guard suppresses new audio re-issues of older books you have already read (`seriesSequence <= lastReadSequence`) or that already released earlier in the series.
