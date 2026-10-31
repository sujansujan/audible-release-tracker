# NocturneTXT — Plain-Text Audiobook Release Tracker (v1.8)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v1.8
- **Official Audible Author Page Thumbnails**: Followed Authors now resolve their exact portrait image from their official Audible author page (`audible.com/author/{authorAsin}` / `amzn-author-media-prod` via [Audnexus](https://github.com/laxamentumtech/audnexus) and direct Audible author page parsing).
- **Concurrency Cap (`Semaphore(4)`) + Gzip + Retry Backoff**: Parallel catalog syncs are throttled to 4 concurrent connections with automatic Gzip decompression and HTTP 429/5xx retry backoff so large followed lists never rate-limit or drop results.
- **Boot & Update Notification Rescheduler (`BootRescheduleReceiver`)**: Automatically re-registers the daily `T-7d` and `T-0d` release check after device reboots, app updates, or timezone changes.
- **5-Second `[ UNDO ]` Banner on Tracked Deletion + Atomic Room `@Transaction` Sync**: Prevents accidental removal of followed sources in `TRACKED` and eliminates UI flicker during catalog refreshes.
