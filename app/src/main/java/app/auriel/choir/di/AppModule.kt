// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.di

import app.auriel.choir.data.AlbumArtLoader
import app.auriel.choir.data.ChoirDatabase
import app.auriel.choir.data.MediaStoreRepository
import app.auriel.choir.data.MusicLibrary
import app.auriel.choir.data.TrackResolver
import app.auriel.choir.data.folders.FolderRepository
import app.auriel.choir.data.folders.FolderScanner
import app.auriel.choir.data.likes.LikesRepository
import app.auriel.choir.data.lyrics.LyricsRepository
import app.auriel.choir.data.lyrics.online.LyricsCache
import app.auriel.choir.data.lyrics.online.OnlineLyricsSource
import app.auriel.choir.data.playlist.PlaylistFiles
import app.auriel.choir.data.playlist.PlaylistRepository
import app.auriel.choir.data.settings.SettingsStore
import app.auriel.choir.ui.settings.SettingsViewModel
import app.auriel.choir.data.queue.QueueRepository
import app.auriel.choir.playback.PlaybackConnection
import app.auriel.choir.ui.library.LibraryViewModel
import app.auriel.choir.ui.picker.MusicPickerViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    // Storage
    single { ChoirDatabase.build(androidContext()) }
    single { get<ChoirDatabase>().queueDao() }
    single { get<ChoirDatabase>().likesDao() }
    single { get<ChoirDatabase>().playlistDao() }
    single { get<ChoirDatabase>().foldersDao() }
    single { QueueRepository(get()) }
    single { LikesRepository(get()) }
    single { PlaylistRepository(get()) }
    single { FolderScanner(androidContext()) }
    single { FolderRepository(androidContext(), get(), get()) }

    // Library. MusicLibrary is a singleton so the browse screens, the search
    // screen and the drill-downs all read one loaded copy.
    single { MediaStoreRepository(androidContext()) }
    single { TrackResolver(get<MediaStoreRepository>()::tracksByIds, get()) }
    single { MusicLibrary(get(), get()) }
    single { AlbumArtLoader(androidContext()) }
    single { PlaylistFiles(androidContext(), get(), get()) }

    // Lyrics. The online source is constructed unconditionally but does nothing
    // until the settings switch is on — see OnlineLyricsSource.
    single { SettingsStore(androidContext()) }
    single { LyricsCache(androidContext()) }
    single { OnlineLyricsSource(androidContext(), get(), get()) }
    single { LyricsRepository(androidContext(), get()) }

    // Playback. Single instance: the activity and the service must not each
    // hold their own controller.
    single { PlaybackConnection(androidContext()) }

    viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { MusicPickerViewModel(get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
