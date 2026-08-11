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

1. **Local-first.** Everything Choir does works with the network off. The one
   feature that needs it — fetching lyrics — is off by default, asks only about
   the track in front of it, and is confined to `data/lyrics/online/`. Any
   future feature that wants the network faces the same bar: optional, explicit,
   and honest about what it sends.
2. **No algorithm.** No recommendations, no listening history, no telemetry.
   Curation is manual, always.
3. **MediaStore is the truth.** The database never mirrors the library. It
   stores only what MediaStore cannot: the queue and the likes now, playlists
   later. A liked row is an id and enough tags to find that track again if
   MediaStore renumbers it — never a copy of the track itself.
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
│   ├── ChoirDatabase.kt         Room, with its migrations
│   ├── model/
│   │   ├── Track.kt             one audio file
│   │   └── Collections.kt       Album/Artist/Playlist + grouping rules
│   ├── likes/                   liked songs: entity, DAO, repository, re-linking
│   ├── lyrics/                  LRC parsing, sidecar lookup
│   │   ├── tags/                ID3v2 and Vorbis comment readers
│   │   └── online/              opt-in providers, HTTP, on-disk cache
│   ├── playlist/                Choir's own playlists, and .m3u import/export
│   ├── settings/                preferences, and what online lyrics may do
│   ├── TrackIdentity.kt         re-linking stored references after a rescan
│   └── queue/                   saved-queue entities, DAO, repository
│
├── playback/
│   ├── PlaybackService.kt       MediaSessionService + ExoPlayer
│   ├── PlaybackConnection.kt    MediaController, state mirror, position ticker
│   ├── MediaItems.kt            Track ↔ MediaItem
│   ├── AudioFormats.kt          which formats can be demuxed, decoded, played
│   ├── ChoirRenderersFactory.kt platform decoders first, FFmpeg for the rest
│   └── FfmpegSupport.kt         finds an FFmpeg renderer, if this build has one
│
├── di/AppModule.kt              Koin graph
│
└── ui/
    ├── ChoirApp.kt              navigation graph
    ├── ChoirIcons.kt            icons, defined from path data
    ├── theme/                   colours and typography
    ├── components/              rows, header, tabs, search field, mini player
    ├── library/                 tabbed browser + the shared ViewModel
    ├── detail/                  album and artist drill-downs, named track lists
    ├── search/                  instant search
    ├── nowplaying/              full player and the synced lyric pane
    ├── settings/                the one screen that changes what Choir may do
    ├── picker/                  GET_CONTENT audio picker
    └── permission/              permission gate

app/src/main/java/androidx/media3/decoder/ffmpeg/
                                Media3's FFmpeg extension, vendored verbatim
app/src/main/jniLibs/<abi>/     libffmpegJNI.so, when a build has one
tools/build-ffmpeg.sh           builds both of the above
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
| `PlaylistBrowserActivity` | playlists tab + `ui/detail/PlaylistScreen.kt` |
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
`Player.Events` callback, and ticks the position every 250 ms while playing —
500 ms was fine for the seek bar alone but read as lag against a synced lyric.
It also exposes a `problems` flow, so a file that will not decode produces a
sentence naming the format rather than a tap that does nothing.

That ticker is why `ChoirApp` collects `playback.state` only inside the
Now Playing route and beside the mini player, exposing just a derived
`isPlayingSomething` boolean at the top. Collecting it at the top level
recomposed the whole navigation tree four times a second, which showed up as
stutter while dragging a playlist row.

The same problem exists one level down and is **not** fixed: the ticker rebuilds
the whole `PlaybackUiState`, and a copy carrying a new position is never equal
to the last one, so `StateFlow` conflation cannot suppress it. Now Playing
collects that whole state, so its artwork, title and transport buttons all
recompose on every tick although none of them changed. The fix is to give the
position its own `StateFlow` and let only the seek bar and lyric pane follow the
clock — which is what `LyricsPane` already does internally, holding the position
in a `rememberUpdatedState` and reading it inside the one row whose highlight
moved.

Playing from any list queues *that* list. Tapping a track on an album queues the
album; tapping a search result queues the results. This is what made the AOSP
browsers feel coherent, and it is verified by checking the session queue length.

---

## Audio formats

Playing a file needs two separate things, and almost every claim about
"multi-format support" quietly conflates them:

1. a **demuxer**, to open the container and find the audio packets, and
2. a **decoder**, to turn those packets into samples.

Media3 ships demuxers only for the containers Android cares about. Media3's
FFmpeg extension ships decoders only. So adding FFmpeg adds codecs, and adds no
containers at all — which is why an APE file stays unplayable on a build with
every decoder FFmpeg has compiled in. `playback/AudioFormats.kt` keeps the two
axes apart and reports which half is missing.

