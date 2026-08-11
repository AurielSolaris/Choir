#!/bin/bash
# SPDX-FileCopyrightText: 2026 AurielSolaris
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds the Media3 FFmpeg audio decoder and drops the result into
# app/src/main/jniLibs, which is where ChoirRenderersFactory expects to find it.
#
# This exists because Google does not publish media3-decoder-ffmpeg to Maven —
# it ships as source to be compiled against the NDK. Choir builds and runs
# without it; running this is what adds the codecs the platform left out.
#
# Needs a Linux host (WSL is fine), curl, unzip, git, make, gcc and nasm.
# Everything else it downloads into $WORK.
#
# Usage:  tools/build-ffmpeg.sh [abi ...]     (default: all four)

set -euo pipefail

WORK="${CHOIR_FFMPEG_WORK:-$HOME/choir-ffmpeg}"
NDK_VERSION="${CHOIR_NDK_VERSION:-r27c}"
MEDIA3_TAG="${CHOIR_MEDIA3_TAG:-1.5.1}"
FFMPEG_TAG="${CHOIR_FFMPEG_TAG:-n6.0}"

# Must not exceed the app's minSdk, and matching it keeps the binary small.
ANDROID_API=29

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_LIBS="$REPO_ROOT/app/src/main/jniLibs"

# The audio codecs Choir wants and Android does not guarantee.
#
# Some of these cannot be reached yet: Media3 has no demuxer for APE, WavPack,
# WMA, Musepack, TTA, TAK or DSD, so those files still will not open. They are
# compiled in anyway — they are a rounding error in binary size, and the day a
# demuxer lands the decoder should already be there. AudioFormats documents
# which is which.
DECODERS=(
    # Lossless, in containers Media3 already opens
    alac flac
    # Lossless, still waiting on a demuxer
    ape wavpack tta tak shorten mlp truehd
    # Windows Media
    wmav1 wmav2 wmapro wmalossless wmavoice
    # Musepack and DSD
    mpc7 mpc8 dsd_lsbf dsd_msbf dsd_lsbf_planar dsd_msbf_planar
    # Dolby and DTS, which many devices decline to decode
    ac3 eac3 dca
    # PCM, for AIFF and the odder WAVE variants
    pcm_s16be pcm_s24be pcm_s32be pcm_f32be pcm_s16le pcm_s24le pcm_s32le
    pcm_u8 pcm_alaw pcm_mulaw
    # Lossy, as a fallback where a device's own decoder is fussy
    mp3 aac aac_latm vorbis opus
    # RealAudio, for completeness at almost no cost
    cook ra_144 ra_288
)

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then
    ABIS=(armeabi-v7a arm64-v8a x86 x86_64)
fi

NDK="$WORK/android-ndk-$NDK_VERSION"
MEDIA3="$WORK/media"
MODULE="$MEDIA3/libraries/decoder_ffmpeg/src/main"

step() { printf '\n=== %s ===\n' "$1"; }

step "fetching sources into $WORK"
mkdir -p "$WORK"

if [ ! -d "$NDK" ]; then
    curl -fL -o "$WORK/ndk.zip" \
        "https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip"
    unzip -q -d "$WORK" "$WORK/ndk.zip"
    rm -f "$WORK/ndk.zip"
fi

if [ ! -d "$MEDIA3" ]; then
    git clone --depth 1 --branch "$MEDIA3_TAG" https://github.com/androidx/media.git "$MEDIA3"
fi

if [ ! -d "$MODULE/jni/ffmpeg" ]; then
    git clone --depth 1 --branch "$FFMPEG_TAG" \
        https://github.com/FFmpeg/FFmpeg.git "$MODULE/jni/ffmpeg"
fi

