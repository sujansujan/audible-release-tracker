# PlainText Audible Release Tracker (Android)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming US Audible releases (`api.audible.com/1.0/catalog/products`).

## Features
- **Plain Text Sports Aesthetic for Android**: Monospace typography, responsive ASCII-style frames (`+---[ ... ]---+`), and 48dp touch-friendly bracketed controls (`[ + Track ]`, `[ Audible -> ]`, `[ DND Book ]`, `[ DND Series ]`).
- **Official Dracula (Dark) & Alucard (Light) Palettes**: Instant one-tap theme toggle in the top header.
- **Multi-Source Tracking & Clash Deduplication**: Track by **Series**, **Author**, **Narrator**, or **Individual Book (ASIN)**. When a book matches multiple tracked sources, it is deduplicated into a single card displaying all matched tags (`Match: [SERIES] [AUTHOR] [NARRATOR]`).
- **Do Not Disturb (DND) List**: Mute any individual Book or entire Series so it never surfaces in future Author/Narrator syncs or notifications.
- **T-7d & T-0d Notifications with Quick-Mute Actions**: Background daily checks via `WorkManager` aligned to your configurable delivery hour, with `[ Mute Book ]` and `[ Mute Series ]` buttons directly inside notifications.
- **Language & Edition Auto-Filters**: Automatically filter out non-English translations, abridged editions, or dramatized adaptations.
- **Series Gap Detector**: Alerts you with `[ ! GAP: Book #(N-1) Unread ]` if an upcoming release is ahead of your last completed book in that series.
- **New Pre-Order Banner, TBA Section & JSON Backup/Restore**: Highlights newly discovered pre-orders (`[ NEW PRE-ORDER ]`), groups unscheduled titles in a collapsible `[ TBA / UNSCHEDULED ]` section, and exports/imports plain-text `.json` backups.

## Building with GitHub Actions
1. Push this repository to GitHub (`main` or `master` branch).
2. Open the **Actions** tab and select **Build PlainText Audible APK**.
3. Download the compiled `plaintext-audible-debug-apk` artifact from the finished run.
