# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.8)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.8 (Unified Design Language & De-Clutter Suite)
1. **Standardized `56dp` Thumbnails & Semantic Borders Everywhere**: Every card across `UPCOMING`, `TRACKED` live search results, and `TRACKED` followed sources uses the exact same `56dp` thumbnail with its semantic accent border.
2. **Unified `14sp` Bold Uppercase Card Titles & `12sp` Metadata Grid**: All primary card titles across `UPCOMING` and `TRACKED` share the same `14sp` bold uppercase typography and `12sp` metadata lines.
3. **Standardized `+=== SECTION ===+` ASCII Dividers**: `UPCOMING` month headers, `DND` headers, `LIVE CATALOG RESULTS`, and `FOLLOWED SOURCES` all use the same `AsciiSectionHeader` (`+=== ... ===+`).
4. **Framed `1dp` Terminal Input Fields (`AsciiTextField`)**: Text inputs in `TRACKED` now feature a crisp `1dp` border (highlighting in `cyan` on focus) and a `>` terminal prompt prefix.
5. **Docked Bottom Filter & Sort Strip on Both `UPCOMING` & `TRACKED`**: `BottomThumbBar` now docks the `FLT:` and `SRT:` horizontal filter/sort strip on `TRACKED` just like `UPCOMING`.
6. **Consolidated Single-Location Status Badges**: Status tags (`[ NEW ]`, `[ ABB ]`) appear once in the card's top `+---[ ... ]---+` frame header with no duplicate badge line inside the card body.
7. **Clean `TRACKED` Card Headers & Body Author Line**: Followed `SERIES` and `BOOK` cards display `+---[ SERIES • 1 UPCOMING ]---+` in the header and `Author: <Name>` cleanly inside the card body.
8. **`Match: [...]` Moved to Expanded View**: Collapsed cards in `UPCOMING` now show a tight 3-line block (`Title`, `Series`, `Author/Narrator`) matching the `56dp` thumbnail height, with `Match: [...]` displayed alongside `Runtime` and `Publisher` when expanded.
9. **De-Cluttered `TRACKED` Followed Rows**: Removed the duplicate `[ ABB ]` button from compact followed rows so long author/series names and `-1` / `+1` sequence buttons never wrap.
10. **Context-Aware Top Bar (`[ SYNC ]` Hidden on `SETTINGS` / `ABOUT`)**: Hides `[ SYNC ]` when viewing `SETTINGS` or `ABOUT`.
