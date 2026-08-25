# R8 rules for the instrumented test APK.
#
# Separate from proguard-rules.pro because none of this belongs in the app: it
# covers the test libraries only, which are compiled into their own APK and
# shrunk alongside the release build they exercise.

# Espresso's view-capture helper reaches for a coroutine-to-future adapter that
# is optional and not on the classpath, and Guava's annotations reference the
# javax.lang.model types that no Android runtime has.
-dontwarn androidx.concurrent.futures.SuspendToFutureAdapter
-dontwarn javax.lang.model.element.Modifier

# The runner finds test classes and methods by reflection, so nothing that
# carries a JUnit annotation may be renamed or removed.
-keep class androidx.test.** { *; }
-dontwarn androidx.test.**
-keep class app.auriel.choir.**Test { *; }
-keepclassmembers class app.auriel.choir.** {
    @org.junit.* <methods>;
    @org.junit.Rule <fields>;
}
-keep class org.junit.** { *; }
-dontwarn org.junit.**

# Room's MigrationTestHelper opens the exported schemas from the test APK's
# assets and instantiates the database class it is given.
-keep class androidx.room.testing.** { *; }
