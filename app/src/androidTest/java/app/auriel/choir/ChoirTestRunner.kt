// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * The instrumentation runner, with one thing set before anything else loads.
 *
 * Compose's test rule runs every test inside coroutines' `runTest`, which will
 * not start until it can find Android's `AndroidExceptionPreHandler` through a
 * `ServiceLoader`. On a device that lookup fails, and the reason is that there
 * are two APKs in the process:
 *
 *  - the app APK declares `AndroidExceptionPreHandler` in
 *    `META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`, from
 *    kotlinx-coroutines-android;
 *  - the test APK declares `ExceptionCollectorAsService` under *the same file
 *    name*, from kotlinx-coroutines-test.
 *
 * Coroutines' own `FastServiceLoader` reads the first match and stops, so it
 * finds the test APK's entry, never sees the app's, and every Compose test
 * fails with "Exception handler was not found via a ServiceLoader" before it
 * composes anything. The JDK's `ServiceLoader` enumerates all of them, which is
 * what turning the fast path off selects.
 *
 * Set here rather than anywhere in a test because the flag is read once, in a
 * static initialiser, the first time coroutines is touched. The runner is
 * constructed before the application, which is the last point at which setting
 * it still means anything.
 */
class ChoirTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        System.setProperty(FAST_SERVICE_LOADER, "false")
        super.onCreate(arguments)
    }

    private companion object {
        const val FAST_SERVICE_LOADER = "kotlinx.coroutines.fast.service.loader"
    }
}
