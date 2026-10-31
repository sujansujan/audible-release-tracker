# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.5)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.5 (De-Duplicated UI & Settings Cleanup)
1. **Single Theme Control in `SETTINGS`**: Removed the duplicate theme switcher button from the top-right header bar (leaving only `[ SYNC ]` on the right) while keeping the `SYSTEM AUTO` / `DRACULA` / `ALUCARD` selector in `SETTINGS`.
2. **Streamlined `[ LIVE SEARCH ]` / `[ DIRECT ADD ]` in `TRACKED`**: Removed the redundant `Direct Manual Add Mode` toggle from `SETTINGS`—the `[ LIVE SEARCH ]` and `[ DIRECT ADD ]` switch is now directly available in `TRACKED`.
3. **Cleaner Notification Schedule Controls**: Removed the redundant `[ Reset 09:00 ]` button, keeping just `[ -1 Hour ]` and `[ +1 Hour ]`.
4. **Smart `+ Track Series` / `+ Track Author` Buttons on Expanded Cards**: Expanded release cards now only show `[ + Track Series ]` or `[ + Track Author ]` if that series or author is **not** already in your `TRACKED` list.
5. **Contextual `[ CLEAR ]` in `TRACKED`**: `[ CLEAR ]` now appears only when live catalog search results are actually open on screen.
6. **Clean Top-Left Tab Header (`[ UPCOMING ]`, `[ TRACKED ]`, `[ SETTINGS ]`)**: Removed the redundant `// US` marketplace code from the top-left header bar.
