# Audible Releases — native Android starter

A Kotlin + Jetpack Compose app for tracking upcoming Audible US releases from selected authors and series.

## Included

- Three native bottom tabs: **Upcoming**, **Following**, **Settings**.
- Release cards rendered as text with title, series, author, narrator, length, release date, Audible URL, and Calendar action.
- Date-grouped Upcoming feed with month navigator styling, All / 7 Days / Month / Released filters, countdown labels, and live/sync timestamps.
- First-launch onboarding, structured author/series follow sections, release detail view, calendar confirmation dialog, and persisted dark terminal mode.
- Upcoming cards show only title, series, author, narrator, and release date; **SHOW MORE** opens the full release view with the synopsis.
- The bottom navigation is text-only (`[UPCOMING]`, `[FOLLOWING]`, `[SETTINGS]`) rather than icon-based; the low-value Search tab was removed.
- Release metadata now includes a cached synopsis; existing Room databases migrate from version 1 to version 2 automatically.
- Upcoming cards are compact and show the core release metadata plus **SHOW MORE**. The date navigator was removed to reduce empty space.
- Upcoming ordering places future releases first, followed by already released titles. The feed keeps its scroll position when opening and backing out of a release detail.
- Alucard uses a dark green released-status color for readable contrast on the light paper background; Dracula keeps its bright green status color.
- Settings supports plain-text export/import of followed authors and series. Audible product URLs are normalized to the corresponding `/pd/` book link, and follow matching normalizes punctuation and ampersands.
- Local Room database for follows and cached release metadata.
- Daily WorkManager refresh and Android notification permission/channel.
- Audible public Coming Soon and New Releases catalog fetchers in one isolated repository class; the worker merges them so a title leaving Coming Soon on release day can still trigger an alert.
- Audible catalog API integration modeled after the public MediaTracker approach: ASIN-based products, contributor/series metadata, merchandising synopsis, and canonical `audible.com/pd/{ASIN}` links.
- The feed uses explicit **NEXT 7 DAYS**, **LATER**, and **RELEASED** sections, aligned metadata rows, text status badges, match reasons, compact `[DETAILS]` / `[CALENDAR]` / `[AUDIBLE]` actions, and a sync/count summary.
- All displayed text is selectable. The top header and bottom text navigation collapse during feed scrolling; the Following and Settings tabs use command-style text controls and plain-text import/export.
- The feed now uses a stable fixed layout: animated chrome hiding was removed because it caused scroll stutter. The default bottom tab is **SORT**, showing upcoming releases only; released titles can still be viewed through the explicit filters.
- The non-working **OPEN AUDIBLE** action was removed from cards and detail screens. Calendar export remains available.
- High-refresh scrolling is optimized with a fixed, non-animated layout, stable ASIN keys, explicit lazy content types, a full-size lazy viewport, and selection containers scoped to cards/screens instead of wrapping the entire feed gesture surface.

## Visual direction

The UI follows the supplied Plain Text Sports reference: monospace-first typography, an off-white paper background, thin gray rules, blue link accents, uppercase section labels, rectangular controls, and compact ASCII-inspired release cards. It intentionally avoids cover art and decorative gradients so the release text stays scannable.

The light theme is named **Alucard** and uses a warm paper background with dark ink and magenta links. The dark theme is named **Dracula** and uses Dracula’s charcoal background, lavender links, blue-gray rules, green released status, and amber upcoming status. The release detail view is wrapped in the active themed surface so it follows the same mode as the rest of the app.

## Build

Open this directory in Android Studio Hedgehog or newer. Android Studio will install the Android SDK and Gradle dependencies. Then run the `app` configuration on an Android 8.0+ device or emulator.

### GitHub Actions

The workflow at `.github/workflows/android.yml` runs on pushes to `main`/`master`, pull requests, and manual dispatch. It installs Android API 35 and Build Tools 35.0.0, validates the Gradle project, builds `assembleDebug`, and uploads `app-debug.apk` as the `audible-releases-debug-apk` artifact for 14 days.

The app and CI use an explicit JVM 17 bytecode target for both Java and Kotlin/KSP. This avoids the `Inconsistent JVM-target compatibility detected` failure that occurs when GitHub’s JDK 21 makes KSP target 21 while Java remains at 1.8.

### MediaTracker attribution

The API integration approach is attributed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). MediaTracker is MIT-licensed; this app does not copy its source code.

### Updating an installed APK

The application ID remains `com.example.audiblereleases` and the version is now `1.1` / version code `2`. The project currently uses the normal Android debug signing setup; no keystore or GitHub signing secrets are required. Android updates work when the new APK is signed with the same debug key, such as repeated builds from the same local Android Studio installation. GitHub-hosted runners may generate different debug keys between runs, so an Actions APK can still require an uninstall before installation.

The Gradle wrapper/configuration check passes in the sandbox. The sandbox does not contain an Android SDK, so an APK build could not be performed here; Android Studio will install or select the SDK when you open the project.

## Important implementation note

`AudibleRepository.kt` uses Audible’s undocumented catalog endpoint with public metadata response groups. The endpoint may change or become unavailable; the app does not access account cookies or private endpoints. The app opens books at the canonical `https://www.audible.com/pd/{ASIN}` URL rather than the redirecting bare `audible.com` host. Android back gestures are handled by Compose `BackHandler` so SHOW MORE returns to the feed instead of closing the app.

## Calendar action

The `ReleaseCard` action is the UI hook. Before shipping, replace the two small helpers in `MainActivity.kt` with `Intent.ACTION_INSERT` and `CalendarContract.Events` so the user can review and save an event in their selected calendar. This is the safest native flow because it avoids requesting calendar read/write permission:

```kotlin
val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
    .putExtra(CalendarContract.Events.TITLE, "${item.title} — Audible release")
    .putExtra(CalendarContract.Events.DESCRIPTION, "${item.author}\n${item.url}")
    .putExtra(CalendarContract.Events.ALL_DAY, true)
    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, releaseMillis)
startActivity(intent)
```

The app should parse the Audible US date as `MM-dd-yy`, validate it, and use the user’s calendar app to finish the event.
