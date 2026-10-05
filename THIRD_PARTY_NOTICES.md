# Third-party notices

## MediaTracker Audible integration reference

This project’s Audible catalog API approach was informed by the public [bonukai/MediaTracker](https://github.com/bonukai/MediaTracker) project, specifically its use of Audible’s catalog endpoint at `https://api.audible.{domain}/1.0/catalog/products`, response groups, ASIN-based product details, contributor mapping, and canonical `/pd/{ASIN}` links.

MediaTracker is licensed under the MIT License. The project is not copied into this Android application; the current Kotlin implementation is original. This notice is included as attribution and for reference.

Copyright (c) 2022 bonukai <bonukai@protonmail.com>

Permission is hereby granted, free of charge, to any person obtaining a copy of the MediaTracker software and associated documentation files, to deal in the software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the software, subject to the conditions in the MediaTracker MIT license.

The full license is available at:
https://github.com/bonukai/MediaTracker/blob/main/LICENSE.md

## Audible API

The Audible catalog API used here is undocumented and may change or be unavailable. The app uses it only for public audiobook metadata and links back to Audible for the corresponding product.
