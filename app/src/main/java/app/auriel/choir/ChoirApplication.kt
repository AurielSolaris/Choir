// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir

import android.app.Application
import app.auriel.choir.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ChoirApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ChoirApplication)
            modules(appModule)
        }
    }
}
