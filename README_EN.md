<p align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" width="112" alt="Smartisan Music icon" />
</p>

<h1 align="center">Smartisan Music Revived</h1>

<p align="center">
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin" alt="Kotlin 2.4.0" /></a>
  <a href="https://developer.android.com/build"><img src="https://img.shields.io/badge/AGP-9.3.2-3DDC84?logo=android" alt="AGP 9.3.2" /></a>
  <a href="https://developer.android.com/about/versions/oreo/android-8.1"><img src="https://img.shields.io/badge/minSdk-27-3DDC84?logo=android" alt="minSdk 27" /></a>
  <a href="https://developer.android.com/media/media3"><img src="https://img.shields.io/badge/Media3-1.10.1-4285F4?logo=android" alt="Media3 1.10.1" /></a>
</p>

<p align="center">
  <a href="README.md">中文</a> · English
</p>

## About this project

This project is a second-round development based on [**SmartisanMusic-Revived**](https://github.com/Mangi-11/SmartisanMusic-Revived) by [**Mangi-11**](https://github.com/Mangi-11/SmartisanMusic-Revived/commits?author=Mangi-11).

[**Mangi-11**](https://github.com/Mangi-11/SmartisanMusic-Revived/commits?author=Mangi-11) added Netease Cloud Music online features in an early version, which is why I abandoned the official Netease Cloud Music app entirely. Those features were removed in a later update. This project brings the Netease online features back and adds a number of improvements and features tailored to my own usage. The main differences from the upstream project are:

### 1. Netease Cloud Music online features

Most of the code comes from Mangi-11's deleted old repository, with part of the UI rewritten in modern Compose.

1. **Listen Together**: create a room, or join a room shared by a friend, to listen together with synchronized playlists and playback progress.
2. **Online music sharing**: share online tracks.
3. **Current quality and bitrate display**: shown below the track title on the playback screen, alongside the artist.
4. **Style recommendations**: daily recommendations gain a style-based feed.
5. **Online playlist import**: online playlists can be added to a local playlist.
6. **Merged account favorites**: once signed in, "Loved Songs" shows the tracks from the account's liked playlist alongside local favorites.
7. **Improved sign-in flow**: phone number, QR code, and web sign-in are all supported.
8. **Smartisan-style pull to refresh**: the Cloud Music page gains a Smartisan-style pull-to-refresh, with component motion taken from [Mail_7.1.0.apk](https://github.com/People-11/SmartisanOS_APP_Port/blob/master/Mail_7.1.0.apk) inside [**SmartisanOS_APP_Port**](https://github.com/People-11/SmartisanOS_APP_Port) by [**People-11**](https://github.com/People-11/SmartisanOS_APP_Port/commits?author=People-11).

### 2. Appearance

1. An optional Netease Cloud Music classic vinyl turntable style is added; the original style remains the default.
2. Lyrics are no longer cut off abruptly on the lyrics page; they fade out instead.

### 3. Player

After my device updated to HyperOS 4, the system's built-in audio decoders all stopped working, which left players without a bundled decoder unable to play FLAC and other high-quality audio. This project therefore adds a bundled `media3-flac` extension module for decoding.

### 4. Lyrics and SuperLyric

The SuperLyric API is integrated so the currently playing lyrics (including word-timed lines and translations) are published to SuperLyric, letting desktop and overlay lyric modules pick them up and display them. When no such module is installed, it degrades silently without affecting playback.

### 5. Other

A number of other changes that are not visible on the surface.

---

## Project overview

Smartisan never saw itself as a company concerned with visuals alone. A beautiful interface was only the beginning; a product still had to solve real problems. Design, functionality, and interaction were meant to feel coherent—easy to understand on first use, yet rich enough to reveal small surprises over time. The “artisan” in Smartisan was not about placing a polished skin over a product, but about caring for every touch, response, and pause.

Smartisan Music is one of the clearest expressions of that idea. Its turntable, tonearm, scratching, and vinyl crackle make digital music feel tangible, while songs, albums, and the library remain calm and legible. The physical playfulness should never come at the expense of playback or organization; what deserves to be preserved is the balance between texture, order, and utility.

Smartisan OS has left the stage, so this project uses Smartisan Music 8.1.0 as its visual and interaction reference and rebuilds it with a modern Android stack. The interface is built entirely with Jetpack Compose and custom Smartisan components, preserving the original drawables, NinePatch assets, selectors, visual language, and layout proportions. Media scanning, background playback, queues, favorites, playlists, and persistence are rebuilt entirely on public Android APIs. Alongside the complete local playback experience, optional NetEase Cloud Music online features are available: search, recommendations, playlists, albums, and lyrics need no account, while account playlists and "Loved Songs" sync require a voluntary sign-in. Credentials are stored encrypted on the device only.

## Improvements over the original

- **Compose UI**: The app shell, library, search, favorites, playlists, settings, and playback screen use Compose. Screens are organized by feature, with shared Smartisan components for resource drawing, title bars, lists, and gestures.
- **Natural interface motion**: Album and artist grids reveal items in sequence, covers shrink into list rows, and dragged items make room with spring animations. Hierarchical navigation keeps the title bar stationary while staging its icons and sliding content horizontally. Full-screen pages such as settings slide vertically as a whole; panels and the sleep timer wheel use Compose animations, retaining the existing visual language and interactions.
- **Modern local playback architecture**: Media3 `MediaLibraryService`, ExoPlayer, and MediaSession power background and lock-screen playback, media notifications, headset and Bluetooth controls, and queue and position recovery after process restarts.
- **Rebuilt local library**: MediaStore indexes songs and provides song, album, artist, genre, and folder views, along with library exclusions, rescanning, sorting, filtering, and an alphabetical sidebar.
- **Rebuilt favorites and playlists**: The original favorites and user-created playlists are retained, with persistence and play statistics reimplemented in Room. The queue, current item, and playback position are saved and restored as well.
- **Expanded playback screen**: Embedded lyrics, a sleep timer, and a reorderable queue complement the original turntable and controls.
- **Switchable turntable styles**: Two turntable styles ship in the app, "Original Turntable" and "Netease Cloud Music Turntable", switchable from Settings and persisted. The original keeps the Smartisan Music 8.1.0 skeuomorphic deck; the Netease style uses the vinyl artwork and tonearm SVG from Netease Cloud Music for macOS 3.1.11, with its own geometry, drag mapping, and needle animation calibration.
- **New personalization options**: Custom artist separators, reorderable and pinnable bottom navigation, switchable Home screen icons, and lightweight sound effects are added. The yellow vinyl icon from realme UI 7.0 Music is the default, with color and monochrome layers adapted to Android's adaptive-icon specification, while the original icon remains available. Sound effects include several presets and a custom equalizer curve.
- **Refined turntable interaction**: Tonearm dragging, vinyl rotation, scratching, crackle audio, and playback-state transitions are reimplemented for modern touch handling, lifecycles, and frame timing.
- **Richer library actions**: Multi-select, swipe actions, playlist insertion, audio-file sharing, and version-appropriate MediaStore deletion authorization are supported. Audio can also be opened directly from file managers and other apps.
- **Android 8.1 and later support**: Separate compatibility paths cover legacy and scoped storage, system bars, gesture navigation, display cutouts, WindowInsets, and standard back handling without replacing the original visual language.
- **Modern data architecture**: Room, DataStore, Coroutines, and StateFlow manage the library, favorites, playlists, settings, and playback state without private Smartisan OS services or system-signature capabilities.
- **Removed legacy baggage**: Business code is written in Kotlin and retains only the resources and public APIs required by the current implementation. The original background services, databases, and settings migrations are not carried forward.
- **Theme selection**: Supports system-following, light, and dark modes through a Compose page that reproduces the existing radio-style settings layout. Visuals remain resource-driven through `values-night` and same-name `drawable-night` variants. The original 8.1.0 had no dark mode, so the night visuals are this project's own design: the charcoal palette comes from the sibling Smartisan Weather revival (page `#25282D`, title bar `#292C31`, cards `#34373C`), and the night bitmaps are generated from the original assets by `tools/generate_night_drawables.py` (proportional darkening or white-out with alpha and nine-patch markers preserved; safe to re-run). The red and blue brand accents are shared by both themes.

## Current features

- Local audio permission flow, scanning, reindexing, and library folder exclusions
- Song, album, artist, genre, and folder browsing, with gallery-style album artwork previews
- Sorting, filtering, alphabetical navigation, blue list tap feedback, button press feedback, list multi-select with blue highlights, and swipe actions
- Favorites, user-created playlists, and play statistics
- System-following, light, and dark themes with a charcoal night palette
- Background playback, media notifications, and headset and Bluetooth controls
- Sequential, shuffle, repeat-one, and repeat-all playback modes
- Expandable queue, drag-to-reorder, and queue and position recovery
- Vinyl turntable, draggable tonearm, scratching, and crackle audio
- Two turntable styles, "Original Turntable" and "Netease Cloud Music Turntable", switchable and persisted from Settings
- Static, line-synchronized, and word-timed lyrics embedded in audio files
- Original, Bass, Clear, Vocal, Rock, and custom sound profiles
- Sleep timer and system music volume control
- External audio opening, audio-file sharing, and MediaStore-backed media deletion
- Custom artist separators, bottom-navigation order and pinned items, and switchable app icons

Cloud Music (optional; browsing and search need no account, while account features require a NetEase Cloud Music login):

- Account login with credentials encrypted and stored locally on the device
- Online search and playback; playback URLs are resolved automatically, refreshed periodically, and the audio stream is cached to disk
- Online lyrics (original, word-timed, and translations) and quality selection
- Home recommendations: banner carousel, daily picks, recommended playlists, charts, new releases, and popular artists; daily picks prefer the account feed and fall back when signed out
- Playlist, album, and artist detail pages with play-all and shuffle; radio listening
- Two-way synchronization of likes with the account's "favorites", with the local loved-songs list merging local and online tracks
- Playlist management: add tracks to a playlist, remove them, and create or delete playlists
- Listen Together: create or accept an invitation, sync playback and queue between two devices, and show connection duration
- Publishes live lyrics to SuperLyric for desktop and overlay lyric modules

## Permissions and privacy

The app is a local player by default. The `INTERNET` permission is used for online music requests. Guests can browse recommendations, search, and detail pages without signing in, and playback falls back to the guest path; account features such as account playlists and like sync require a voluntary NetEase Cloud Music login. Login credentials are stored encrypted via `EncryptedSharedPreferences` on the device only, and the app never uploads your songs, artwork, lyrics, or library metadata.

- Android 13 and later use `READ_MEDIA_AUDIO` to read device audio. Android 8.1 through Android 12 use the version-limited `READ_EXTERNAL_STORAGE` permission.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is used only to keep user-initiated playback and its media notification active in the background.
- `MODIFY_AUDIO_SETTINGS` supports playback effects and system music volume control, while `VIBRATE` provides interaction feedback.
- For song deletion, Android 11 and later use the system batch confirmation flow, while Android 10 grants access per file. Android 8.1 and Android 9 request `WRITE_EXTERNAL_STORAGE` only after the user confirms deletion, then delete through MediaStore.
- The app does not request modify-system-settings, location, camera, microphone, contacts, SMS, overlay, or accessibility permissions.

## Screenshots

<p align="center">
  <img src="docs/images/screenshot-playback.jpg" width="200" alt="Smartisan Music playback screen" />
  <img src="docs/images/screenshot-lyrics.jpg" width="200" alt="Smartisan Music lyrics screen" />
  <img src="docs/images/screenshot-albums.jpg" width="200" alt="Smartisan Music albums screen" />
</p>

Album artwork, artist information, and music content visible in screenshots remain the property of their respective rights holders and are shown only to demonstrate the interface.

## Tech stack

| Category | Technology |
| --- | --- |
| Build | Android Gradle Plugin `9.3.2`, Gradle `9.5.0`, JDK 21 (Java 11 bytecode) |
| Language | Kotlin `2.4.0` |
| UI | Jetpack Compose, custom Smartisan components, Drawable / NinePatch rendering |
| Playback | Media3 `1.10.1`, ExoPlayer, MediaLibraryService, MediaSession, purpose-built libFLAC extension decoder (`:media3-flac`) |
| State | Lifecycle, StateFlow, Coroutines |
| Storage | Room `2.8.4`, DataStore `1.2.1`, MediaStore |
| Online | Purpose-built Netease client (`HttpURLConnection` with AES/RSA signing), Coil 3 (artwork loading) |
| SDK | `minSdk 27` / `targetSdk 36` / `compileSdk 37` |

See [UI architecture](docs/ui-architecture.md) for package boundaries, shared components, and state ownership.

## Third-party libraries

Live lyrics publishing is powered by [SuperLyricApi](https://github.com/HChenX/SuperLyricApi) (LGPL-2.1, via JitPack).

The Netease turntable style uses the official vinyl artwork `outline.png` and `disc.png` from Netease Cloud Music for macOS 3.1.11. See [Third-Party Notices](THIRD_PARTY_NOTICES.md) for source paths, version, and SHA-256 checksums.

## Build

Install JDK 21 and the Android SDK, then run:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

To verify the minified release build, run:

```bash
./gradlew assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/` as `SmartisanMusic-Revived-<versionName>.apk`. The version is defined in [app/build.gradle.kts](app/build.gradle.kts).

Release builds use the production signing configuration: signing settings are read from `keystore.properties` at the repository root (both `keystore/` and `keystore.properties` are git-ignored). When that file is absent, the release build falls back to unsigned so local and CI debugging still work.

Build Compose UI tests with `./gradlew assembleDebugAndroidTest`; this does not run them on a device. See the [development and validation record](docs/compose-migration.md) for the complete validation commands and device regression checklist.

## Acknowledgments

Thanks to [People-11](https://github.com/People-11/) for [SmartisanOS_APP_Port](https://github.com/People-11/SmartisanOS_APP_Port/). This project used its `Music_8.1.0.apk` as a reverse-engineering reference for original resources, page hierarchy, visual details, animation timing, and interaction behavior.

People-11's work allows the original app to continue running on non-Smartisan devices. This project instead rebuilds media scanning, playback services, the library, queues, and persistence, using modern public Android APIs for system integration while preserving the original design language.

## Disclaimer

This project is not affiliated with ByteDance, Smartisan Technology, realme, OPPO, or any rights holder associated with their products. It is an unofficial recreation driven by personal interest.

- Smartisan OS, related trademarks, visual designs, and original assets remain the intellectual property of their respective rights holders.
- The optional yellow vinyl icon is sourced from the default realme UI 7.0 UXIcon resources and is included solely for visual preservation and homage. Its artwork and related rights remain with their original rights holders and are not relicensed under this project's license. See `THIRD_PARTY_NOTICES.md` for provenance and file hashes.
- This project provides no music content. Users are responsible for ensuring that audio stored on their devices is obtained and used in accordance with applicable law and rights-holder requirements.
- The NetEase Cloud Music features are an independent third-party integration developed by this project and are not affiliated with NetEase or its affiliates. Online music content remains the property of NetEase Cloud Music and its rights holders and is for personal use only.