Measured on a Samsung SM‑M315F, Android 16:

| File | Demuxer | Decoder used | Result |
| --- | --- | --- | --- |
| `.flac` | `FlacExtractor` | `c2.android.flac.decoder` | plays, platform |
| `.opus` | `OggExtractor` | `c2.android.opus.decoder` | plays, platform |
| `.m4a` (ALAC) | `Mp4Extractor` | `ffmpegLavc60-alac` | plays, **needs FFmpeg** |
| `.ac3` | `Ac3Extractor` | `ffmpegLavc60-ac3` | plays, **needs FFmpeg** |
| `.aiff` | — | — | `UnrecognizedInputFormatException` |
| `.wma` | — | — | `UnrecognizedInputFormatException` |
| `.mka` (WavPack) | `MatroskaExtractor` | — | `No valid tracks were found` |
| `.wv`, `.tta` | — | — | not even indexed as audio |

The last two rows are the interesting failures. Matroska *is* demuxable, and the
file still fails: Media3's Matroska extractor matches a fixed list of codec ids
and exposes no track at all for one outside it, so the decoder is never offered
the stream. And `.wv` and `.tta` never reach playback because the media scanner
types them `application/octet-stream` with `media_type=0` — they are not audio
as far as the platform is concerned, and are invisible to
`MediaStore.Audio.Media` entirely.

### What the library shows

`MediaStoreRepository.SELECTION` used to require `DURATION > 0`. That silently
deleted every AIFF, WMA and AC‑3 from the library, because the scanner writes a
null duration for exactly the files it cannot parse. The filter is now on file
size, which drops stub rows without dropping real songs, and:

- unknown durations render as `—` rather than `0:00`,
- a row whose "title" is only the filename stem keeps its extension, so three
  unparsable files in one folder are told apart,
- a track that fails to play raises a `PlaybackProblem` naming the format,
  rather than skipping in silence.

### Building the decoder

`tools/build-ffmpeg.sh` downloads its own NDK, the pinned Media3 tag and the
pinned FFmpeg tag, configures FFmpeg with `--disable-everything` plus an
explicit decoder list, builds `libffmpegJNI.so` for the requested ABIs, strips
it, and vendors the extension's five Java files into
`app/src/main/java/androidx/media3/decoder/ffmpeg/`. About 2 MB per ABI.

It is vendored rather than depended on because Google does not publish
`media3-decoder-ffmpeg` to Maven. `ChoirRenderersFactory` never names those
classes: it finds a renderer through `FfmpegSupport`, which tries the official
class name and then the community port's, and asks the extension's own
`FfmpegLibrary.isAvailable()` whether the native library really loaded. A build
with no `.so` is a supported build.

The mode is `EXTENSION_RENDERER_MODE_ON`, not `PREFER`. `PREFER` would route
MP3 and AAC through software decoding for hours on a battery; `ON` leaves those
to the hardware and calls FFmpeg only where the device has nothing.

Reaching APE, WavPack and WMA needs demuxers, which is a separate piece of work:
either hand-written Media3 `Extractor`s (AIFF is nearly trivial — IFF chunks
around big-endian PCM) or a JNI bridge to `libavformat`.

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

**201 unit tests**, plain JVM tests on JUnit 5 with MockK and Robolectric.
`./gradlew test`, or `make test`.

The tests are concentrated where the risk is, and the risk is not in the UI. It
is in reading files written by other programs, over thirty years, to
specifications that were widely ignored.

| Suite | Tests | What it pins down |
| --- | ---: | --- |
| `data/lyrics/tags/Id3v2ReaderTest` | 18 | USLT, SYLT and TXXX frames across ID3v2.2/2.3/2.4; synchsafe sizes; unsynchronisation; UTF‑16 BOMs; descriptors with no terminator |
| `data/lyrics/tags/VorbisCommentReaderTest` | 11 | FLAC metadata blocks, Ogg page and packet reassembly across 255-byte segment boundaries |
| `data/lyrics/tags/ContainerReaderTest` | 24 | MP4 `©lyr` and `----` atoms, `moov` after `mdat`, streams whose `skip` does nothing, RIFF and AIFF chunk padding and byte order |
| `data/lyrics/LrcParserTest` + `LyricsIndexTest` | 20 | simple, enhanced and A2 word-level timestamps, `[offset:]`, tenths/centis/millis, binary search for the active line |
| `data/playlist/M3uParseTest` + `M3uResolveTest` | 17 | `.m3u` round trips, resolution by path then filename then metadata |
| `data/playlist/PlaylistRepositoryTest` + `PlaylistTracksInTest` | 19 | ordering, renumbering after removal, refusal of incomplete reorders |
| `data/RelinkTest` | 10 | re-linking likes and playlist members after MediaStore renumbers the library |
| `data/lyrics/online/*ProviderTest` | 19 | request shape and response parsing for each provider, with an injected HTTP lambda — the suite never opens a socket |
| `playback/AudioFormatsTest` | 22 | format identification from extension and MIME, and which formats can actually play |
| `data/likes`, `data/queue`, `data/settings`, `core` | 41 | persistence, defaults, formatting |

