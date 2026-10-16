# PlainText Audible Release Tracker v1.1 (Android)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Features
- **Plain Text Sports Aesthetic for Android**: Monospace typography, responsive ASCII-style frames (`+---[ ... ]---+`), framed 58dp book cover thumbnails, and 48dp touch-friendly bracketed controls.
- **Auto-Hiding Thumb-Zone Bottom Bar & Quick Filter Strip**: Bottom navigation (`< UPCOMING | TRACKED | DND | CFG >`) and quick filters (`[ ALL ] [ SERIES ] [ AUTHOR ] [ NARRATOR ] [ <=30d ]`) smoothly hide when scrolling down and reappear when scrolling up.
- **Official Dracula (Dark) & Alucard (Light) Palettes**: Instant one-tap theme toggle in the top header.
- **In-App Synopsis & Metadata Drawer (`[ + Info ]`)**: Read the full book blurb, runtime length, publisher, and edition ASINs directly inside the card without hitting Audible regional blocks.
- **Region-Safe Audible Opener & Marketplace Selector**: Opens Audible links with `?ipRedirectOverride=true&overrideBaseCountry=true`, provides a `[ Search App ]` button to open the edition licensed for your region, supports switching marketplace domains (`.com`, `.in`, `.co.uk`, `.ca`, `.com.au`), and merges duplicate US/International editions with an `[ Alt ASIN ]` toggle.
- **Live Catalog Search & One-Tap Tracking**: Search the live Audible catalog inside the `TRACKED` tab to preview covers, series, authors, and narrators and track them with a single tap.
- **One-Tap Calendar Export (`[ + Cal ]`)**: Add any upcoming audiobook release date directly to your Android calendar.
- **Do Not Disturb (DND) List & Notification Quick-Mute**: Mute any individual Book or entire Series from the app or directly from `T-7d` and `T-0d` notifications.
- **Series Gap Detector, New Pre-Order Banner, TBA Section & JSON Backup/Restore**: Full suite of release tracking and backup tools.
