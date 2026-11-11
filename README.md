# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.9)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.9 (Precision AudioBookBay Verification)
1. **Strict Author-Only Word-Boundary Verification**: Never uses narrator or full-cast tokens (`Ray Porter`, `John`, `Full cast`) when verifying an ABB post—requires the actual **Author's surname or pen-name** (`\bpercy\b` or `\bweir\b` for *Exoplanet*; `\bbaldree\b` for *Tales from the Territory*; `\bshirtaloon\b` or `\bdeverell\b` for *He Who Fights with Monsters 13*) to appear as a whole word.
2. **ABB `Posted:` Date Cutoff Guard**: Parses `Posted: DD Mmm YYYY` from the AudioBookBay search/post page and rejects any upload posted more than 10 days before the book's Audible `releaseDateIso`, making it impossible for an older book with the same title (like Craig Robertson's 2019 *Exoplanet*) to false-match a new release.
3. **Unnumbered Standalone Series Titles Fix (*Tales from the Territory*)**: Only requires a sequence number in the ABB post title if that number actually appears in `bookTitle` (like `13` in *He Who Fights with Monsters 13*), so standalone-titled series entries like *Tales from the Territory* (`Legends & Lattes #5`) match `Tales from the Territory - Travis Baldree` cleanly.
4. **Automatic DB Reset of False-Positive ABB Flags (`MIGRATION_6_7`) + On-Expand Check**: Resets any stale `v2.7`/`v2.8` ABB flags on upgrade and automatically triggers a single-book ABB verification whenever you tap to expand an already-released card (`releaseDateIso <= today`).
