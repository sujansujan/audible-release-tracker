# NocturneTXT — Plain-Text Audiobook Release Tracker (v3.3)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v3.3 (Fixed & Diagnostic AudioBookBay Search)
1. **Restored Exact Working ABB Search URL (`/?s={query}&cat=undefined%2Cundefined`)**: Restores the exact category query parameter required by AudioBookBay's search handler.
2. **Removed Spoofed `Origin` + `Sec-Fetch-Site: same-origin` `GET` Header Combo**: Uses clean browser navigation headers so Cloudflare doesn't flag the `GET` request as an automated script, with automatic headless Chromium `WebView` fallback (`AbbWebViewFetcher`) when challenged.
3. **Fixed Title & Author Matcher (`AudibleApiClient.kt`)**:
   - Allows 1-word tolerance on 3+ word titles (`Tales from the Territory`, `He Who Fights with Monsters 13`).
   - Removed the overly aggressive `hasConflictingInstallmentNumber` and `DateTimeFormatter` filters that were rejecting valid ABB posts.
   - Only rejects older books if their `Posted:` year on ABB is strictly older than `releaseYear - 1` (preventing 2019 *Exoplanet* from matching a 2026 release while never rejecting valid 2026 uploads).
4. **Live Diagnostic Status Toast on `[ Check ABB ]`**: Tapping `[ Check ABB ]` on any expanded card shows the exact result (`[ Found on ABB! Magnet ready ]` or `[ ABB: 0 matches in N posts for 'Title' ]` or `[ ABB: HTTP 403 / Mirror unreachable ]`).
