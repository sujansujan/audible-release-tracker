# NocturneTXT — Plain-Text Audiobook Release Tracker (v2.7)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v2.7 (Tested & Verified ABB Checker)
1. **Verified AudioBookBay (`ABB`) Availability & Magnet Checker**:
   - Sends full browser `Origin`, `Referer`, and `Sec-Fetch-*` headers required by AudioBookBay's anti-bot filter.
   - Queries the clean core book title (`?s=he+who+fights+with+monsters+13`) without appending pen-name/real-name hybrids (`Shirtaloon` vs `Travis Deverell`), and checks any credit name (`Shirtaloon` or `Deverell` or `Heath Miller`) across both the `<div class="postTitle"><h2><a href="...">` heading and the surrounding `<div class="post">` block.
   - Supports both relative (`/audio-books/...`) and full (`https://audiobookbay.../abss/...`) post URLs across `.lu`, `.is`, `.fi`, and `.se` mirrors.
   - Sorts candidate releases newest-first (`sortedByDescending { it.releaseDateIso }`) so recent releases like *He Who Fights with Monsters 13* are always checked first, and adds a 1-tap **`[ Check ABB ]`** button inside the expanded card for instant on-demand verification.
2. **Series-Author & ASIN Pinning**: Prevents same-name series by different authors from colliding.
3. **1-Tap `[ Magnet ]` Launch / Copy**: Automatically extracts the 40-char BitTorrent `Info Hash` from the matched ABB post page.
