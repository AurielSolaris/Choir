#!/bin/bash
# SPDX-FileCopyrightText: 2026 AurielSolaris
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Generates one short file per audio format and pushes them to a connected
# device, so the claims in playback/AudioFormats.kt can be checked against what
# the platform actually does rather than against documentation.
#
# Needs ffmpeg and adb on PATH. Note that ffmpeg has no Monkey's Audio encoder,
# so .ape has to come from somewhere else.
#
# Usage:  tools/make-format-probes.sh          generate and push
#         tools/make-format-probes.sh clean    remove them from the device

set -euo pipefail

DEVICE_DIR="/sdcard/Music/ChoirCodecTests"
OUT="${TMPDIR:-/tmp}/choir-format-probes"

if [ "${1:-}" = "clean" ]; then
    adb shell rm -rf "$DEVICE_DIR"
    adb shell content call --uri content://media --method scan_volume --arg external_primary
    echo "removed $DEVICE_DIR and rescanned"
    exit 0
fi

mkdir -p "$OUT"
cd "$OUT"

ffmpeg -hide_banner -loglevel error -f lavfi \
    -i "sine=frequency=220:duration=12,aformat=sample_fmts=s16:sample_rates=44100:channel_layouts=stereo" \
    -y src.wav

# Format, then the encoder arguments. The interesting cases are the last few:
# a codec Android cannot decode (alac), one in a container Media3 cannot open
# (wma, wv, tta), and one in a container it can open but whose codec its
# extractor does not recognise (wavpack in matroska).
probe() {
    local name="$1"; shift
    ffmpeg -hide_banner -loglevel error -i src.wav "$@" \
        -metadata title="Choir Format Probe ${name}" \
        -metadata artist="Format Probe" \
        -metadata album="Choir Codec Tests" \
        -y "probe.${name}" || echo "could not encode $name" >&2
}

probe flac     -c:a flac
probe opus     -c:a libopus
probe alac.m4a -c:a alac
probe ac3      -c:a ac3
probe aiff     -c:a pcm_s16be
probe wma      -c:a wmav2
probe wv       -c:a wavpack
probe tta      -c:a tta
probe mka      -c:a wavpack -f matroska

adb shell mkdir -p "$DEVICE_DIR"
for f in probe.*; do adb push "$f" "$DEVICE_DIR/$f"; done
adb shell content call --uri content://media --method scan_volume --arg external_primary

echo
echo "how the scanner filed them:"
adb shell "content query --uri content://media/external/file \
    --projection _display_name:mime_type:media_type:duration:_size \
    --where \"_display_name LIKE 'probe.%'\""
