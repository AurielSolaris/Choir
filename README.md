<p align="center">
  <img src="docs/icon.png" width="128" alt="Choir">
</p>

<h1 align="center">Choir</h1>

<p align="center">
  A local-first music player for Android, ported from the AOSP Music app to
  Kotlin, Compose and Media3.
</p>

Choir plays the music on your device. It has no accounts, no streaming, no
recommendations, no telemetry and no network permission at all. What you own is
what you hear.

The icon lives at [`docs/icon.svg`](docs/icon.svg) (source) and
[`docs/icon.png`](docs/icon.png) (512px, for embedding).

**Status: v0.2.0** — the library browses four ways and plays; see
[the roadmap](DOCS.md#roadmap) for what is coming.

<p align="center">
  <img src="docs/screenshots/library.png" width="30%" alt="Track list">
  <img src="docs/screenshots/album.png" width="30%" alt="Album detail">
  <img src="docs/screenshots/now-playing.png" width="30%" alt="Now playing">
</p>

## What works today

- **Library** — tracks, albums, artists and playlists, read from MediaStore
- **Playback** — gapless-capable ExoPlayer backend, audio focus, becoming-noisy
  handling, media notification, lock-screen and Bluetooth controls
- **Queue** — playing from an album, artist or search result queues *that* list;
  the queue survives a restart
- **Search** — instant filtering across titles, albums and artists
- **Picker** — Choir answers other apps' "choose a track" requests
- **Design** — monochrome throughout, with EB Garamond for content and Inter for
  chrome; hierarchy comes from type, never colour

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35).

```sh
./gradlew assembleDebug      # or: make debug / ./build.sh debug
./gradlew test               # unit tests
./gradlew installRelease     # build, sign and push to a connected device
```

Release builds are signed from a git-ignored `keystore.properties` at the repo
root:

```properties
storeFile=your-key.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without that file the release build still completes — it simply goes unsigned.

## Licence

Choir is free software under the **GNU General Public License v3.0 or later**.
You may use, study, share and modify it; if you distribute a modified version,
it must stay free software under the same licence, source included.

Choir derives from the Android Open Source Project's Music app, which is Apache
2.0. See [NOTICE](NOTICE) for the full attribution, and [LICENSE](LICENSE) for
the GPL text.

## Contact

debadityamalakar@gmail.com
