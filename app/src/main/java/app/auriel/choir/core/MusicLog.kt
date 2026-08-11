// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.core

import android.util.Log
import app.auriel.choir.BuildConfig

/**
 * Logging front-end for the whole app, in the spirit of AOSP's `MusicLog`.
 *
 * Everything below [Log.WARN] is compiled against [BuildConfig.DEBUG] so release
 * builds stay quiet — Choir ships no analytics, and the log is the only place
 * user data could plausibly leak out of the process.
 */
object MusicLog {

    private const val TAG = "Choir"

    fun v(scope: String, message: String) {
        if (BuildConfig.DEBUG) Log.v(TAG, "$scope: $message")
    }

    fun d(scope: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$scope: $message")
    }

    fun i(scope: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, "$scope: $message")
    }

    fun w(scope: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "$scope: $message", error)
    }

    fun e(scope: String, message: String, error: Throwable? = null) {
        Log.e(TAG, "$scope: $message", error)
    }
}
