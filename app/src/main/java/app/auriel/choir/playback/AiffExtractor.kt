// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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
 * Reads AIFF, the one missing demuxer that was never going to need a decoder.
 *
 * Media3 has extractors for the containers Android cares about, and AIFF is not
 * among them, so an `.aiff` fails before anything is asked to decode it. What
 * makes this the cheapest of the gaps to close is that the samples inside are
 * ordinary uncompressed PCM: only the chunked IFF wrapper and the byte order
 * stand between the file and the platform's own audio path.
 *
 * So this swaps the bytes and declares little-endian PCM, rather than declaring
 * big-endian and hoping. Big-endian raw PCM is a format Media3 can describe and
 * that audio sinks are not obliged to accept; a swap costs one pass over each
 * buffer and means an AIFF plays on a build with no FFmpeg in it at all.
 *
 * Handles AIFF, and AIFC where the compression is `NONE`, `sowt` (which is the
 * same samples the other way round, so no swap) or `twos`. Genuinely compressed
 * AIFC is declined at [sniff] rather than half-read.
 */
@UnstableApi
class AiffExtractor : Extractor {

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var headersParsed = false
    private var channelCount = 0
    private var sampleRate = 0
    private var bitsPerSample = 0
    private var bytesPerFrame = 0
    private var bigEndian = true

    /** Where the samples start and how many there are, from the SSND chunk. */
    private var dataStartPosition = 0L
    private var dataSize = 0L

    private var framesWritten = 0L

    private val buffer = ParsableByteArray(BUFFER_BYTES)

    /** A partial frame left over from the previous read, held at the front. */
    private var bufferedBytes = 0

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ByteArray(HEADER_BYTES)
        input.peekFully(header, 0, HEADER_BYTES)
        input.resetPeekPosition()

