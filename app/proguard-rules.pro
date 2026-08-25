# ProGuard/R8 rules for Choir

# Room
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}

# Koin resolves by reflected type, so the classes it constructs must keep their
# names and constructors.
-keep class org.koin.** { *; }
-keepclassmembers class app.auriel.choir.** {
    public <init>(...);
}

# Media3 loads renderers, decoders and the session service reflectively.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# The FFmpeg audio decoder, when a build has one. Nothing refers to these
# classes by name — Choir finds them with Class.forName precisely so it can be
# built without them — so R8 would otherwise see them as unreachable and delete
# the decoder out of the APK it was just added to. Both the official extension
# (covered by the media3 rule above) and the prebuilt community port are named
# here; the JNI entry points must survive too, since the native library looks
# them up by signature.
-keep class io.github.anilbeesetti.nextlib.** { *; }
-dontwarn io.github.anilbeesetti.nextlib.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# The service is named from the manifest and bound to by name from the app.
-keep class app.auriel.choir.playback.PlaybackService { *; }

# Guava's ListenableFuture ships with annotations R8 has no bodies for.
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# Kept for the instrumented tests, which run against this build.
#
# Kotlin's Intrinsics class — every null check the compiler emits calls into it —
# ships inside the app APK, and the test APK is minified separately against the
# app's mapping rather than carrying its own copy. R8 shrinks Intrinsics down to
# the overloads the *app* happens to use, so a check the test code emits and the
# app does not is a NoSuchMethodError in the runner's first instruction, before
# any test starts. It is a handful of tiny static methods.
-keep class kotlin.jvm.internal.Intrinsics { *; }
