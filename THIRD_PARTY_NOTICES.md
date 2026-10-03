# Third-party notices

jmail is GPL-3.0-or-later. The APK bundles the libraries below; all of them
are under licenses compatible with GPLv3.

| Library | Version | License |
|---|---|---|
| Bouncy Castle (`bcprov`, `bcpg`) | 1.79 | Bouncy Castle Licence (MIT-style) |
| Apache James Mime4j (`core`, `dom`) | 0.8.11 | Apache-2.0 |
| Apache Commons IO (via Mime4j) | 2.13.0 | Apache-2.0 |
| OkHttp, Okio | 4.12.0, 3.6.0 | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | 2.0.21, 1.9.0, 1.7.1 | Apache-2.0 |
| AndroidX (Core, Activity, Lifecycle, Compose, Material 3, WorkManager, Room, ...) | see `app/build.gradle.kts` | Apache-2.0 |
| Guava ListenableFuture (via WorkManager) | 1.0 | Apache-2.0 |
| JetBrains annotations | | Apache-2.0 |

Build and test only, not in the APK: the Gradle wrapper (Apache-2.0) and
JUnit 4 (EPL-1.0).

Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0>
Bouncy Castle Licence: <https://www.bouncycastle.org/licence.html>

## NOTICE files

```
Apache James :: Mime4j :: Core
Copyright 2004-2024 The Apache Software Foundation

Apache James :: Mime4j :: DOM
Copyright 2004-2024 The Apache Software Foundation

Apache Commons IO
Copyright 2002-2023 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Bouncy Castle Licence

```
Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files (the "Software"), to deal in the Software
without restriction, including without limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```
