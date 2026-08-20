// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import app.auriel.choir.core.MusicLog

/**
 * Reads WavPack, the format that made the demuxer/decoder distinction concrete.
 *
 * Choir has shipped a WavPack *decoder* since v0.3.0 — FFmpeg builds one in —
 * and a `.wv` still would not play, because Media3 has no extractor for the
 * container and the file was refused before any decoder was consulted. This is
 * the missing half.
 *
 * WavPack is unusually kind to a demuxer. A file is a plain sequence of blocks,
 * each starting with a 32-byte header giving its own size, its position in the
 * stream in samples, and the shape of the audio inside it. Nothing has to be
 * inferred from an index that might be absent, every block decodes on its own,
 * and a block found by scanning states the time it belongs at — so seeking
 * lands exactly, rather than settling for what a bitrate estimate suggests.
 *
 * Blocks are passed to the decoder whole, headers included: FFmpeg's `wavpack`
 * decoder reads the same header to learn how the samples were coded.
 */
@UnstableApi
class WavPackExtractor : Extractor {

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var sampleRate = 0
    private var channelCount = 0

    /** Total samples in the stream, or -1 where the header declines to say. */
    private var totalSamples = -1L

    private var firstBlockPosition = 0L
    private var formatPublished = false

    /**
     * True after a seek, until the reader has found a block boundary again. A
     * seek lands on a byte position derived from the average bitrate, which is
     * almost never the start of a block.
     */
    private var resyncNeeded = false

    /** Bytes of the current frame already handed to the output. */
    private var pendingFrameBytes = 0
    private var pendingFrameTimeUs = C.TIME_UNSET

    private val header = ByteArray(WV_HEADER_BYTES)
    private val block = ParsableByteArray(INITIAL_BLOCK_CAPACITY)

