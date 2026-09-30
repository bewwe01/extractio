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

## Real-URL verification

See the table below (filled from the verifier run).
