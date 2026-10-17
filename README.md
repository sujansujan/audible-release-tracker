# NocturneTXT — Plain-Text Audiobook Release Tracker (v1.6)

A lean, native Android app inspired by [Plain Text Sports](https://plaintextsports.com/) with official [Dracula and Alucard](https://draculatheme.com/spec) themes for tracking upcoming Audible releases (`api.audible.com/1.0/catalog/products`).

## Key Highlights in v1.6
- **Native Android Back Navigation Stack (`BackHandler`)**: Pressing the Android system back button/gesture closes `ABOUT` if open, then steps back to the previous tab you were in, then returns to the Home (`UPCOMING`) tab, and finally exits cleanly to your phone's home screen.
- **120Hz Buttery-Smooth Scrolling Engine**:
  - Precomputes all card display models (`@Immutable` `ReleaseCardUiModel`), date math, and series gap checks off the UI scroll path.
  - Replaces nested horizontal scroll containers inside cards with zero-allocation `FlowRow` action bars.
  - Animates the auto-hiding bottom bar on the GPU RenderThread via `Modifier.graphicsLayer { translationY = ... }` with zero full-screen recompositions.
  - Downsamples book cover thumbnails to `160x160` px in an expanded 200-item `LruCache` and builds an R8-optimized Release APK in GitHub Actions.