    override fun sniff(input: ExtractorInput): Boolean {
        val start = leadingTagBytes(input)
        val peeked = ByteArray(WV_HEADER_BYTES)

        input.resetPeekPosition()
        if (start > 0) input.advancePeekPosition(start)
        val read = input.peekFully(peeked, 0, WV_HEADER_BYTES, /* allowEndOfInput= */ true)
        input.resetPeekPosition()

        return read && isWavPackHeader(peeked)
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!formatPublished) {
            if (!publishFormat(input)) return Extractor.RESULT_END_OF_INPUT
            formatPublished = true
            return Extractor.RESULT_CONTINUE
        }
        if (resyncNeeded) {
            if (!resync(input)) return Extractor.RESULT_END_OF_INPUT
            resyncNeeded = false
        }
        return readBlock(input)
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingFrameBytes = 0
        pendingFrameTimeUs = C.TIME_UNSET
        // The first block is the one place a boundary is already known.
        resyncNeeded = position > firstBlockPosition
    }

    override fun release() = Unit

    // --- Format --------------------------------------------------------------

    /**
     * Reads the first block's header — and, where the header defers to them,
     * its metadata sub-blocks — to learn what the stream is.
     *
     * Peeked rather than read: the block is the first thing the decoder needs,
     * so nothing here may consume it.
     */
    private fun publishFormat(input: ExtractorInput): Boolean {
        val skip = leadingTagBytes(input)
        if (skip > 0) input.skipFully(skip)

        input.resetPeekPosition()
        if (!input.peekFully(header, 0, WV_HEADER_BYTES, /* allowEndOfInput= */ true)) return false
        if (!isWavPackHeader(header)) {
            MusicLog.i(TAG, "no WavPack block at the start of the file")
            return false
        }

        firstBlockPosition = input.position
        val flags = header.u32le(WV_FLAGS_OFFSET)
        totalSamples = wavPackTotalSamples(header)

        sampleRate = wavPackSampleRate(flags)
        channelCount = if (flags and WV_FLAG_MONO == 0L) 2 else 1

        // Two things the fixed header cannot always express: a sample rate
        // outside the fifteen it has room for, and more channels than a stereo
        // pair. Both live in the sub-blocks that follow it.
        val multichannel = flags and WV_FLAG_FINAL_BLOCK == 0L
        if (sampleRate == 0 || multichannel) readSubBlocks(input)

        if (sampleRate <= 0 || channelCount <= 0) {
            MusicLog.i(TAG, "WavPack block declares no usable sample rate or channel count")
            return false
        }

        val durationUs = if (totalSamples > 0) {
            totalSamples * C.MICROS_PER_SECOND / sampleRate
        } else {
            C.TIME_UNSET
        }

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType(ChoirMimeTypes.AUDIO_WAVPACK)
                .setChannelCount(channelCount)
                .setSampleRate(sampleRate)
                .build(),
        )
        extractorOutput?.seekMap(
            BlockSeekMap(
                durationUs = durationUs,
                firstBlockPosition = firstBlockPosition,
                inputLength = input.length,
            ),
        )
        MusicLog.d(TAG, "WavPack: $sampleRate Hz, $channelCount ch, ${durationUs}us")
        return true
    }

    /**
     * Walks the first block's metadata sub-blocks for the fields the fixed
     * header has no room for.
     *
     * Each is an id byte, a length counted in 16-bit words, then its data — a
     * tiny type-length-value scheme carrying everything from the channel layout
     * to the album art. Two matter here; the rest are stepped over.
     */
    private fun readSubBlocks(input: ExtractorInput) {
        val bodySize = (header.u32le(WV_SIZE_OFFSET) + WV_SIZE_BIAS - WV_HEADER_BYTES).toInt()
        if (bodySize <= 0 || bodySize > MAX_BLOCK_BYTES) return

        val body = ByteArray(bodySize)
        input.resetPeekPosition()
        input.advancePeekPosition(WV_HEADER_BYTES)
        val read = input.peekFully(body, 0, bodySize, /* allowEndOfInput= */ true)
        input.resetPeekPosition()
        if (!read) return

        var offset = 0
        while (offset + 2 <= bodySize) {
            val id = body[offset].toInt() and 0xFF
            val large = id and ID_LARGE != 0
            val headerBytes = if (large) 4 else 2
            if (offset + headerBytes > bodySize) return

            val words = if (large) {
                (body[offset + 1].toInt() and 0xFF) or
                    ((body[offset + 2].toInt() and 0xFF) shl 8) or
                    ((body[offset + 3].toInt() and 0xFF) shl 16)
            } else {
                body[offset + 1].toInt() and 0xFF
            }

            val dataStart = offset + headerBytes
            val dataSize = words * 2 - if (id and ID_ODD_SIZE != 0) 1 else 0
            if (words < 0 || dataSize < 0 || dataStart + words * 2 > bodySize) return

            when (id and ID_FUNCTION_MASK) {
                ID_SAMPLE_RATE -> if (dataSize >= 3) {
                    sampleRate = (body[dataStart].toInt() and 0xFF) or
                        ((body[dataStart + 1].toInt() and 0xFF) shl 8) or
                        ((body[dataStart + 2].toInt() and 0xFF) shl 16)
                }

                ID_CHANNEL_INFO -> if (dataSize >= 1) {
                    channelCount = body[dataStart].toInt() and 0xFF
                }
            }

            offset = dataStart + words * 2
        }
    }

    // --- Blocks --------------------------------------------------------------

    /**
     * Reads one block and, where it carries audio, hands it to the output.
     *
     * A multichannel file splits one stretch of audio across several blocks — a
     * stereo pair, then another, then a centre channel — marked by the initial
     * and final flags. Those are concatenated into a single sample, because
     * that is the unit the decoder expects to be given.
     */
    private fun readBlock(input: ExtractorInput): Int {
        val output = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        if (!input.readFully(header, 0, WV_HEADER_BYTES, /* allowEndOfInput= */ true)) {
            return endOfStream()
        }
        if (!isWavPackHeader(header)) {
            // Junk between blocks: an APEv2 tag, an ID3v1 trailer, or a file
            // that was concatenated carelessly. Look for the next block rather
            // than abandoning the rest of the track.
            MusicLog.d(TAG, "lost the block boundary; resynchronising")
            resyncNeeded = true
            return Extractor.RESULT_CONTINUE
        }

        val bodySize = (header.u32le(WV_SIZE_OFFSET) + WV_SIZE_BIAS - WV_HEADER_BYTES).toInt()
        if (bodySize < 0 || bodySize > MAX_BLOCK_BYTES) {
            MusicLog.i(TAG, "implausible WavPack block size: $bodySize")
            return Extractor.RESULT_END_OF_INPUT
        }

        block.reset(WV_HEADER_BYTES + bodySize)
        System.arraycopy(header, 0, block.data, 0, WV_HEADER_BYTES)
        if (bodySize > 0 &&
            !input.readFully(block.data, WV_HEADER_BYTES, bodySize, /* allowEndOfInput= */ true)
        ) {
            return endOfStream()
        }

        // A block with no samples carries only metadata — tags, or stream
        // information a later block refers back to. On its own it decodes to
        // nothing, and handing it over would only produce an empty buffer.
        if (header.u32le(WV_SAMPLES_OFFSET) == 0L) return Extractor.RESULT_CONTINUE

        if (pendingFrameBytes == 0) {
            pendingFrameTimeUs =
                wavPackBlockIndex(header) * C.MICROS_PER_SECOND / sampleRate
        }
        output.sampleData(block, block.limit())
        pendingFrameBytes += block.limit()

        if (header.u32le(WV_FLAGS_OFFSET) and WV_FLAG_FINAL_BLOCK != 0L) {
            output.sampleMetadata(
                /* timeUs= */ pendingFrameTimeUs,
                // Every block decodes independently, so all of them are seek
                // points and none of them is a difference from another.
                /* flags= */ C.BUFFER_FLAG_KEY_FRAME,
                /* size= */ pendingFrameBytes,
                /* offset= */ 0,
                /* cryptoData= */ null,
            )
            pendingFrameBytes = 0
        }
        return Extractor.RESULT_CONTINUE
    }

    /**
     * Ends the stream, dropping a frame whose final block never arrived.
     *
     * A truncated multichannel frame is missing channels, so it is thrown away
     * rather than handed over short — a decoder given half a frame produces
     * noise, which is worse than a track that stops.
     */
    private fun endOfStream(): Int {
        pendingFrameBytes = 0
        pendingFrameTimeUs = C.TIME_UNSET
        return Extractor.RESULT_END_OF_INPUT
    }

    /**
     * Scans forward for the next block header after a seek.
     *
     * Byte by byte, because WavPack blocks are not padded to any alignment and
     * a boundary can fall anywhere. The magic alone would match inside
     * compressed audio often enough to matter, so a candidate is accepted only
     * once its version and size fields also read as a header.
     */
    private fun resync(input: ExtractorInput): Boolean {
        val candidate = ByteArray(WV_HEADER_BYTES)
        var skipped = 0L

        while (skipped < MAX_RESYNC_BYTES) {
            input.resetPeekPosition()
            if (!input.peekFully(candidate, 0, WV_HEADER_BYTES, /* allowEndOfInput= */ true)) {
                return false
            }
            if (isWavPackHeader(candidate)) {
                input.resetPeekPosition()
                return true
            }
            input.skipFully(1)
            skipped++
        }
        MusicLog.i(TAG, "gave up looking for a block after $MAX_RESYNC_BYTES bytes")
        return false
    }

    /**
     * Seeking by proportion of the file, corrected on arrival.
     *
     * WavPack keeps no index, so the byte position for a given time is an
     * estimate from the average bitrate — but every block states the sample it
     * begins at, so the timestamp that comes back after the resync is the true
     * one. The player corrects itself and nothing accumulates.
     */
    private class BlockSeekMap(
        private val durationUs: Long,
        private val firstBlockPosition: Long,
        private val inputLength: Long,
    ) : SeekMap {

        private val audioBytes: Long
            get() = (inputLength - firstBlockPosition).coerceAtLeast(0L)

        override fun isSeekable(): Boolean =
            durationUs != C.TIME_UNSET && inputLength != C.LENGTH_UNSET.toLong() && audioBytes > 0

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!isSeekable) return SeekMap.SeekPoints(SeekPoint.START)

            val clamped = timeUs.coerceIn(0L, durationUs)
            val offset = if (durationUs == 0L) 0L else audioBytes * clamped / durationUs
            return SeekMap.SeekPoints(SeekPoint(clamped, firstBlockPosition + offset))
        }
    }

    private companion object {
        const val TAG = "WavPackExtractor"

        /** The id byte of a metadata sub-block: two flag bits, then a function. */
        const val ID_ODD_SIZE = 0x40
        const val ID_LARGE = 0x80
        const val ID_FUNCTION_MASK = 0x3F
        const val ID_CHANNEL_INFO = 0x0D
        const val ID_SAMPLE_RATE = 0x07

        const val INITIAL_BLOCK_CAPACITY = 64 * 1024

        /** Long enough to cross a tag block, short enough to fail promptly. */
        const val MAX_RESYNC_BYTES = 1L * 1024 * 1024
    }
}

