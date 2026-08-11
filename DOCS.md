# Choir — developer documentation

Choir is a Kotlin port of the AOSP Music application, rebuilt on Compose, Media3
and Room. This document covers how it is put together and why, so that a change
can be made without reading the whole tree first.

- [Principles](#principles)
- [Architecture](#architecture)
- [Source layout](#source-layout)
- [How the library is loaded](#how-the-library-is-loaded)
- [How playback works](#how-playback-works)
- [Design language](#design-language)
- [Building and running](#building-and-running)
- [Testing](#testing)
- [Platform notes](#platform-notes)
- [Roadmap](#roadmap)
- [Licensing](#licensing)

---

## Principles

1. **Local-first.** Choir holds no network permission. Anything that would need
   one is either out of scope or must be an explicit, user-configured opt-in.
2. **No algorithm.** No recommendations, no listening history, no telemetry.
   Curation is manual, always.
3. **MediaStore is the truth.** The database never mirrors the library. It
   stores only what MediaStore cannot: the queue now, liked songs and playlists
   later.
4. **Typography over ornament.** The palette is ink and paper. Hierarchy comes
   from type and space, never from colour.

---

## Architecture

A single activity hosting Compose, a foreground service owning the player, and
one shared in-memory library between them.

```
┌─────────────────────────────────────────────────────────────┐
│ MainActivity — Compose, one NavHost                         │
│                                                             │
│   PermissionGate                                            │
│     └── ChoirNavigation                                     │
│           library → album / artist / playlist → now playing │
│           library → search                                  │
└───────┬──────────────────────────────────┬──────────────────┘
        │ observes                         │ commands
        ▼                                  ▼
┌──────────────────────┐        ┌──────────────────────────┐
│ LibraryViewModel     │        │ PlaybackConnection       │
│  tabs, search,       │        │  holds a MediaController │
│  playlist contents   │        │  mirrors player → State  │
└───────┬──────────────┘        └───────────┬──────────────┘
        │                                   │ Media3 session
        ▼                                   ▼
┌──────────────────────┐        ┌──────────────────────────┐
│ MusicLibrary         │        │ PlaybackService          │
│  StateFlow<Snapshot> │        │  ExoPlayer + MediaSession│
│  tracks/albums/      │        │  notification, focus,    │
│  artists/playlists   │        │  saved queue             │
└───────┬──────────────┘        └───────────┬──────────────┘
        │                                   │
        ▼                                   ▼
┌──────────────────────┐        ┌──────────────────────────┐
│ MediaStoreRepository │        │ QueueRepository → Room   │
└──────────────────────┘        └──────────────────────────┘
```

Two deliberate choices worth knowing:

**One ViewModel for the whole graph.** Every browse screen is a projection of
the same track list. A ViewModel per destination would re-derive albums and
artists on each drill-down, so `LibraryViewModel` is obtained once in
`ChoirNavigation` and its state passed down. Detail screens are stateless
composables that filter a snapshot.

**The UI never touches the player directly.** `PlaybackConnection` owns the
`MediaController`, connects on `onStart` and releases on `onStop`. Playback
lives in the service and is unaffected by the UI coming and going.

---

## Source layout

```
app/src/main/java/app/auriel/choir/
├── ChoirApplication.kt          Koin startup
├── MainActivity.kt              the only launcher activity
│
├── core/
│   ├── MusicLog.kt              logging, silenced in release
│   ├── MusicUtils.kt            duration and metadata formatting
│   └── Permissions.kt           per-API-level permission sets
│
├── data/
│   ├── MediaStoreRepository.kt  every MediaStore query
│   ├── MusicLibrary.kt          the loaded library, as a StateFlow
│   ├── AlbumArtLoader.kt        bounded bitmap cache, no image library
│   ├── ChoirDatabase.kt         Room
│   ├── model/
│   │   ├── Track.kt             one audio file
│   │   └── Collections.kt       Album/Artist/Playlist + grouping rules
│   └── queue/                   saved-queue entities, DAO, repository
│
├── playback/
│   ├── PlaybackService.kt       MediaSessionService + ExoPlayer
│   ├── PlaybackConnection.kt    MediaController, state mirror, position ticker
│   └── MediaItems.kt            Track ↔ MediaItem
│
├── di/AppModule.kt              Koin graph
│
└── ui/
    ├── ChoirApp.kt              navigation graph
    ├── ChoirIcons.kt            icons, defined from path data
    ├── theme/                   colours and typography
    ├── components/              rows, header, tabs, search field, mini player
    ├── library/                 tabbed browser + the shared ViewModel
    ├── detail/                  album, artist, playlist drill-downs
    ├── search/                  instant search
    ├── nowplaying/              full player
    ├── picker/                  GET_CONTENT audio picker
    └── permission/              permission gate
```

### Where the AOSP code went

| AOSP | Choir |
| --- | --- |
| `MediaPlaybackService` | `playback/PlaybackService.kt` |
| `MediaPlaybackActivity` | `ui/nowplaying/NowPlayingScreen.kt` |
| `MusicBrowserActivity` | `ui/library/LibraryScreen.kt` (tab host) |
| `TrackBrowserActivity` | `ui/library/` tracks tab |
| `AlbumBrowserActivity` | albums tab + `ui/detail/AlbumDetailScreen.kt` |
| `ArtistAlbumBrowserActivity` | artists tab + `ui/detail/ArtistDetailScreen.kt` |
| `PlaylistBrowserActivity` | playlists tab + `ui/detail/PlaylistDetailScreen.kt` |
| `QueryBrowserActivity` | `ui/search/SearchScreen.kt` |
| `MusicPicker` | `ui/picker/` |
| `MusicUtils`, `MusicLog` | `core/` |
| `CursorLoader`s | `data/MediaStoreRepository.kt` + `MusicLibrary.kt` |

---

## How the library is loaded

`MediaStoreRepository.observeTracks()` returns a flow that emits the full track
list once, then again whenever a `ContentObserver` reports the audio collection
changed. The observer only pokes a conflated channel — it fires on MediaStore's
thread and must not block.

`MusicLibrary` collects that flow and publishes a `LibrarySnapshot` holding
tracks, albums, artists and playlists together, so no screen can ever show two
inconsistent halves of the library.

**Albums and artists are derived, not queried.** `Collections.kt` groups the
track list by `albumId` and `artistId`. The dedicated MediaStore tables carry
columns that scoped storage deprecated or emptied, and a derived view is
guaranteed to agree with the tracks the player can actually open. A few hundred
rows cost nothing to group. The rules that matter:

- an album whose tracks name more than one artist is credited to *Various
  artists*, not to whichever track sorted first
- album running order is by track number, with untagged tracks last, by title
- `TRACK` is decoded as `disc * 1000 + track`, as MediaStore encodes it

---

## How playback works

`PlaybackService` is a `MediaSessionService`. Media3 handles audio focus, the
becoming-noisy receiver, media buttons, the notification and the lock screen;
the service adds only what Media3 does not have opinions about:

- **Saved queue.** A `Player.Listener` snapshots the queue on transitions and
  pauses, debounced, into Room. On start-up the service restores it and prepares
  without playing. Track ids are the media ids, which is what makes a saved
  queue resolvable after a restart.
- **Error recovery.** A single unreadable file skips to the next item rather
  than stranding the queue.
- **Task removal.** Swiping the app away stops the service only if nothing is
  playing.

`PlaybackConnection` mirrors the player into a `PlaybackUiState` on every
`Player.Events` callback, and ticks the position every 500 ms while playing —
often enough for a live seek bar, rarely enough to be free.

Playing from any list queues *that* list. Tapping a track on an album queues the
album; tapping a search result queues the results. This is what made the AOSP
browsers feel coherent, and it is verified by checking the session queue length.

---

## Design language

Monochrome, with type doing the work colour normally would.

- **EB Garamond** — track, album and artist names. Old-style, bookish; as close
  to ink on paper as a screen manages.
- **Inter** — labels, counts, timestamps. Light and tracked out so it reads as
  annotation and recedes behind the content. Timestamps use tabular figures so a
  ticking clock does not jitter.

Both ship as single variable fonts in `res/font/`, with every weight taken from
one file per family via `FontVariation`. Swapping either family is two lines in
`ui/theme/Type.kt`.

Colours live in `ui/theme/Theme.kt` as `ChoirColors` — background, surface, two
ink levels, muted and divider — and are projected onto a Material 3 scheme so no
stray platform colour can leak through a component.

Icons are built from path data in `ui/ChoirIcons.kt` rather than pulled from a
Material icons dependency, which is large and will be replaced by a hand-drawn
set anyway.

The launcher mark is `docs/icon.svg`, hand-maintained in step with
`res/drawable/ic_launcher_foreground.xml`. All artwork sits inside the 72dp
keyline circle so no mask clips it.

---

## Building and running

Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 29).

| Task | Gradle | Make | Shell |
| --- | --- | --- | --- |
| Debug APK | `assembleDebug` | `make debug` | `./build.sh debug` |
| Release APK | `assembleRelease` | `make release` | `./build.sh release` |
| Unit tests | `test` | `make test` | `./build.sh test` |
| Install debug | `installDebug` | `make install` | `./build.sh install` |
| Install release | `installRelease` | `make install-release` | `./build.sh install-release` |

`build.ps1` and `build.bat` take the same targets on Windows.

### Signing

Release signing reads a git-ignored `keystore.properties` at the repo root:

```properties
storeFile=your-key.jks
storePassword=…
keyAlias=…
keyPassword=…
```

If the file is missing the release build completes unsigned rather than failing,
so CI can still verify that R8 is happy. An app signed with one key cannot
upgrade an install signed with another — generate the real key once and keep it.

---

## Testing

Unit tests are plain JVM tests on JUnit 5, covering the logic that has rules
rather than plumbing:

- `MusicUtilsTest` — duration formatting, unknown-tag fallbacks
- `CollectionsTest` — album/artist grouping, compilation detection, album order
- `QueueRepositoryTest` — saved-queue round trip and index clamping

Framework stubs return defaults (`testOptions.unitTests.isReturnDefaultValues`),
so model classes must stay constructible without the framework loaded — this is
why `Track` resolves its artwork URI lazily rather than at class-init.

There are no instrumentation or Compose UI tests yet. The dependencies are
declared; the suite belongs with the file-format work in phase 3 and later.

---

## Platform notes

**Permissions.** `READ_MEDIA_AUDIO` on API 33+, `READ_EXTERNAL_STORAGE` below
it, plus `POST_NOTIFICATIONS` for the media notification. The gate re-checks on
every `ON_START`, so a grant made in Settings takes effect without a restart.

**Playlists are mostly gone.** `MediaStore.Audio.Playlists` was deprecated and
then closed to other apps' rows. On Android 11 and newer the query returns
nothing, and the playlists tab says so plainly. Choir's own playlists arrive in
phase 4. The deprecation warnings in `MediaStoreRepository` are suppressed
deliberately and locally.

**Edge to edge.** The window is edge-to-edge, so lists reserve the gesture-bar
inset and the mini player's surface runs to the bottom of the window. The search
screen needs `android:windowSoftInputMode="adjustResize"` for `imePadding()` to
receive an inset.

---

## Roadmap

Choir ships in phases. Comments in the source refer to these numbers.

| Version | Scope |
| --- | --- |
| **0.1.0** ✅ | Kotlin/Compose/Media3 port. Track list, now playing, play/pause/skip/seek, media notification, saved queue. |
| **0.2.0** ✅ | Full browse port: albums, artists, playlists, search, drill-downs, audio picker. |
| 0.3.0 | Lyrics — embedded (USLT/SYLT/Vorbis), external `.lrc` including word-level, pluggable online providers. Liked songs in Room. |
| 0.4.0 | FFmpeg for FLAC, Opus, WMA, APE, WavPack and friends. Folder browsing via SAF, editable playlists, `.m3u` import/export. |
| 0.5.0 | The real UI: iPod-style hierarchy, paper-grain texture overlay, hand-sketched icon set. |
| 0.5.5 | Tree-shaken FFmpeg build, targeting a 60–80% smaller binary. |
| 0.6.0 | Stabilisation, accessibility, RTL. Peer-to-peer sharing over Bluetooth/Wi-Fi Direct using a `.chmf` bundle. |
| 1.0.0 | Production signing, F-Droid and direct APK distribution. |

Explicit non-goals, at every phase: recommendation algorithms, ads, analytics,
tracking, and streaming integration.

---

## Licensing

Choir is **GPL-3.0-or-later**. Contributions are accepted under the same terms.

GPL **v3** specifically, not v2: Choir derives from Apache-2.0 AOSP code, and
Apache 2.0 is one-way compatible with GPLv3 while being incompatible with GPLv2.

AGPL was considered and rejected. Its additional clause covers software users
interact with over a network, which an offline player never is; it would add
friction for contributors without adding protection. GPLv3 already prevents the
thing worth preventing — a closed-source, ad-supported fork.

New source files carry SPDX headers:

```kotlin
// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later
```

Bundled fonts stay under the SIL Open Font License and are not relicensed; their
texts ship inside the app at `assets/licenses/`. See [NOTICE](NOTICE) for the
full attribution.
