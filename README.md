# SaveIt

A native Android app (Kotlin, Jetpack Compose, Material 3) that saves videos and images from **public** posts on
Reddit, X, Instagram, TikTok, Snapchat, Facebook and Pinterest straight into your gallery.

No ads, no analytics, no trackers. SaveIt only contacts the site you download from, and GitHub when you update the
extractor engine.

## Features

- **Paste, share or copy.** Paste a link, use *Share → SaveIt* from any app, or let SaveIt spot a supported link in
  your clipboard when you open it. Share text with extra words, tracking parameters and short links
  (`vm.tiktok.com`, `pin.it`, `fb.watch`, `t.co`, `redd.it`, `v.redd.it`, Reddit `/s/` links, `snapchat.com/t/…`,
  `instagram.com/share/…`, `facebook.com/share/…`) is handled.
- **Preview before downloading.** Thumbnail, title, author, platform, and every item in the post (galleries,
  carousels, multi-photo posts, slideshows) with checkboxes and *Download all*. Pick Best / 1080p / 720p / 480p, or
  audio only (M4A/MP3).
- **Always with sound.** Sources that serve video and audio separately (Reddit, some Facebook and Instagram
  streams) are merged with ffmpeg. Every result is inspected with ffprobe, and a silent file is rejected when the
  source has audio.
- **Background queue.** Downloads run in a foreground service with a progress notification (with Cancel), are
  retried on transient errors (re-extracting the post, since CDN links expire), and show speed and percentage in
  the app.
- **Gallery-ready.** Files go to `Movies/SaveIt`, `Pictures/SaveIt` or `Music/SaveIt` through MediaStore (no
  storage permission on Android 10+).
- **History.** Thumbnails, platform, date and size. Open, share, download again, or delete (the file too).
- **Settings.** Default quality, theme and dynamic color, filename template (`{platform}_{author}_{id}_{index}` by
  default), clipboard detection, optional per-platform login, **Update extractor engine**, clear history.
- **Specific errors.** Private, deleted, login required, age-restricted, region-blocked, rate-limited, network
  down, or engine outdated, each with the action that can fix it (log in, update engine, retry).

## Supported platforms

| Platform | What works without login | Needs login | Not supported |
|---|---|---|---|
| Reddit | v.redd.it videos (merged audio), images, galleries (all images), GIFs, Redgifs/Imgur links, crossposts | quarantined/private subreddits | — |
| X | videos (highest-bitrate MP4), GIFs (as MP4), photos at `name=orig`, multi-media posts; x.com / twitter.com / fxtwitter / vxtwitter / fixupx links | protected accounts, some NSFW posts | Spaces, live broadcasts |
| Instagram | reels, video posts, photo posts, carousels (all items, full resolution) when Instagram serves them to visitors | stories, highlights, private accounts, and often everything when Instagram rate-limits logged-out visitors | — |
| TikTok | videos (watermark-free stream when available), photo slideshows (all images + optional soundtrack), short links | private accounts, age-restricted posts | lives |
| Snapchat | public Spotlight videos, public stories / public profiles | — | private snaps, chats, friends-only stories, lenses |
| Facebook | public videos, reels, watch links, photo posts, fb.watch and share links | most personal-profile and group content | private or limited-audience posts |
| Pinterest | original-resolution images (`/originals/`), video pins, GIF pins, idea pins, carousel pins, `pin.it` links | — | boards and profiles (pick a pin) |

## Known limitations

- **Platforms change constantly.** Extraction can break when a platform changes its site. See *Maintenance*.
- **Instagram and Facebook** increasingly show nothing to logged-out visitors. When a post can't be read, SaveIt
  says so and offers the login screen. Logging in uses a WebView. Only the session cookies are kept, encrypted on
  the device, and they are never backed up.
- **Reddit** throttles some networks (VPNs, data centers). You'll get a "too many requests" message; logging in or
  switching networks helps.
- **Snapchat** only exposes Spotlight and public stories. Everything private is out of reach by design.
- **TikTok watermark:** when TikTok only offers the watermarked download stream, that is what you get.
- **DRM / paywalls** are never bypassed.
- The engine (`youtubedl-android`) is GPL-3.0. SaveIt is intended for personal use; if you redistribute builds,
  comply with the GPL.

## Maintenance: when a platform breaks

1. **In the app:** *Settings → Extractor engine → Update extractor engine.* This downloads the latest stable yt-dlp
   from GitHub and fixes most breakages within days of a platform change.
2. **Bump the bundled engine:** set `youtubedlAndroid` in `gradle/libs.versions.toml` to the newest
   `io.github.junkfood02.youtubedl-android` release on Maven Central and rebuild.
3. **Fix the platform's own extractor.** Each platform has one class in
   `core/src/main/kotlin/app/saveit/core/extractor/` (`RedditExtractor`, `TwitterExtractor`, `InstagramExtractor`,
   `TikTokExtractor`, `SnapchatExtractor`, `FacebookExtractor`, `PinterestExtractor`) behind the common
   `PlatformExtractor` interface, so one can be fixed without touching the others. Unit tests with sample responses
   live in `core/src/test/`.
4. **Check it against real posts** with the verifier (see BUILD.md): it runs the exact download path the app uses
   and inspects every file with ffprobe.

## Project layout

```
core/       Pure Kotlin/JVM: URL handling, platform extractors, yt-dlp integration, download + merge pipeline, tests
app/        Android app: Compose UI, foreground download service, Room history, DataStore settings, MediaStore
verifier/   JVM CLI that downloads real posts through :core and checks the files with ffprobe
tools/      jvm-build (build :core/:verifier without an Android SDK), make-keystore.sh
```

## Responsible use

SaveIt is for saving publicly available content for personal use. You are responsible for respecting creators'
rights and each platform's terms of service.
