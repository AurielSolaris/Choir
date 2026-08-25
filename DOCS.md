# Choir — developer documentation

Choir is a Kotlin port of the AOSP Music application, rebuilt on Compose, Media3
and Room. This document covers how it is put together and why, so that a change
can be made without reading the whole tree first.

- [Principles](#principles)
- [Architecture](#architecture)
- [Source layout](#source-layout)
- [How the library is loaded](#how-the-library-is-loaded)
- [How playback works](#how-playback-works)
- [Lyrics](#lyrics)
- [Widgets](#widgets)
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
│   │   └── Folders.kt           the folder tree, built from granted documents
│   ├── likes/                   liked songs: entity, DAO, repository, re-linking
│   ├── lyrics/                  LRC parsing, sidecar lookup
│   │   ├── tags/                ID3v2 and Vorbis comment readers
│   │   └── online/              opt-in providers, HTTP, on-disk cache
│   ├── folders/                 SAF trees, scanning, and the files in them
│   ├── playlist/                Choir's own playlists, and .m3u import/export
│   ├── settings/                preferences, and what online lyrics may do
│   ├── TrackIdentity.kt         re-linking stored references after a rescan
│   ├── TrackResolver.kt         a track by id, from MediaStore or a folder
│   └── queue/                   saved-queue entities, DAO, repository
│
├── playback/
│   ├── PlaybackService.kt       MediaSessionService + ExoPlayer
│   ├── PlaybackConnection.kt    MediaController, state mirror, position ticker
│   ├── MediaItems.kt            Track ↔ MediaItem
│   ├── AudioFormats.kt          which formats can be demuxed, decoded, played
│   ├── ChoirMimeTypes.kt        MIME types Media3 has no constant for
│   ├── ChoirCodecContext.kt     codec fields Media3's Format cannot carry
│   ├── ChoirExtractorsFactory.kt Media3's extractors, plus the four below
│   ├── AiffExtractor.kt         IFF chunks around big-endian PCM
│   ├── WavPackExtractor.kt      self-describing blocks, scanned for
│   ├── ApeExtractor.kt          a seek table at the front, exact seeking
│   ├── AsfExtractor.kt          fixed-size packets, blocks reassembled
│   ├── ChoirRenderersFactory.kt platform decoders first, FFmpeg for the rest
│   ├── WidgetPublisher.kt       writes what the player is doing, for the widgets
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
    ├── nowplaying/              full player, the lyric pane, the queue sheet
    ├── settings/                the one screen that changes what Choir may do
    ├── folders/                 browsing by directory, through SAF
    ├── widget/                  the four home screen widgets, in Glance
    ├── picker/                  GET_CONTENT audio picker
    └── permission/              permission gate

app/src/test/                   400 JVM tests, mirroring the tree above
app/src/androidTest/            26 that need a device: migrations, widget
                                declarations, the snapshot store, the player

app/src/main/java/androidx/media3/decoder/ffmpeg/
                                Media3's FFmpeg extension, vendored — two of
                                the five files carry Choir additions, marked
app/src/main/jniLibs/<abi>/     libffmpegJNI.so, when a build has one
tools/build-ffmpeg.sh           builds both of the above
tools/ffmpeg-jni-context.inc    a JNI entry point the build appends to Media3's
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

### The queue, and the order it is in

`PlaybackUiState.queue` is the queue **in play order** — not the order the items
were added in. With shuffle on those are different lists, and a queue view
showing the second one is describing bookkeeping rather than music.

The player will not hand that order over as a list, so it is walked: start at
`Timeline.getFirstWindowIndex(shuffled)` and follow `getNextWindowIndex` until
it runs out. Two details make that safe.

The walk asks for the next index with `REPEAT_MODE_OFF` regardless of what the
player is actually set to. Repeat is what happens when the queue runs out, not a
reason to list a track twice — and with `REPEAT_MODE_ALL` the timeline's "next"
wraps, so following it as given never terminates. `playOrder` also stops at any
index it has already seen and at the queue's length, which covers repeat-one
(where every window's successor is itself) and any timeline that disagrees with
itself. It takes the count and a step function rather than a `Timeline`, so all
of that is unit tested without Media3 loaded.

`QueueItem.mediaIndex` is the player's own index for an entry, which is not the
entry's position in the list. Jumping to a track names the index the player
knows it by; using the row's position would start a different song as soon as
shuffle was on.

Rebuilt only when it can have changed. `publish()` runs on every player event
and a queue can be the whole library, so the result is cached against the
`Timeline` instance and the shuffle flag — a timeline is immutable and replaced
wholesale when the queue changes, which makes identity a sound guard.

---

## Audio formats

Playing a file needs two separate things, and almost every claim about
"multi-format support" quietly conflates them:

1. a **demuxer**, to open the container and find the audio packets, and
2. a **decoder**, to turn those packets into samples.

Media3 ships demuxers only for the containers Android cares about. Media3's
FFmpeg extension ships decoders only. So adding FFmpeg adds codecs, and adds no
containers at all — which is why an APE file stayed unplayable on a build with
every decoder FFmpeg has compiled in. `playback/AudioFormats.kt` keeps the two
axes apart and reports which half is missing.

Measured on a Samsung SM‑M315F, Android 16, before v0.4.0 supplied the missing
demuxers:

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
`MediaStore.Audio.Media` entirely. Reaching one means browsing a folder the user
granted, which is the other half of what v0.4.0 added.

### The demuxers Choir writes

Four containers now have a hand-written Media3 `Extractor` in `playback/`, added
to Media3's own list by `ChoirExtractorsFactory` — appended, not prepended, so
Media3's extractors keep first refusal on the formats they own.

| Extractor | Container | How a frame is found | Seeking |
| --- | --- | --- | --- |
| `AiffExtractor` | AIFF, AIFC | IFF chunk walk | by byte offset, exact |
| `WavPackExtractor` | WavPack | 32-byte block header, scanned for | estimate, corrected on arrival |
| `ApeExtractor` | Monkey's Audio | seek table read up front | **exact** |
| `AsfExtractor` | ASF (`.wma`) | fixed-size packets | packet boundary, corrected |

They are as different as the formats are. WavPack blocks describe themselves and
can be picked up anywhere in the file, so a lost boundary is recoverable by
scanning; APE frames carry no sync word at all, so its header and seek table are
everything and a damaged one ends the file. ASF is neither — a streaming
container whose audio is cut across fixed-size packets, so a block larger than
the space left in a packet is split and has to be sewn back together before the
decoder sees it.

Only AIFF needs no decoder. The other three carry compressed audio that reaches
FFmpeg, which is where the second half of the problem is.

### What the decoder has to be told

Media3's FFmpeg extension opens a codec from a `Format`: MIME type, sample rate,
channel count, extradata. That is sufficient for every codec Media3 has a
demuxer for, because each of those reads its own parameters out of its
extradata. The ones reached here do not:

- `wmadec` refuses to open without `block_align`, and derives its coefficient
  tables from `bit_rate`. Given neither, a sound file fails to open — or opens
  and decodes to silence.
- `apedec` picks its output sample format from `bits_per_coded_sample`, and
  treats a zero as an unsupported depth rather than as "unstated".

Media3's `Format` has nowhere to put any of the three, and its JNI applies its
sample rate and channel count only to raw PCM. So:

- `ChoirCodecContext` encodes the three numbers into sixteen bytes that ride
  along as a second entry in `initializationData`, behind the codec extradata,
- `FfmpegAudioDecoder` (vendored, and marked "Choir's addition") reads them back
  and calls `ffmpegInitializeContext`,
- `tools/ffmpeg-jni-context.inc` is that function, appended to Media3's
  `ffmpeg_jni.cc` by the build script.

It is a *second* entry point rather than a change to `ffmpegInitialize`, and the
Java side catches `UnsatisfiedLinkError` and falls back. A `libffmpegJNI.so`
built before this existed keeps working for ALAC and Dolby instead of failing to
link; APE and WMA are the only things it cannot do, which is what it could not
do anyway.

**This means `.ape` and `.wma` need `tools/build-ffmpeg.sh` re-run.** The
demuxers work without it — the container opens, the format is published, the
packets are correct — and then the decoder declines to initialise. `.wv` is
unaffected: WavPack's decoder needs nothing beyond its packets.

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
`app/src/test/                   400 JVM tests, mirroring the tree above
app/src/androidTest/            26 that need a device: migrations, widget
                                declarations, the snapshot store, the player

app/src/main/java/androidx/media3/decoder/ffmpeg/`. About 2 MB per ABI.

It is vendored rather than depended on because Google does not publish
`media3-decoder-ffmpeg` to Maven. `ChoirRenderersFactory` never names those
classes: it finds a renderer through `FfmpegSupport`, which tries the official
class name and then the community port's, and asks the extension's own
`FfmpegLibrary.isAvailable()` whether the native library really loaded. A build
with no `.so` is a supported build.

The mode is `EXTENSION_RENDERER_MODE_ON`, not `PREFER`. `PREFER` would route
MP3 and AAC through software decoding for hours on a battery; `ON` leaves those
to the hardware and calls FFmpeg only where the device has nothing.

The script also appends `tools/ffmpeg-jni-context.inc` to Media3's
`ffmpeg_jni.cc` before building, and skips copying back the two Java files that
carry Choir's additions. Appended rather than patched: a unified diff would have
to match context lines in a file upstream is free to reformat between releases,
and would fail the whole build the day it did.

Moving to a newer Media3 tag means re-applying the additions in `FfmpegLibrary`
(MIME type to codec name) and `FfmpegAudioDecoder` (the codec context) by hand.
Both are marked `Choir's addition` in the source, and `tools/build-ffmpeg.sh`
names them.

---

## Lyrics

Three places are searched: a `.lrc` or `.txt` sidecar next to the file, the
file's own tags, and — only if the user switched it on — a service.

**A local source always wins.** If the file has words, in a sidecar or in its
own tags, the network is never asked, even when what is on the device is plain
text and a service might have offered a timed version of the same song. Someone
put those words next to that file, or in it; a lookup keyed on a title, an
artist and a duration is a guess about which recording this is, and a guess does
not get to overrule the thing itself. The practical half of the same argument is
that the common case then never leaves the device at all.

Before 0.5.0 that was not quite the rule: a synced result from a provider beat
an unsynced one on disk, on the reasoning that timing is more useful. It is, but
not at the price of showing someone a different take's words over their file.

Between the two *local* sources, timed beats untimed — an embedded `SYLT` frame
wins over a plain-text sidecar, a timed `.lrc` over an untimed tag. Both are the
file's own words, so there is nothing to defer to and the more useful one is
simply better. `betterOf` is that choice, and it is a pure function so it can be
tested without a `Context`. The sidecar keeps a tie: putting a `.lrc` beside a
file is a more deliberate act than a tagger writing a lyric frame.

Tags are read lazily. Parsing them means opening the audio file, so it only
happens when it could still change the answer — a synced sidecar has already won.

---

## Widgets

Four of them, in Jetpack Glance: *Now Playing* (2×2 and 4×2), *Controls* (4×1),
*Liked Songs* (2×2) and *Lyric Line* (4×1). They live in `ui/widget/`.

The constraint that shapes all of it is that **a widget is not the app**. It is
`RemoteViews` drawn by the launcher's process, frequently while Choir's own
process is dead. It cannot hold a `MediaController`, observe a `StateFlow`, or
ask the player anything at all.

So there is a `WidgetSnapshot` — a dozen short strings in preferences, written
by `playback/WidgetPublisher` on the player's own callbacks, and the only thing
a widget reads. A widget drawn after a reboot shows the track that was playing
before it, with a play button that resumes it.

### Nothing polls

`updatePeriodMillis` is `0` on all four. Every redraw is provoked by something
the player did, which leaves one hard case: the lyric line changes while
nothing else does.

That could have been a one-second tick. It is not, because it does not need to
be — an `.lrc` states the time of every line, so the next change is a fact to
read rather than a condition to poll for. `WidgetPublisher` sleeps exactly until
the next line and wakes once. One wake per line, only while playing, only when a
Lyric Line widget is actually on a home screen, and none at all past the last
line of the song. `lyricWaitFrom` is that arithmetic, and it is unit tested.

### The trap: a composition that never changes

`provideGlance` is called when a Glance *session* starts, not on every update.
`update()` recomposes the session that is already running. So this is wrong,
and wrong in the way that looks right:

```kotlin
// Renders correctly exactly once.
override suspend fun provideGlance(context: Context, id: GlanceId) {
    val snapshot = store.read()
    provideContent { Content(snapshot) }
}
```

`snapshot` is captured by value and frozen for the life of the session. The
publisher goes on writing updates that nothing reads, and the widget shows
whatever was true when it was first drawn — a pause button over a paused track,
for as long as the process lives. It survives casual testing because a session
that has to be recreated, after the process dies, does come back current: place
the widget, and it is right; place it and then press pause, and it is not.

So the snapshot is *observed* inside the composition, through
`WidgetSnapshotStore.snapshots()`, and the artwork is derived from it with
`produceState`. `LikedSongsWidget` observes the likes flow for the same reason.
This was found on a real home screen and could not have been found any other
way; nothing in a unit test composes a widget.

### The picker

`previewLayout` on each provider, from API 31 on: a static layout in
`res/layout/widget_preview_*.xml` that shows the widget's own shape in Choir's
paper and type. It is plain `RemoteViews` — no Glance, no snapshot, nothing
read — because the picker draws it without ever starting a session.

It replaces what was there before, which was the launcher icon four times with
four different labels under it. `previewImage` still points at that icon for
Android 10 and 11, which have no `previewLayout` to read.

The preview layouts are the one place the widget palette exists as resources
(`values/colors.xml` and its night variant) rather than in
`ui/widget/WidgetTheme.kt`. A picker inflating XML has no way to reach the
Kotlin, and `WidgetTheme` stays the place the values are decided.

### Taps

Glance callbacks run in Choir's process, so a button builds a short-lived
`MediaController`, says one thing, and releases it. Binding *starts* the service
if it is not running, which is what makes the resume button work after a reboot
— a media-button intent would need the service already alive and would say
nothing back about whether it arrived.

### What does not carry over

The design tokens do; the components do not. `ui/widget/WidgetTheme.kt` restates
the palette from `ui/theme`, but there is no `MaterialTheme`, no
`CompositionLocal` across the process boundary, and no EB Garamond or Inter —
the launcher cannot load a typeface out of another app's assets. Hierarchy is
carried by weight, size and the serif/sans split alone. The icons are duplicated
as vector drawables in `res/drawable/ic_widget_*` for the same reason: an
`ImageVector` built in Compose cannot cross into `RemoteViews`.

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

Every Material type slot Choir uses is declared in `Type.kt`. An undeclared one
does not fall back to Inter — it falls back to Roboto, and a single Roboto string
in a screen set in Inter and Garamond is visible. `bodyLarge` was undeclared
until 0.5.0, which is why a lyric pane's inactive lines were in a different face
from its active one.

Lyrics are set through `ChoirTypography.lyric`, and **every line gets it** —
the same face, size and weight whether or not it is the line being sung. A pane
that changed type for the active line reflowed the whole column each time the
song moved on, and read as two documents interleaved. What marks the current
line is ink against grey, and where the pane holds it on screen.

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

**400 unit tests** on JUnit 5 with MockK, plus **26 instrumented tests** on a
device. `./gradlew test`, or `make test`; `./gradlew connectedReleaseAndroidTest`
for the second set.

The tests are concentrated where the risk is, and the risk is not in the UI. It
is in reading files written by other programs, over thirty years, to
specifications that were widely ignored.

| Suite | Tests | What it pins down |
| --- | ---: | --- |
| `playback/AsfExtractorTest` | 24 | ASF objects and packet payload parsing — optional fields at four widths, blocks reassembled across packets, compressed payloads, error correction stepped over |
| `playback/ApeExtractorTest` | 22 | both header layouts, the seek table, frame alignment on the four-byte grid, and the prefix FFmpeg's decoder expects |
| `playback/WavPack*Test` | 23 | block headers, sub-blocks, multichannel frames, resynchronisation after a seek |
| `playback/Aiff*`, `SwapSampleBytes`, `ExtendedFloat` | 18 | IFF chunk padding, byte order, and the 80-bit float a sample rate is stored as |
| `playback/AudioFormatsTest` | 26 | format identification from extension and MIME, and which formats can actually play |
| `playback/ChoirCodecContextTest` | 5 | the sixteen bytes the extractors write and the vendored decoder reads |
| `ui/widget/WidgetSnapshotTest` + `playback/WidgetPublisherTest` | 16 | what survives between the player writing a snapshot and a launcher drawing it hours later, and the arithmetic that keeps the lyric widget from becoming a timer |
| `data/lyrics/tags/Id3v2ReaderTest` | 18 | USLT, SYLT and TXXX frames across ID3v2.2/2.3/2.4; synchsafe sizes; unsynchronisation; UTF‑16 BOMs; descriptors with no terminator |
| `data/lyrics/tags/VorbisCommentReaderTest` | 11 | FLAC metadata blocks, Ogg page and packet reassembly across 255-byte segment boundaries |
| `data/lyrics/tags/Mp4ReaderTest` + `IffReaderTest` | 24 | MP4 `©lyr` and `----` atoms, `moov` after `mdat`, streams whose `skip` does nothing, RIFF and AIFF chunk padding and byte order |
| `data/lyrics/Lrc*`, `WordTiming`, `SungUpTo` | 34 | simple, enhanced and A2 word-level timestamps, `[offset:]`, tenths/centis/millis, binary search for the active line |
| `data/lyrics/online/*ProviderTest` | 32 | request shape and response parsing for each provider, with an injected HTTP lambda — the suite never opens a socket |
| `data/playlist/*` | 36 | `.m3u` round trips, resolution by path then filename then metadata, ordering, renumbering, refusal of incomplete reorders |
| `data/folders/*` + `data/model/FolderTreeTest` | 30 | SAF document paths, tree building, `.nomedia`, and the files the media scanner never indexed |
| `data/RelinkTest` + `TrackResolverTest` | 16 | re-linking likes and playlist members after MediaStore renumbers the library; resolving a track from either source |
| `playback/PlayOrderTest` | 11 | the walk that turns a timeline into the order it will play: shuffled, wrapped by repeat, repeat-one, and the circular cases that would otherwise never terminate |
| `data/lyrics/LyricsPrecedenceTest` | 6 | which lyric wins between a sidecar and a tag, and the tie the sidecar keeps |
| `data/likes`, `data/queue`, `data/settings`, `data/model`, `core` | 48 | persistence, defaults, grouping, formatting |

Three choices are worth knowing about.

**Tag fixtures are built byte by byte in the tests**, in
`data/lyrics/tags/TagFixtures.kt`, rather than checked in as sample files. A
checked-in `.mp3` proves the parser handles that one file; a fixture that
assembles a synchsafe size field proves the parser handles the rule.

**Container fixtures are built the same way**, in each extractor's own test. An
APE seek table and an ASF packet are written there from the specification, so a
passing test says the reader agrees with the format rather than with whatever
produced a sample file — and a fixture generated by the same misunderstanding as
the parser would agree with it perfectly and prove nothing.
`playback/ExtractorFakes.kt` supplies the input and output an extractor is
given, in place of `media3-test-utils`, which would drag JUnit 4 in behind it.

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

### The 26 that need a device

Some things cannot be answered on the JVM, and until 0.5.0 they were not being
asked. `app/src/androidTest/` holds the ones that need real Android underneath
them.

| Suite | Tests | What it pins down |
| --- | ---: | --- |
| `data/ChoirDatabaseMigrationTest` | 6 | the three hand-written migrations, run against real SQLite: each step, the whole 1→4 chain a user who installed at 0.1.0 actually gets, the playlist cascade, and the unique index on `folder_files.trackId` |
| `ui/widget/WidgetProviderInfoTest` | 6 | what the launcher is told — four providers installed, every one with a description and a preview, no update period on any of them, and the cell sizes each was designed for |
| `ui/widget/WidgetSnapshotStoreTest` | 5 | the snapshot surviving a write and a read by a different reader, the clear that stops one track's lyric captioning the next, and the flow a Glance session observes actually firing |
| `ui/NowPlayingScreenTest` | 9 | the player and the queue popup: that it opens, says whether it is shuffled and repeating, marks what is playing, and reports the *player's* index for a tapped row rather than the row's position |

The migration tests are the ones that most needed writing. Room verifies a
migration's result against the exported schema only when the SQL executes, and
`MIGRATION_1_2` and its two successors are hand-written — so before this, a
misspelt column was a compile-time success and an upgrade-time crash, taking
somebody's likes and playlists with it.

They run against the **release** variant (`testBuildType = "release"`), so what
is tested is the build that ships: its resources, its manifest, its signing. R8
is the one exception — it is switched off for an instrumented-test invocation
and for nothing else. The test APK is minified separately and linked against the
app's mapping, so every class the test code touches inside the app APK has to
have survived the app's own shrink under the name the mapping gave it. It
routinely has not: R8 trims Kotlin's `Intrinsics` to the overloads the app
happens to use, drops `androidx.tracing.Trace` as unreachable, and each one is a
`NoSuchMethodError` in the runner's first instruction. `assembleRelease` still
minifies and shrinks exactly as before, so the R8 rules are verified every time
the shipping build is made.

Two smaller things had to be arranged before a Compose test would run at all,
both consequences of there being two APKs in one process:

- **The exception handler.** Compose's test rule runs every test inside
  coroutines' `runTest`, which will not start until `ServiceLoader` finds
  `ExceptionCollectorAsService`. That lookup goes through coroutines-core's own
  classloader — the app APK's — which cannot see an entry that exists only in
  the test APK. Both entries are merged into one services file in the app APK
  instead; see the `packaging` block in `app/build.gradle.kts`.
- **A host activity.** `createComposeRule` composes into a bare
  `ComponentActivity` that `ui-test-manifest` contributes. The usual
  `debugImplementation` is no help when the tests run against release, so it is
  added to the release variant too — for a test invocation only, never for a
  shipping APK.

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
| **0.4.0** ✅ | Folder browsing via SAF, reaching the files MediaStore never indexed. Demuxers for WavPack, APE and WMA, and the JNI entry point their decoders need — see [Audio formats](#audio-formats) for why a decoder alone is not enough. |
| **0.5.0** ✅ | Four home screen widgets in Jetpack Glance, driven by the media session — including a lyric line that waits for the next word rather than polling for it, and previews in the picker that look like the widgets they are. A queue popup that lists what is actually coming, shuffle and repeat included. Lyrics set in one face throughout, and never overruled by the network. The first instrumented tests: the three hand-written migrations, run. |
| 0.5.3 | The real UI: iPod-style hierarchy, paper-grain texture overlay, hand-sketched icon set. |
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
