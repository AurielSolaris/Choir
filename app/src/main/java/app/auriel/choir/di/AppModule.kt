// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.di

import app.auriel.choir.data.AlbumArtLoader
import app.auriel.choir.data.ChoirDatabase
import app.auriel.choir.data.MediaStoreRepository
import app.auriel.choir.data.MusicLibrary
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
    single { QueueRepository(get()) }

    // Library. MusicLibrary is a singleton so the browse screens, the search
    // screen and the drill-downs all read one loaded copy.
    single { MediaStoreRepository(androidContext()) }
    single { MusicLibrary(get()) }
    single { AlbumArtLoader(androidContext()) }

    // Playback. Single instance: the activity and the service must not each
    // hold their own controller.
    single { PlaybackConnection(androidContext()) }

    viewModel { LibraryViewModel(get(), get()) }
    viewModel { MusicPickerViewModel(get()) }
}