        if (header.string(0, 4) != "FORM") return false
        val form = header.string(8, 4)
        return form == "AIFF" || form == "AIFC"
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headersParsed) {
            if (!parseHeaders(input)) return Extractor.RESULT_END_OF_INPUT
            headersParsed = true
            return Extractor.RESULT_CONTINUE
        }
        return readSamples(input)
    }

    override fun seek(position: Long, timeUs: Long) {
        bufferedBytes = 0
        // Derived from the time rather than the byte position: the two agree
        // for constant-bitrate PCM, and the time is the thing the caller asked
        // for.
        framesWritten = if (sampleRate > 0) {
            timeUs * sampleRate / C.MICROS_PER_SECOND
        } else {
            0L
        }
    }

    override fun release() = Unit

    // --- Headers -------------------------------------------------------------

    /**
     * Walks chunks until the samples are reached, publishing the format on the
     * way. Returns false if the file ends before both COMM and SSND turn up.
     */
    private fun parseHeaders(input: ExtractorInput): Boolean {
        val form = ByteArray(HEADER_BYTES)
        input.readFully(form, 0, HEADER_BYTES)
        if (form.string(0, 4) != "FORM") return false

        var scanned = 0L
        val chunkHeader = ByteArray(CHUNK_HEADER_BYTES)

        while (scanned < MAX_SCAN_BYTES) {
            if (!input.readFully(chunkHeader, 0, CHUNK_HEADER_BYTES, /* allowEndOfInput= */ true)) {
                return false
            }
            val id = chunkHeader.string(0, 4)
            val size = chunkHeader.u32(4)
            if (size < 0) return false

            // Odd-length chunks carry a pad byte their size does not count.
            val padded = size + (size and 1L)

            when (id) {
                "COMM" -> {
                    if (size < COMM_BYTES) return false
                    val comm = ByteArray(size.toInt())
                    input.readFully(comm, 0, comm.size)
                    if (!readCommonChunk(comm)) return false
                    if (size and 1L == 1L) input.skipFully(1)
                }

                "SSND" -> {
                    // Two fields precede the samples: an offset into the chunk
                    // where they really begin, and a block size nothing uses.
                    val ssnd = ByteArray(SSND_PREFIX_BYTES)
                    input.readFully(ssnd, 0, SSND_PREFIX_BYTES)
                    val offset = ssnd.u32(0)
                    if (offset > 0) input.skipFully(offset.toInt())

                    dataStartPosition = input.position
                    dataSize = (size - SSND_PREFIX_BYTES - offset).coerceAtLeast(0L)
                    return publishFormat()
                }

                else -> input.skipFully(padded.toInt())
            }
            scanned += padded + CHUNK_HEADER_BYTES
        }
        return false
    }

    /** `channels, frames, sample size, sample rate`, then AIFC's compression. */
    private fun readCommonChunk(comm: ByteArray): Boolean {
        channelCount = comm.u16(0)
        bitsPerSample = comm.u16(6)
        sampleRate = comm.extendedFloat(8).toInt()

        if (channelCount <= 0 || sampleRate <= 0) return false
        bytesPerFrame = channelCount * (bitsPerSample / 8)
        if (bytesPerFrame <= 0) return false

        // AIFC appends a compression type. AIFF has none, and is big-endian.
        bigEndian = when {
            comm.size < COMM_BYTES + 4 -> true
            else -> when (val compression = comm.string(COMM_BYTES, 4)) {
                "NONE", "twos" -> true
                // Apple's name for "the same, little-endian" — the samples are
                // already in the order the platform wants.
                "sowt" -> false
                else -> {
                    MusicLog.i(TAG, "unsupported AIFC compression: $compression")
                    return false
                }
            }
        }
        return true
    }

    private fun publishFormat(): Boolean {
        val encoding = pcmEncodingFor(bitsPerSample)
        if (encoding == C.ENCODING_INVALID) {
            MusicLog.i(TAG, "unsupported AIFF sample size: $bitsPerSample")
            return false
        }

        val bytesPerSecond = bytesPerFrame * sampleRate
        val durationUs = if (bytesPerSecond > 0) {
            dataSize * C.MICROS_PER_SECOND / bytesPerSecond
        } else {
            C.TIME_UNSET
        }

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(encoding)
                .setChannelCount(channelCount)
                .setSampleRate(sampleRate)
                .setAverageBitrate(bytesPerSecond * 8)
                .setPeakBitrate(bytesPerSecond * 8)
                .setMaxInputSize(BUFFER_BYTES)
                .build(),
        )

        extractorOutput?.seekMap(
            PcmSeekMap(
                durationUs = durationUs,
                dataStartPosition = dataStartPosition,
                dataSize = dataSize,
                bytesPerSecond = bytesPerSecond,
                bytesPerFrame = bytesPerFrame,
            ),
        )
        return true
    }

    // --- Samples -------------------------------------------------------------

    private fun readSamples(input: ExtractorInput): Int {
        val output = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        val wanted = BUFFER_BYTES - bufferedBytes
        val read = input.read(buffer.data, bufferedBytes, wanted)
        if (read == C.RESULT_END_OF_INPUT) {
            // A trailing partial frame is not playable and not worth a
            // complaint; files do get truncated.
            return Extractor.RESULT_END_OF_INPUT
        }

        val available = bufferedBytes + read
        val usable = available - available % bytesPerFrame
        if (usable <= 0) {
            bufferedBytes = available
            return Extractor.RESULT_CONTINUE
        }

        if (bigEndian) swapSampleBytes(buffer.data, usable, bitsPerSample)

        buffer.setPosition(0)
        buffer.setLimit(usable)
        output.sampleData(buffer, usable)
        output.sampleMetadata(
            /* timeUs= */ framesWritten * C.MICROS_PER_SECOND / sampleRate,
            /* flags= */ C.BUFFER_FLAG_KEY_FRAME,
            /* size= */ usable,
            /* offset= */ 0,
            /* cryptoData= */ null,
        )
        framesWritten += usable / bytesPerFrame

        // Carry the partial frame to the front for the next pass.
        bufferedBytes = available - usable
        if (bufferedBytes > 0) {
            System.arraycopy(buffer.data, usable, buffer.data, 0, bufferedBytes)
        }
        return Extractor.RESULT_CONTINUE
    }

    private fun pcmEncodingFor(bits: Int): Int = when (bits) {
        8 -> C.ENCODING_PCM_8BIT
        16 -> C.ENCODING_PCM_16BIT
        24 -> C.ENCODING_PCM_24BIT
        32 -> C.ENCODING_PCM_32BIT
        else -> C.ENCODING_INVALID
    }

    /**
     * Seeking in constant-bitrate PCM is arithmetic, so this is exact rather
     * than the approximation a compressed format would have to settle for.
     */
    private class PcmSeekMap(
        private val durationUs: Long,
        private val dataStartPosition: Long,
        private val dataSize: Long,
        private val bytesPerSecond: Int,
        private val bytesPerFrame: Int,
    ) : SeekMap {

        override fun isSeekable(): Boolean = bytesPerSecond > 0 && durationUs != C.TIME_UNSET

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!isSeekable) return SeekMap.SeekPoints(SeekPoint.START)

            val clamped = timeUs.coerceIn(0L, durationUs)
            val rawOffset = clamped * bytesPerSecond / C.MICROS_PER_SECOND
            // Landing mid-frame would shift every later sample by a byte and
            // turn the track into noise.
            val offset = (rawOffset - rawOffset % bytesPerFrame)
                .coerceIn(0L, (dataSize - bytesPerFrame).coerceAtLeast(0L))

            val actualTimeUs = offset * C.MICROS_PER_SECOND / bytesPerSecond
            return SeekMap.SeekPoints(SeekPoint(actualTimeUs, dataStartPosition + offset))
        }
    }

    private companion object {
        const val TAG = "AiffExtractor"

        const val HEADER_BYTES = 12
        const val CHUNK_HEADER_BYTES = 8

        /** channels, frame count, sample size, and the 80-bit sample rate. */
        const val COMM_BYTES = 18

        /** The offset and block-size fields ahead of the samples. */
        const val SSND_PREFIX_BYTES = 8

        const val BUFFER_BYTES = 32 * 1024

        /** Far enough past any plausible run of metadata chunks. */
        const val MAX_SCAN_BYTES = 64L * 1024 * 1024
    }
}

