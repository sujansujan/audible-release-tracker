# NocturneTXT — Plain-Text Audiobook Release Tracker (v3.1)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v3.1 (7-Point AudioBookBay Reliability Suite)
1. **Spelled-Out Number & Roman Numeral Equivalence (`Book Nine` <-> `Book 9` <-> `Book IX`)**: Treats digits (`1`–`20`), spelled-out numbers (`one`–`twenty`), and Roman numerals (`i`–`xx`) as equivalent when matching book and series numbers on AudioBookBay.
2. **Series-First Title Fallback (`"Series Name Book # - Author"`)**: Matches uploads formatted either by book title (`Waybound - Will Wight`) or by series + sequence (`Cradle Book 12 - Will Wight`).
3. **Foreign-Language Upload Rejector**: Inspects the ABB post snippet and detail page (`Language:` field and `[German]` / `[Spanish]` / `[French]` tags) and rejects non-English uploads when `English Language Only` is `[ ON ]`.
4. **Conflicting Series Installment Guard**: Rejects any ABB upload whose title explicitly names a different book number (`Book 1`, `Vol 2`, `#3`) than the target release's `seriesSequence`, preventing older books in the same series from false-matching a new sequel.
5. **Apostrophe, Ampersand & Punctuation Equivalence (`&` <-> `and`, `’` <-> `'`)**: Normalizes ampersands and strips apostrophes in search queries and word comparisons so `Legends & Lattes` and `Legends and Lattes` match seamlessly.
6. **Tracker-Enriched Magnet URIs (`&tr=...`)**: Scrapes the active `udp://` and `http(s)://` tracker announce URLs directly from the matched AudioBookBay post page (with fallback to standard public trackers) so mobile torrent clients start downloading metadata immediately without waiting for DHT bootstrap.
7. **Sticky Fastest-Mirror Memory (`.lu` / `.is` / `.fi` / `.se`)**: Remembers whichever ABB mirror responded fastest and tries that mirror first on subsequent checks and browser launches.