Two choices are worth knowing about.

**Tag fixtures are built byte by byte in the tests**, in
`data/lyrics/tags/TagFixtures.kt`, rather than checked in as sample files. A
checked-in `.mp3` proves the parser handles that one file; a fixture that
assembles a synchsafe size field proves the parser handles the rule.

**The format table was written from a device, not from documentation.** Each
entry in `AudioFormats` reflects what a Samsung SM‑M315F on Android 16 actually
recorded after the file was pushed and rescanned, and the list of demuxers it
claims Media3 has is the list Media3 names in its own
`UnrecognizedInputFormatException`. `tools/` has no fixture for this because the
check is a device check; the unit tests assert the conclusions it produced.

Framework stubs return defaults (`testOptions.unitTests.isReturnDefaultValues`),
so model classes must stay constructible without the framework loaded — this is
why `Track` resolves its artwork URI lazily rather than at class-init. It is
also why `org.json` is a real test dependency: `android.jar`'s copy is a stub
that returns nulls, and the lyric providers parse JSON.

Instrumentation and Compose UI dependencies are declared but the suite is thin;
UI behaviour is currently checked on a real device.

---

## Platform notes

**Permissions.** `READ_MEDIA_AUDIO` on API 33+, `READ_EXTERNAL_STORAGE` below
it, plus `POST_NOTIFICATIONS` for the media notification. The gate re-checks on
every `ON_START`, so a grant made in Settings takes effect without a restart.

**Playlists are mostly gone.** `MediaStore.Audio.Playlists` was deprecated and
then closed to other apps' rows. On Android 11 and newer the query returns
nothing. Choir keeps its own playlists in Room; the MediaStore query survives
only as a one-time import path for playlists made before the platform closed it
off. The deprecation warnings in `MediaStoreRepository` are suppressed
deliberately and locally.

**Edge to edge.** The window is edge-to-edge, so lists reserve the gesture-bar
inset and the mini player's surface runs to the bottom of the window. The search
screen needs `android:windowSoftInputMode="adjustResize"` for `imePadding()` to
receive an inset.

---

## Roadmap

| Version | Scope |
| --- | --- |
| **0.1.0** ✅ | Kotlin/Compose/Media3 port. Track list, now playing, play/pause/skip/seek, media notification, saved queue. |
| **0.2.0** ✅ | Full browse port: albums, artists, search, drill-downs, audio picker. |
| **0.3.0** ✅ | Lyrics from tags, sidecars and — opt in — the network. Liked songs and editable playlists in Room. FFmpeg decoding, an AIFF reader, and a library that stops hiding what the media scanner could not parse. |
| 0.4.0 | Folder browsing via SAF. Demuxers for APE, WavPack and WMA — see [Audio formats](#audio-formats) for why a decoder alone is not enough. |
| 0.5.0 | The real UI: iPod-style hierarchy, paper-grain texture overlay, hand-sketched icon set. |
| 0.5.5 | Tree-shaken FFmpeg build, targeting a 60–80% smaller binary. |
| 0.6.0 | Stabilisation, accessibility, RTL. Peer-to-peer sharing over Bluetooth/Wi-Fi Direct using a `.chmf` bundle. |
| 1.0.0 | F-Droid and direct APK distribution. |

Explicit non-goals, at every version: recommendation algorithms, ads, analytics,
tracking, and streaming integration.

---

## Licensing

Choir is **GPL-3.0-or-later**. Contributions are accepted under the same terms.

GPL **v3** specifically, not v2: Choir derives from Apache-2.0 AOSP code, and
Apache 2.0 is one-way compatible with GPLv3 while being incompatible with GPLv2.

AGPL was considered and rejected. Its additional clause covers software users
interact with *over* a network — a hosted service. Choir is a client that
occasionally makes a request; nobody ever interacts with a copy of Choir running
on someone else's machine, so the clause would never fire. It would add friction
for contributors without adding protection. GPLv3 already prevents the thing
worth preventing — a closed-source, ad-supported fork.

New source files carry SPDX headers:

```kotlin
// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later
```

Bundled fonts stay under the SIL Open Font License and are not relicensed; their
texts ship inside the app at `assets/licenses/`. See [NOTICE](NOTICE) for the
full attribution.
