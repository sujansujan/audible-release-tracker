# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.4)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.4 (Under-the-Hood Reliability Suite)
1. **Paginated Deep Catalog Sync (Up to 150 Books per Source)**: Automatically fetches up to 3 pages (`num_results=50`, `page=0..2`) sorted by `-ReleaseDate` when a prolific author or series has a full page of future/recent entries so foreign translations or box sets never push an upcoming release off page 1.
2. **Release Date Shift & Delay Detector (`[ MOVED: old -> new ]`)**: Tracks `previousReleaseDateIso` in Room (`MIGRATION_4_5`) whenever a publisher moves a pre-order's release date forward or backward, displaying the shift inside the expanded scorecard view.
3. **"Narrator TBA -> Announced" Detector (`[ NARRATOR ANNOUNCED ]`)**: Detects when an existing pre-order transitions from `Narrator: TBA` to a confirmed narrator and highlights the announcement inside the expanded scorecard view.
4. **Automatic 30-Day LRU Disk Cache Pruning (`pruneStaleDiskCache`)**: Runs a lightweight background sweep on startup that deletes cached JSON and cover files older than 30 days and enforces a hard 15 MB ceiling by evicting oldest files first.
5. **Sync Telemetry & Offline Status Line in `SETTINGS`**: Records the last sync timestamp, synced source count, error count, and offline fallback status (`Last Synced: YYYY-MM-DD HH:mm • X sources • Y errors`) inside `SETTINGS`.