// --- The block header --------------------------------------------------------
//
// Internal rather than private so it can be tested against handmade headers. A
// misread header does not fail loudly: it plays the wrong stretch of audio, or
// reports the wrong length, which is the kind of bug that survives a listen.

internal const val WV_HEADER_BYTES = 32

/** `ckSize` counts from after itself, so the block is eight bytes longer. */
internal const val WV_SIZE_BIAS = 8L

internal const val WV_SIZE_OFFSET = 4
internal const val WV_VERSION_OFFSET = 8
internal const val WV_BLOCK_INDEX_HIGH_OFFSET = 10
internal const val WV_TOTAL_SAMPLES_HIGH_OFFSET = 11
internal const val WV_TOTAL_SAMPLES_OFFSET = 12
internal const val WV_BLOCK_INDEX_OFFSET = 16
internal const val WV_SAMPLES_OFFSET = 20
internal const val WV_FLAGS_OFFSET = 24

internal const val WV_FLAG_MONO = 0x0000_0004L
internal const val WV_FLAG_FINAL_BLOCK = 0x0000_1000L

/** Versions 4.0 to 5.x, which covers every WavPack file in circulation. */
internal const val WV_MIN_VERSION = 0x0402
internal const val WV_MAX_VERSION = 0x0410

/** A ceiling on a single block, well above what any encoder produces. */
internal const val MAX_BLOCK_BYTES = 8 * 1024 * 1024