private const val TWO_TO_THE_32 = 4_294_967_296.0

/**
 * Reverses each sample in place, turning AIFF's big-endian PCM into the
 * little-endian the platform expects.
 *
 * This is the whole reason an AIFF can play with no decoder at all: one pass
 * over a 32 kB buffer a few times a second, against handing every sample to a
 * software codec.
 *
 * It is also the one part of the extractor a device cannot check. Wrong-endian
 * audio is noise, and noise plays at exactly the right rate and duration — so
 * everything observable looks correct. Hence a unit test.
 */
internal fun swapSampleBytes(data: ByteArray, length: Int, bitsPerSample: Int) {
    when (bitsPerSample) {
        16 -> {
            var index = 0
            while (index + 1 < length) {
                val first = data[index]
                data[index] = data[index + 1]
                data[index + 1] = first
                index += 2
            }
        }

        24 -> {
            var index = 0
            while (index + 2 < length) {
                val first = data[index]
                data[index] = data[index + 2]
                data[index + 2] = first
                index += 3
            }
        }

        32 -> {
            var index = 0
            while (index + 3 < length) {
                val first = data[index]
                val second = data[index + 1]
                data[index] = data[index + 3]
                data[index + 1] = data[index + 2]
                data[index + 2] = second
                data[index + 3] = first
                index += 4
            }
        }

        // AIFF's 8-bit samples are signed where the platform's are not, so the
        // fix is a bias rather than a swap. There is no byte order in one byte.
        8 -> for (index in 0 until length) {
            data[index] = (data[index].toInt() xor 0x80).toByte()
        }
    }
}

// --- Big-endian reading ------------------------------------------------------
//
// Internal rather than private so the awkward one — the 80-bit float — can be
// tested directly. A wrong sample rate does not fail; it plays at the wrong
// pitch, which is the kind of bug that survives a long time.

internal fun ByteArray.string(offset: Int, length: Int): String =
    if (offset + length > size) "" else String(this, offset, length, Charsets.US_ASCII)

internal fun ByteArray.u16(offset: Int): Int =
    if (offset + 2 > size) 0 else ((this[offset].toInt() and 0xFF) shl 8) or
        (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.u32(offset: Int): Long {
    if (offset + 4 > size) return -1
    var value = 0L
    for (index in offset until offset + 4) {
        value = (value shl 8) or (this[index].toLong() and 0xFF)
    }
    return value
}

/**
 * An 80-bit IEEE 754 extended float, which is how AIFF — and only AIFF —
 * records a sample rate.
 *
 * A sign bit, fifteen bits of exponent, then a 64-bit mantissa whose leading
 * bit is written out rather than implied. Nothing else in audio uses this, and
 * there is no library function for it, so: value = mantissa × 2^(exponent−16383−63).
 */
internal fun ByteArray.extendedFloat(offset: Int): Double {
    if (offset + 10 > size) return 0.0

    val exponent = (((this[offset].toInt() and 0x7F) shl 8) or
        (this[offset + 1].toInt() and 0xFF))

    var mantissa = 0L
    for (index in offset + 2 until offset + 10) {
        mantissa = (mantissa shl 8) or (this[index].toLong() and 0xFF)
    }
    if (exponent == 0 && mantissa == 0L) return 0.0

    // The mantissa is 64 unsigned bits and Long is signed, so its top bit —
    // which is *always* set in a normalised value, meaning every real file —
    // would otherwise read as a sign and give a negative sample rate. Summing
    // the halves as doubles is exact: both fit in 53 bits with room to spare.
    val high = (mantissa ushr 32).toDouble()
    val low = (mantissa and 0xFFFFFFFFL).toDouble()
    val magnitude = (high * TWO_TO_THE_32 + low) * Math.scalb(1.0, exponent - 16383 - 63)

    return if (this[offset].toInt() and 0x80 != 0) -magnitude else magnitude
}
