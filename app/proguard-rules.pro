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

# The service is named from the manifest and bound to by name from the app.
-keep class app.auriel.choir.playback.PlaybackService { *; }

# Guava's ListenableFuture ships with annotations R8 has no bodies for.
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
