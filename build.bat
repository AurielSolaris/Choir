@echo off
REM Choir build script — Windows CMD fallback

if "%~1"=="" set TARGET=debug
if not "%~1"=="" set TARGET=%1

if "%TARGET%"=="debug"           call gradlew assembleDebug
if "%TARGET%"=="release"         call gradlew assembleRelease
if "%TARGET%"=="test"            call gradlew test
if "%TARGET%"=="clean"           call gradlew clean
if "%TARGET%"=="install"         call gradlew installDebug
if "%TARGET%"=="install-release" call gradlew installRelease
