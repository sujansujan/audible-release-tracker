<<<<<<< HEAD
# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.6)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.6
1. **Series-Author & ASIN Pinning (Fixes Same-Name Series Collisions)**: Followed `SERIES` and `BOOK` items lock onto their Audible `seriesAsin` + primary author (`externalId = "seriesAsin|Author"`), blocking books from unrelated series that share the same name by different authors.
2. **Throttled Daily AudioBookBay (`ABB`) Availability Checker**: Checks already-released books (`releaseDateIso <= today`) sequentially (`Semaphore(1)` + `1500ms` delay + mirror fallback `.lu` / `.is` / `.fi`) at most once every 20 hours (or when you tap `[ SYNC ]`). Once found on ABB, it caches `abbAvailable = true` permanently so ABB is never queried for that book again, displays **`[ ABB: AVAILABLE ]`**, and sends a daily `[ON ABB]` notification.
3. **1-Tap `[ Magnet ]` Launch / Copy**: Automatically extracts the 40-char BitTorrent `Info Hash` from the matched AudioBookBay post page and shows a **`[ Magnet ]`** button inside the expanded card that launches your torrent app or copies the `magnet:?xt=urn:btih:...` URI to your clipboard.
4. **Word-Boundary Author & Narrator Matching**: Uses strict word-boundary matching so short names never falsely match inside unrelated longer names.
5. **1-Tap `[ Archive Book ]` for Released Standalone `BOOK` Trackers**: Offers a 1-tap `[ Archive Book ]` action on released standalone `BOOK` trackers so completed one-off books don't clutter your `TRACKED` list.
6. **Smart Subtitle & `By & Narrated:` Card De-Clutter**: Strips repetitive LitRPG/Progression Fantasy marketing subtitles when the series line is already shown, and combines `Author:` + `Narrator:` into `By & Narrated:` when the author narrates their own audiobook.
=======
# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.5)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.5 (De-Duplicated UI & Settings Cleanup)
1. **Single Theme Control in `SETTINGS`**: Removed the duplicate theme switcher button from the top-right header bar (leaving only `[ SYNC ]` on the right) while keeping the `SYSTEM AUTO` / `DRACULA` / `ALUCARD` selector in `SETTINGS`.
2. **Streamlined `[ LIVE SEARCH ]` / `[ DIRECT ADD ]` in `TRACKED`**: Removed the redundant `Direct Manual Add Mode` toggle from `SETTINGS`—the `[ LIVE SEARCH ]` and `[ DIRECT ADD ]` switch is now directly available in `TRACKED`.
3. **Cleaner Notification Schedule Controls**: Removed the redundant `[ Reset 09:00 ]` button, keeping just `[ -1 Hour ]` and `[ +1 Hour ]`.
4. **Smart `+ Track Series` / `+ Track Author` Buttons on Expanded Cards**: Expanded release cards now only show `[ + Track Series ]` or `[ + Track Author ]` if that series or author is **not** already in your `TRACKED` list.
5. **Contextual `[ CLEAR ]` in `TRACKED`**: `[ CLEAR ]` now appears only when live catalog search results are actually open on screen.
6. **Clean Top-Left Tab Header (`[ UPCOMING ]`, `[ TRACKED ]`, `[ SETTINGS ]`)**: Removed the redundant `// US` marketplace code from the top-left header bar.
>>>>>>> 0c80a6bd6412354f728b081bee4dfc17c0cfbe16