/** `0xFFFFFFFF` in the total-samples field means "not stated". */
private const val WV_UNKNOWN_SAMPLES = 0xFFFF_FFFFL

/**
 * The fifteen sample rates a block header can name outright. Anything else is
 * written as index 15 and spelled out in a metadata sub-block.
 */
private val WV_SAMPLE_RATES = intArrayOf(
    6_000, 8_000, 9_600, 11_025, 12_000, 16_000, 22_050, 24_000,
    32_000, 44_100, 48_000, 64_000, 88_200, 96_000, 192_000,
)

/** True when these bytes read as a WavPack block header. */
internal fun isWavPackHeader(header: ByteArray): Boolean {
    if (header.size < WV_HEADER_BYTES) return false
    if (header[0] != 'w'.code.toByte() || header[1] != 'v'.code.toByte() ||
        header[2] != 'p'.code.toByte() || header[3] != 'k'.code.toByte()
    ) {
        return false
    }

    val version = header.u16le(WV_VERSION_OFFSET)
    if (version < WV_MIN_VERSION || version > WV_MAX_VERSION) return false

    // ckSize covers everything after itself, so it is at least the rest of the
    // header — and a block far larger than any encoder emits is not one.
    val size = header.u32le(WV_SIZE_OFFSET)
    return size >= WV_HEADER_BYTES - WV_SIZE_BIAS && size <= MAX_BLOCK_BYTES
}

/**
 * Total samples in the stream, or -1 where the file does not say.
 *
 * WavPack 5 needed more than 32 bits for this and had no spare field, so the
 * top eight bits went into a byte version 4 leaves at zero — which makes the
 * same arithmetic correct for both.
 */
internal fun wavPackTotalSamples(header: ByteArray): Long {
    val low = header.u32le(WV_TOTAL_SAMPLES_OFFSET)
    if (low == WV_UNKNOWN_SAMPLES) return -1L
    val high = header[WV_TOTAL_SAMPLES_HIGH_OFFSET].toLong() and 0xFF
    return (high shl 32) or low
}

/** The sample this block starts at, split the same way as [wavPackTotalSamples]. */
internal fun wavPackBlockIndex(header: ByteArray): Long {
    val low = header.u32le(WV_BLOCK_INDEX_OFFSET)
    val high = header[WV_BLOCK_INDEX_HIGH_OFFSET].toLong() and 0xFF
    return (high shl 32) or low
}

/** The sample rate a block's flags name, or 0 when they defer to a sub-block. */
internal fun wavPackSampleRate(flags: Long): Int {
    val index = ((flags shr 23) and 0xFL).toInt()
    return if (index < WV_SAMPLE_RATES.size) WV_SAMPLE_RATES[index] else 0
}

/**
 * How many bytes of ID3v2 sit in front of the audio, if any.
 *
 * Neither WavPack nor Monkey's Audio has any business carrying an ID3v2 tag,
 * and taggers write them anyway. The tag states its own length, so stepping
 * over it costs one peek and saves a file that would otherwise refuse to open.
 */
internal fun leadingTagBytes(input: ExtractorInput): Int {
    val tag = ByteArray(ID3_HEADER_BYTES)
    input.resetPeekPosition()
    val read = input.peekFully(tag, 0, ID3_HEADER_BYTES, /* allowEndOfInput= */ true)
    input.resetPeekPosition()
    if (!read) return 0

    if (tag[0] != 'I'.code.toByte() || tag[1] != 'D'.code.toByte() ||
        tag[2] != '3'.code.toByte()
    ) {
        return 0
    }

    // Synchsafe: seven bits per byte, so no run of bytes in the length can be
    // mistaken for the frame sync a decoder scans for.
    var size = 0
    for (index in 6 until 10) {
        val byte = tag[index].toInt() and 0xFF
        if (byte and 0x80 != 0) return 0
        size = (size shl 7) or byte
    }
    val footer = if (tag[5].toInt() and 0x10 != 0) ID3_HEADER_BYTES else 0
    return ID3_HEADER_BYTES + size + footer
}

private const val ID3_HEADER_BYTES = 10

internal fun ByteArray.u16le(offset: Int): Int =
    if (offset + 2 > size) 0 else (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u32le(offset: Int): Long {
    if (offset + 4 > size) return 0L
    var value = 0L
    for (index in offset + 3 downTo offset) {
        value = (value shl 8) or (this[index].toLong() and 0xFF)
    }
    return value
}
