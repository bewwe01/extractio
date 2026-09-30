# Verification status

## Unit tests (`./gradlew -p tools/jvm-build test`, all passing: 97 tests)

| Area | What is checked |
|---|---|
| URL extraction | Share-sheet text from TikTok / Instagram / X / Snapchat, Markdown links, parentheses, glued text, ellipses, CJK brackets, non-breaking spaces, scheme-less links (`pin.it/…`, `fb.watch/…`) |
| Normalization | Canonical URLs + tracking removal for all 7 platforms, incl. fxtwitter/vxtwitter/fixupx, old/np/m.reddit, `redd.it`, `/gallery/`, Instagram `/reels/`, `/s/<base64>` highlight links, TikTok `m.`/embed/photo, Facebook login `?next=`, Pinterest ccTLDs and slug pins |
| Short links | vm.tiktok.com, pin.it (via api.pinterest.com), t.co meta-refresh, Reddit `/s/` relative redirects, v.redd.it, facebook.com/share, fb.watch, instagram.com/share (canonical tag), t.snapchat.com, dead links → "deleted" |
| X token | Syndication token matches Node.js/V8 `Number.prototype.toString(36)` on 12 real IDs |
| Parsers | Reddit gallery/video/image/GIF/crosspost/Redgifs/text/private, X photos+video+GIF/tombstone/quote, Instagram GraphQL carousel/reel/embed/product API, TikTok video/slideshow/private/deleted, Snapchat Spotlight/story/profile, Pinterest image/video/idea pin/carousel/GIF, Facebook DASH+progressive/photo/login wall, DASH manifests, yt-dlp JSON |
| Pipeline (real ffmpeg) | Video-only + audio-only streams merged into one MP4 with both streams (skipping a dead audio URL); silent result refused when the source has audio; alt-URL fallback with extension fix; HTML-instead-of-media rejected; audio extraction; engine→direct fallback |
| Errors | Real yt-dlp error strings → private / deleted / login / age / region / rate-limit / network / engine-outdated |

The parser fixtures are hand-built from each platform's response structure (the same fields yt-dlp reads).
They are not captured live responses, because this build environment had no access to the platforms.

## Build (GitHub Actions run 36788877170)

`./gradlew test lintDebug assembleDebug assembleRelease` succeeded with zero errors on the first CI run.

| APK | Size | Engine native libs packaged |
|---|---|---|
| app-arm64-v8a-release.apk | 67 MB | libpython, libpython.zip, libffmpeg, libffmpeg.zip, libffprobe, libqjs |
| app-armeabi-v7a-release.apk | 61 MB | same, armeabi-v7a |
| app-universal-release.apk | 206 MB | arm64-v8a, armeabi-v7a, x86, x86_64 |
| debug variants | 75 / 68 / 213 MB | same |

`apksigner verify` passed for all six APKs (v2 scheme). CI release APKs are signed with the debug key, because CI
has no release keystore unless you add the secrets described in BUILD.md.

The `smoke-test` job installs the **release** APK on an API 34 emulator, launches it, shares a link into it, and
fails on any crash or if the engine does not report "Engine ready".

## Real-URL verification (GitHub Actions run 36788982793, yt-dlp 2025.11.12, quality Best)

The verifier runs the app's resolver and downloader and inspects every file with ffprobe. It ran on GitHub's
Ubuntu runners because this build environment couldn't reach the platforms.

| Platform | Type | Result | Notes |
|---|---|---|---|
| Reddit | video ×2 | NEEDS LOGIN | Reddit answered 403 to the GitHub datacenter IP (`.json` API and yt-dlp alike). Expected from cloud IPs; retest from a phone or home connection. |
| Reddit | image / gallery / GIF | NOT RUN | No stable public URL yet |
| X | multi (2 videos) | **PASS** | via x-syndication: 2 × MP4 720×1280, H.264 + AAC, 113.5 s and 102.2 s |
| X | video (user-provided post `DramaAlert/status/2105063117147484566`) | **PASS** | via x-syndication (direct): MP4 1280×720, H.264 + AAC, 19.3 s, 5.7 MB (run 36791020310) |
| X | video (old candidate) | FAIL (dead post) | 2015 "Amplify" card video; the host domain no longer exists (yt-dlp: HTTP 500 Domain Not Found) |
| X | GIF | FAIL (dead post) | "This Post was deleted by the Post author" |
| X | image | NOT RUN | No URL yet |
| Instagram | reel / carousel | NEEDS LOGIN | Instagram serves nothing to logged-out datacenter IPs (yt-dlp: "rate-limit reached or login required") |
| Instagram | image | NOT RUN | No URL yet |
| TikTok | video | FAIL | TikTok reported "Video unavailable" / "IP blocked" to the runner; likely IP blocking, not confirmed |
| TikTok | short link | FAIL (dead link) | `vm.tiktok.com/ZTR45GpSF` now redirects to the home page. Now reported as "link expired" instead of "unsupported" |
| TikTok | slideshow | NOT RUN | No URL yet |
| Snapchat | Spotlight | FAIL (dead post) | HTTP 404, the Spotlight was removed |
| Snapchat | public story | NOT RUN | No URL yet |
| Facebook | watch video | **PASS** | via yt-dlp: MP4 720×1280, H.264 + AAC, 136.2 s |
| Facebook | reel | **PASS** | via yt-dlp: MP4 480×848, VP9 + AAC, 9.6 s |
| Facebook | photo | NOT RUN | No URL yet |
| Pinterest | video pin ×2 | **PASS** | via pinterest-resource (direct): MP4 1080×1920 H.264 + AAC 57.7 s; MP4 540×960 H.264 + AAC 14.9 s |
| Pinterest | image / idea pin | NOT RUN | No URL yet |

**Summary:** all 6 downloads from live posts passed (X, Facebook, Pinterest), each with video and audio. Reddit
and Instagram refuse cloud IPs, so they need a run from a residential connection (see BUILD.md). The candidate
URLs from yt-dlp's test suite for X video/GIF, TikTok and Snapchat are dead and need replacing. Rows marked
NOT RUN need real public post URLs.