step "building FFmpeg static libraries"
# Upstream's script always builds all four ABIs. Ours honours the argument
# list, because waiting on x86 to test an arm64 phone is twenty wasted minutes.
(
    cd "$MODULE/jni/ffmpeg"

    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
    COMMON=(
        --target-os=android --enable-static --disable-shared
        --disable-doc --disable-programs --disable-everything
        --disable-avdevice --disable-avformat --disable-swscale
        --disable-postproc --disable-avfilter --disable-symver
        --enable-swresample --extra-ldexeflags=-pie
        --disable-v4l2-m2m --disable-vulkan
    )
    for decoder in "${DECODERS[@]}"; do
        COMMON+=("--enable-decoder=$decoder")
    done

    for abi in "${ABIS[@]}"; do
        [ -f "android-libs/$abi/libavcodec.a" ] && { echo "$abi already built"; continue; }
        echo "--- $abi ---"

        case "$abi" in
            armeabi-v7a) arch=arm;     cpu=armv7-a; triple="armv7a-linux-androideabi$ANDROID_API"
                         extra=(--extra-cflags="-march=armv7-a -mfloat-abi=softfp"
                                --extra-ldflags="-Wl,--fix-cortex-a8") ;;
            arm64-v8a)   arch=aarch64; cpu=armv8-a; triple="aarch64-linux-android$ANDROID_API"
                         extra=() ;;
            x86)         arch=x86;     cpu=i686;    triple="i686-linux-android$ANDROID_API"
                         extra=(--disable-asm) ;;
            x86_64)      arch=x86_64;  cpu=x86-64;  triple="x86_64-linux-android$ANDROID_API"
                         extra=(--disable-asm) ;;
            *) echo "unknown ABI: $abi" >&2; exit 1 ;;
        esac

        ./configure \
            --libdir="android-libs/$abi" \
            --arch="$arch" --cpu="$cpu" \
            --cross-prefix="$TOOLCHAIN/$triple-" \
            --nm="$TOOLCHAIN/llvm-nm" --ar="$TOOLCHAIN/llvm-ar" \
            --ranlib="$TOOLCHAIN/llvm-ranlib" --strip="$TOOLCHAIN/llvm-strip" \
            "${extra[@]}" "${COMMON[@]}"
        make -j"$(nproc)"
        make install-libs
        make clean
    done
)

step "building libffmpegJNI"
# The module carries a CMakeLists but no gradle wiring we can use outside an
# Android project, so CMake is driven directly with the NDK's toolchain file.
# That keeps the whole build to a Linux host with no Android SDK on it.
for abi in "${ABIS[@]}"; do
    build="$WORK/jni-build/$abi"
    cmake -S "$MODULE/jni" -B "$build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$ANDROID_API" \
        -DCMAKE_BUILD_TYPE=Release
    cmake --build "$build" -j"$(nproc)"
done

step "stripping and copying into the app"
# CMake's Release build leaves the symbol table in, which is several megabytes
# of names nothing on a phone will ever read.
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
for abi in "${ABIS[@]}"; do
    so="$WORK/jni-build/$abi/libffmpegJNI.so"
    [ -f "$so" ] || { echo "nothing built for $abi" >&2; exit 1; }
    mkdir -p "$JNI_LIBS/$abi"
    cp "$so" "$JNI_LIBS/$abi/"
    before=$(stat -c%s "$JNI_LIBS/$abi/libffmpegJNI.so")
    "$STRIP" --strip-unneeded "$JNI_LIBS/$abi/libffmpegJNI.so"
    after=$(stat -c%s "$JNI_LIBS/$abi/libffmpegJNI.so")
    printf '%s: %s -> %s bytes\n' "$abi" "$before" "$after"
done

step "copying the decoder's Java sources"
# The extension's Kotlin/Java half is Apache-2.0 and tiny, and vendoring it
# avoids requiring a full Android SDK inside the build host just to produce an
# AAR that would contain these same files.
SRC="$MODULE/java/androidx/media3/decoder/ffmpeg"
DEST="$REPO_ROOT/app/src/main/java/androidx/media3/decoder/ffmpeg"
mkdir -p "$DEST"
# Audio only. The video renderer is marked experimental upstream and Choir has
# nothing to show a frame on.
for f in FfmpegAudioDecoder FfmpegAudioRenderer FfmpegDecoderException FfmpegLibrary package-info; do
    cp -v "$SRC/$f.java" "$DEST/"
done

step "done"
find "$JNI_LIBS" -name '*.so' -exec ls -la {} +
