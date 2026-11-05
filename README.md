# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.6)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.6
1. **Series-Author & ASIN Pinning (Fixes Same-Name Series Collisions)**: Followed `SERIES` and `BOOK` items lock onto their Audible `seriesAsin` + primary author (`externalId = "seriesAsin|Author"`), blocking books from unrelated series that share the same name by different authors.
2. **Throttled Daily AudioBookBay (`ABB`) Availability Checker**: Checks already-released books (`releaseDateIso <= today`) sequentially (`Semaphore(1)` + `1500ms` delay + mirror fallback `.lu` / `.is` / `.fi`) at most once every 20 hours (or when you tap `[ SYNC ]`). Once found on ABB, it caches `abbAvailable = true` permanently so ABB is never queried for that book again, displays **`[ ABB: AVAILABLE ]`**, and sends a daily `[ON ABB]` notification.
3. **1-Tap `[ Magnet ]` Launch / Copy**: Automatically extracts the 40-char BitTorrent `Info Hash` from the matched AudioBookBay post page and shows a **`[ Magnet ]`** button inside the expanded card that launches your torrent app or copies the `magnet:?xt=urn:btih:...` URI to your clipboard.
4. **Word-Boundary Author & Narrator Matching**: Uses strict word-boundary matching so short names never falsely match inside unrelated longer names.
5. **1-Tap `[ Archive Book ]` for Released Standalone `BOOK` Trackers**: Offers a 1-tap `[ Archive Book ]` action on released standalone `BOOK` trackers so completed one-off books don't clutter your `TRACKED` list.
6. **Smart Subtitle & `By & Narrated:` Card De-Clutter**: Strips repetitive LitRPG/Progression Fantasy marketing subtitles when the series line is already shown, and combines `Author:` + `Narrator:` into `By & Narrated:` when the author narrates their own audiobook.
