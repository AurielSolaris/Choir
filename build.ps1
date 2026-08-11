# Choir build script — Windows PowerShell
param(
    [ValidateSet("debug", "release", "test", "clean", "install", "install-release")]
    [string]$Target = "debug"
)

switch ($Target) {
    "debug"           { ./gradlew assembleDebug }
    "release"         { ./gradlew assembleRelease }
    "test"            { ./gradlew test }
    "clean"           { ./gradlew clean }
    "install"         { ./gradlew installDebug }
    "install-release" { ./gradlew installRelease }
}
