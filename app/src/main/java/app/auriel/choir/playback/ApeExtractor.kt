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
 * Reads Monkey's Audio, the format that keeps its map at the front.
 *
 * WavPack scatters its structure through the file and can be picked up
 * anywhere; APE does the opposite. Everything needed to read the stream — how
 * many frames there are, how many samples each holds, and the byte offset of
 * every one of them — sits in a header and a seek table ahead of the first
 * frame. Read those and the file is fully described; fail to read them and
 * there is nothing to scan forward to, because an APE frame carries no sync
 * word and no length of its own. That is why there is no resync path here: a
 * damaged header is the end of the file, not a hiccup to recover from.
 *
 * The compensation is that seeking is *exact*. The seek table names the first
 * byte of every frame, so a seek lands on a real frame boundary at a known
 * sample, with none of the estimate-and-correct that a format without an index
 * forces on [WavPackExtractor].
 *
 * ## What the decoder is handed
 *
 * FFmpeg's `ape` decoder never reads the file header. It is given six bytes of
 * it as extradata, and then expects every packet to arrive with a short prefix
 * that appears nowhere in the container:
 *
 * ```
 * [0..3]  blocks in this frame                      (little-endian u32)
 * [4..7]  bytes to skip before the bitstream starts (little-endian u32, 0..3)
 * [8..]   the frame itself
 * ```
 *
 * The skip is there because APE is addressed on a four-byte grid. The decoder
 * byte-swaps whole 32-bit words before it reads a single bit, so a frame has to
 * be handed over from its word boundary with the offset of its real first byte
 * stated separately. Getting that wrong does not fail loudly — it decodes
 * noise, which is why the alignment arithmetic in [buildFrameTable] is spelled
 * out rather than folded together.
 */
@UnstableApi
class ApeExtractor : Extractor {

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var headerParsed = false

    private var sampleRate = 0
    private var channelCount = 0
    private var bitsPerSample = 0
    private var blocksPerFrame = 0
    private var finalFrameBlocks = 0

    /** Where each frame begins, already rewound to its word boundary. */
    private var framePositions = LongArray(0)

    /** How many bytes to hand over for each frame, rounded up to a word. */
    private var frameSizes = IntArray(0)

    /** How far into each frame the bitstream actually starts, 0 to 3. */
    private var frameSkips = IntArray(0)

    private var currentFrame = 0

    /** Where the reader is in the file, so an overlap can be recognised. */
    private var streamPosition = 0L

    /**
     * The tail of the bytes last read.
     *
     * Rewinding a frame to its word boundary can put its first byte *before*
     * the end of the frame ahead of it, so frames are not a partition of the
     * file — consecutive ones overlap by a few bytes. Keeping the tail serves
     * that overlap from memory instead of seeking backwards once per frame.
     */
    private val carry = ByteArray(CARRY_BYTES)
    private var carryBytes = 0

    private val frame = ParsableByteArray(INITIAL_FRAME_CAPACITY)

    override fun sniff(input: ExtractorInput): Boolean {
        val start = leadingTagBytes(input)
        val peeked = ByteArray(APE_MAGIC_BYTES + 2)

        input.resetPeekPosition()
        if (start > 0 && !input.advancePeekPosition(start, /* allowEndOfInput= */ true)) return false
        val read = input.peekFully(peeked, 0, peeked.size, /* allowEndOfInput= */ true)
        input.resetPeekPosition()
        if (!read) return false

        if (!isApeMagic(peeked)) return false
        return peeked.u16le(APE_MAGIC_BYTES) in APE_MIN_VERSION..APE_MAX_VERSION
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerParsed) {
            if (!parseHeader(input)) return Extractor.RESULT_END_OF_INPUT
            headerParsed = true
            return Extractor.RESULT_CONTINUE
        }
        return readFrame(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        carryBytes = 0
        streamPosition = position
        if (!headerParsed) return
        currentFrame = frameIndexForPosition(position)
    }

    override fun release() = Unit

    // --- The header ----------------------------------------------------------

    /**
     * Reads the descriptor, the header and the seek table, and from them builds
     * the frame table the rest of the class runs on.
     *
     * Read rather than peeked: unlike a WavPack block, none of this is audio,
     * and no part of it is ever handed to the decoder.
     */
    private fun parseHeader(input: ExtractorInput): Boolean {
        val junkBytes = leadingTagBytes(input).toLong()
        if (junkBytes > 0 && !input.skipFully(junkBytes.toInt(), /* allowEndOfInput= */ true)) {
            return false
        }

        val magic = ByteArray(APE_MAGIC_BYTES + 2)
        if (!input.readFully(magic, 0, magic.size, /* allowEndOfInput= */ true)) return false
        if (!isApeMagic(magic)) {
            MusicLog.i(TAG, "no 'MAC ' at the start of the file")
            return false
        }

        val fileVersion = magic.u16le(APE_MAGIC_BYTES)
        if (fileVersion < APE_MIN_VERSION || fileVersion > APE_MAX_VERSION) {
            MusicLog.i(TAG, "unsupported Monkey's Audio version $fileVersion")
            return false
        }

        val header = if (fileVersion >= APE_DESCRIPTOR_VERSION) {
            readModernHeader(input, junkBytes)
        } else {
            readLegacyHeader(input, fileVersion, junkBytes)
        } ?: return false

        sampleRate = header.sampleRate
        channelCount = header.channelCount
        bitsPerSample = header.bitsPerSample
        blocksPerFrame = header.blocksPerFrame
        finalFrameBlocks = header.finalFrameBlocks

        if (sampleRate <= 0 || channelCount <= 0 || blocksPerFrame <= 0) {
            MusicLog.i(TAG, "header declares no usable sample rate, channels or frame size")
            return false
        }
        if (header.totalFrames <= 0 || header.totalFrames > MAX_FRAMES) {
            MusicLog.i(TAG, "implausible frame count: ${header.totalFrames}")
            return false
        }

        val seekTable = readSeekTable(input, header) ?: return false
        buildFrameTable(header, seekTable, input.length)
        if (framePositions.isEmpty()) return false

        // Everything ahead of the first frame has now been consumed, and the
        // frame table is stated in absolute file positions.
        streamPosition = input.position
        carryBytes = 0
        currentFrame = 0

        val totalBlocks = (framePositions.size - 1).toLong() * blocksPerFrame +
            finalFrameBlocks.coerceAtLeast(0)
        val durationUs = totalBlocks * C.MICROS_PER_SECOND / sampleRate

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType(ChoirMimeTypes.AUDIO_APE)
                .setChannelCount(channelCount)
                .setSampleRate(sampleRate)
                .setInitializationData(
                    listOf(
                        apeExtraData(fileVersion, header.compressionLevel, header.formatFlags),
                        // The decoder refuses a bit depth it was not told, and
                        // nothing on Media3's Format carries one for a coded
                        // stream. See ChoirCodecContext.
                        ChoirCodecContext.encode(bitsPerCodedSample = bitsPerSample),
                    ),
                )
                .build(),
        )
        extractorOutput?.seekMap(FrameSeekMap(durationUs))

        MusicLog.d(
            TAG,
            "APE $fileVersion: $sampleRate Hz, $channelCount ch, $bitsPerSample bit, " +
                "${framePositions.size} frames, ${durationUs}us",
        )
        return true
    }

    /**
     * The layout used from 3.98 onwards: a descriptor stating the size of
     * everything behind it, then the header proper.
     *
     * The descriptor's own length is one of the fields it states, which is what
     * lets a later version append to it without breaking this reader — the
     * surplus is stepped over rather than misread as the header.
     */
    private fun readModernHeader(input: ExtractorInput, junkBytes: Long): ApeHeader? {
        val descriptor = ByteArray(APE_DESCRIPTOR_BYTES - APE_MAGIC_BYTES - 2)
        if (!input.readFully(descriptor, 0, descriptor.size, /* allowEndOfInput= */ true)) {
            return null
        }

        // Two bytes of padding sit after the version; the lengths follow it.
        val descriptorBytes = descriptor.u32le(2).toInt()
        val headerBytes = descriptor.u32le(6).toInt()
        val seekTableBytes = descriptor.u32le(10).toInt()
        val wavHeaderBytes = descriptor.u32le(14).toInt()
        val frameDataBytes = descriptor.u32le(18) or (descriptor.u32le(22) shl 32)
        val terminatingBytes = descriptor.u32le(26)

        val effectiveDescriptor = maxOf(descriptorBytes, APE_DESCRIPTOR_BYTES)
        if (effectiveDescriptor > APE_DESCRIPTOR_BYTES) {
            val surplus = effectiveDescriptor - APE_DESCRIPTOR_BYTES
            if (!input.skipFully(surplus, /* allowEndOfInput= */ true)) return null
        }

        val header = ByteArray(APE_HEADER_BYTES)
        if (!input.readFully(header, 0, APE_HEADER_BYTES, /* allowEndOfInput= */ true)) return null

        val effectiveHeader = maxOf(headerBytes, APE_HEADER_BYTES)
        if (effectiveHeader > APE_HEADER_BYTES) {
            if (!input.skipFully(effectiveHeader - APE_HEADER_BYTES, /* allowEndOfInput= */ true)) {
                return null
            }
        }

        return ApeHeader(
            compressionLevel = header.u16le(0),
            formatFlags = header.u16le(2),
            blocksPerFrame = header.u32le(4).toInt(),
            finalFrameBlocks = header.u32le(8).toInt(),
            totalFrames = header.u32le(12).toInt(),
            bitsPerSample = header.u16le(16),
            channelCount = header.u16le(18),
            sampleRate = header.u32le(20).toInt(),
            seekTableBytes = seekTableBytes,
            wavHeaderBytes = wavHeaderBytes,
            terminatingBytes = terminatingBytes,
            frameDataBytes = frameDataBytes,
            junkBytes = junkBytes,
            firstFramePosition = junkBytes + effectiveDescriptor + effectiveHeader +
                seekTableBytes + wavHeaderBytes,
        )
    }

    /**
     * The layout used before 3.98, where the fields sit directly behind the
     * magic and two of them are optional.
     *
     * Frame size is not stored here at all: it was a constant of the encoder,
     * raised twice, so it has to be inferred from the version — and at one
     * awkward version, from the compression level as well.
     */
    private fun readLegacyHeader(
        input: ExtractorInput,
        fileVersion: Int,
        junkBytes: Long,
    ): ApeHeader? {
        val header = ByteArray(APE_LEGACY_HEADER_BYTES - APE_MAGIC_BYTES - 2)
        if (!input.readFully(header, 0, header.size, /* allowEndOfInput= */ true)) return null

        val compressionLevel = header.u16le(0)
        val formatFlags = header.u16le(2)
        val channels = header.u16le(4)
        val rate = header.u32le(6).toInt()
        val wavHeaderBytes = header.u32le(10).toInt()
        val terminatingBytes = header.u32le(14)
        val totalFrames = header.u32le(18).toInt()
        val finalBlocks = header.u32le(22).toInt()

        var headerBytes = APE_LEGACY_HEADER_BYTES
        val optional = ByteArray(4)

        if (formatFlags and MAC_FLAG_HAS_PEAK_LEVEL != 0) {
            if (!input.readFully(optional, 0, 4, /* allowEndOfInput= */ true)) return null
            headerBytes += 4
        }

        // Where the count is absent it is one entry per frame, which is what it
        // has always been in practice; the field exists for the case it is not.
        var seekTableBytes = totalFrames * Int.SIZE_BYTES
        if (formatFlags and MAC_FLAG_HAS_SEEK_ELEMENTS != 0) {
            if (!input.readFully(optional, 0, 4, /* allowEndOfInput= */ true)) return null
            headerBytes += 4
            seekTableBytes = optional.u32le(0).toInt() * Int.SIZE_BYTES
        }

        val bits = when {
            formatFlags and MAC_FLAG_8_BIT != 0 -> 8
            formatFlags and MAC_FLAG_24_BIT != 0 -> 24
            else -> 16
        }

        // A file that says it builds the WAVE header on decompression did not
        // store one, so there is nothing there to step over.
        val storedWavHeader =
            if (formatFlags and MAC_FLAG_CREATE_WAV_HEADER != 0) 0 else wavHeaderBytes

        return ApeHeader(
            compressionLevel = compressionLevel,
            formatFlags = formatFlags,
            blocksPerFrame = legacyBlocksPerFrame(fileVersion, compressionLevel),
            finalFrameBlocks = finalBlocks,
            totalFrames = totalFrames,
            bitsPerSample = bits,
            channelCount = channels,
            sampleRate = rate,
            seekTableBytes = seekTableBytes,
            wavHeaderBytes = storedWavHeader,
            terminatingBytes = terminatingBytes,
            frameDataBytes = 0L,
            junkBytes = junkBytes,
            firstFramePosition = junkBytes + headerBytes + seekTableBytes + storedWavHeader,
        )
    }

    /** Reads the seek table: one absolute file offset per frame. */
    private fun readSeekTable(input: ExtractorInput, header: ApeHeader): LongArray? {
        val entries = header.seekTableBytes / Int.SIZE_BYTES
        if (header.seekTableBytes <= 0 || entries <= 0 || header.seekTableBytes > MAX_SEEK_TABLE_BYTES) {
            MusicLog.i(TAG, "no usable seek table, so the frames cannot be located")
            return null
        }

        val bytes = ByteArray(header.seekTableBytes)
        if (!input.readFully(bytes, 0, bytes.size, /* allowEndOfInput= */ true)) return null

        val table = LongArray(minOf(entries, header.totalFrames))
        for (index in table.indices) table[index] = bytes.u32le(index * Int.SIZE_BYTES)

        // The stored WAVE header, where there is one, sits between the table
        // and the audio.
        if (header.wavHeaderBytes > 0 &&
            !input.skipFully(header.wavHeaderBytes, /* allowEndOfInput= */ true)
        ) {
            return null
        }
        return table
    }

    /**
     * Turns the seek table into the positions, sizes and skips frames are read
     * with.
     *
     * A frame's size is stored nowhere: it is the distance to the frame after
     * it, and for the last one, the distance to the end of the audio. Both are
     * then widened outwards to the four-byte grid the decoder reads on, which
     * is where the skip comes from — the frame starts a little earlier than the
     * table says, and the decoder is told how much of that head to ignore.
     */
    private fun buildFrameTable(header: ApeHeader, seekTable: LongArray, inputLength: Long) {
        val count = minOf(header.totalFrames, seekTable.size)
        if (count <= 0) return

        val positions = LongArray(count)
        val sizes = IntArray(count)
        val skips = IntArray(count)

        // The table's first entry and the computed start of the audio ought to
        // agree; where they do not, the computed one wins, because everything
        // ahead of it has just been read past and is known to be behind us.
        positions[0] = header.firstFramePosition
        for (index in 1 until count) positions[index] = seekTable[index] + header.junkBytes

        // A table whose offsets run backwards describes nothing readable, and
        // would be read as enormous frames at arbitrary places.
        var usable = count
        for (index in 1 until count) {
            if (positions[index] < positions[index - 1]) {
                MusicLog.i(TAG, "seek table stops ascending at frame $index; truncating there")
                usable = index
                break
            }
        }

        val audioEnd = frameDataEnd(header, inputLength, positions[usable - 1])

        for (index in 0 until usable) {
            val start = positions[index]
            val end = if (index + 1 < usable) positions[index + 1] else audioEnd

            // How far this frame's first byte sits past a word boundary,
            // measured from the first frame rather than from the file, because
            // that is the grid the encoder wrote on.
            val skip = ((start - positions[0]) and 3L).toInt()
            val size = (end - start + skip).coerceAtLeast(0L)

            positions[index] = start - skip
            skips[index] = skip
            sizes[index] = ((size + 3) and 3L.inv()).coerceAtMost(MAX_FRAME_BYTES.toLong()).toInt()
        }

        framePositions = positions.copyOf(usable)
        frameSizes = sizes.copyOf(usable)
        frameSkips = skips.copyOf(usable)
    }

    /**
     * Where the audio stops.
     *
     * The descriptor states the length of the frame data outright, which is the
     * answer whenever it is there and plausible. The older layout does not,
     * leaving the end of the file less whatever trailing tag it declares — and
     * where even the length is unknown, a bound rather than a guess.
     */
    private fun frameDataEnd(header: ApeHeader, inputLength: Long, lastFrame: Long): Long {
        val declared = header.firstFramePosition + header.frameDataBytes
        val lengthKnown = inputLength != C.LENGTH_UNSET.toLong()

        if (header.frameDataBytes > 0 && declared > lastFrame &&
            (!lengthKnown || declared <= inputLength)
        ) {
            return declared
        }
        if (lengthKnown) {
            return (inputLength - header.terminatingBytes).coerceAtLeast(lastFrame)
        }
        return lastFrame + MAX_FRAME_BYTES
    }

    // --- Frames --------------------------------------------------------------

    /**
     * Reads one frame and hands it over with the prefix the decoder expects.
     *
     * Frames are read in order and all but touch, so the ordinary path is a
     * plain sequential read; the few bytes of overlap that word alignment
     * leaves come out of [carry], and only a real discontinuity — which is to
     * say, a seek — costs a seek of the input.
     */
    private fun readFrame(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val output = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        if (currentFrame >= framePositions.size) return Extractor.RESULT_END_OF_INPUT

        val start = framePositions[currentFrame]
        val size = frameSizes[currentFrame]
        if (size <= 0) return Extractor.RESULT_END_OF_INPUT

        val behind = streamPosition - start
        if (behind < 0 || behind > carryBytes) {
            // Neither where the frame starts nor close enough past it to be
            // served from memory, so the input has to move.
            if (input.position != start) {
                seekPosition.position = start
                return Extractor.RESULT_SEEK
            }
            streamPosition = start
            carryBytes = 0
        }

        val fromCarry = (streamPosition - start).toInt()

        frame.reset(APE_PACKET_PREFIX_BYTES + size)
        val data = frame.data
        data.putU32le(0, blocksInFrame(currentFrame).toLong())
        data.putU32le(4, frameSkips[currentFrame].toLong())

        if (fromCarry > 0) {
            System.arraycopy(carry, carryBytes - fromCarry, data, APE_PACKET_PREFIX_BYTES, fromCarry)
        }

        val remaining = size - fromCarry
        var got = 0
        if (remaining > 0) {
            got = readAtMost(input, data, APE_PACKET_PREFIX_BYTES + fromCarry, remaining)
            if (got <= 0 && fromCarry == 0) return Extractor.RESULT_END_OF_INPUT
            streamPosition += got
        }

        val frameBytes = fromCarry + got
        rememberTail(data, APE_PACKET_PREFIX_BYTES, frameBytes)

        frame.setLimit(APE_PACKET_PREFIX_BYTES + frameBytes)
        output.sampleData(frame, frame.limit())
        output.sampleMetadata(
            /* timeUs= */ frameStartBlock(currentFrame) * C.MICROS_PER_SECOND / sampleRate,
            // Every APE frame decodes from scratch, so all of them are seek
            // points and none of them is a difference from another.
            /* flags= */ C.BUFFER_FLAG_KEY_FRAME,
            /* size= */ frame.limit(),
            /* offset= */ 0,
            /* cryptoData= */ null,
        )

        // A short read is the file ending early — the frame just handed over is
        // all there is, whatever the table promised after it.
        currentFrame = if (got < remaining) framePositions.size else currentFrame + 1
        return Extractor.RESULT_CONTINUE
    }

    /** Reads up to [length] bytes, stopping short at the end of the file. */
    private fun readAtMost(
        input: ExtractorInput,
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var read = 0
        while (read < length) {
            val count = input.read(target, offset + read, length - read)
            if (count == C.RESULT_END_OF_INPUT) break
            read += count
        }
        return read
    }

    /** Keeps the last few bytes of a frame, for the next one to start inside. */
    private fun rememberTail(data: ByteArray, offset: Int, length: Int) {
        carryBytes = minOf(length, CARRY_BYTES)
        System.arraycopy(data, offset + length - carryBytes, carry, 0, carryBytes)
    }

    /** Every frame holds the same count but the last, which holds the remainder. */
    private fun blocksInFrame(index: Int): Int =
        if (index == framePositions.size - 1 && finalFrameBlocks > 0) {
            finalFrameBlocks
        } else {
            blocksPerFrame
        }

    private fun frameStartBlock(index: Int): Long = index.toLong() * blocksPerFrame

    private fun frameIndexForPosition(position: Long): Int {
        if (framePositions.isEmpty()) return 0
        val index = framePositions.binarySearch(position)
        return if (index >= 0) index else (-index - 2).coerceIn(0, framePositions.size - 1)
    }

    /**
     * Seeking straight to a frame, because the file says where every one of
     * them is.
     *
     * There is no estimate to correct and nothing to resynchronise. The block a
     * frame starts at is its index times the frame size, so the time handed
     * back is the exact time of the frame the reader is about to be placed on,
     * and the player is told the truth before it arrives rather than after.
     */
    private inner class FrameSeekMap(private val durationUs: Long) : SeekMap {

        override fun isSeekable(): Boolean = framePositions.size > 1

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!isSeekable) return SeekMap.SeekPoints(SeekPoint.START)

            val clamped = timeUs.coerceIn(0L, durationUs)
            val block = clamped * sampleRate / C.MICROS_PER_SECOND
            val index = (block / blocksPerFrame).toInt().coerceIn(0, framePositions.size - 1)

            return SeekMap.SeekPoints(
                SeekPoint(
                    frameStartBlock(index) * C.MICROS_PER_SECOND / sampleRate,
                    framePositions[index],
                ),
            )
        }
    }

    private companion object {
        const val TAG = "ApeExtractor"

        const val INITIAL_FRAME_CAPACITY = 64 * 1024

        /**
         * Enough to cover any overlap between one frame and the next.
         *
         * Word alignment can move a frame's start back by three bytes and round
         * its end up by three more, so six is the worst case; eight keeps the
         * buffer itself on the same grid.
         */
        const val CARRY_BYTES = 8

        /** A seek table larger than this is not a seek table. */
        const val MAX_SEEK_TABLE_BYTES = MAX_FRAMES * Int.SIZE_BYTES
    }
}

// --- The header --------------------------------------------------------------
//
// Internal rather than private so the layouts can be tested against handmade
// headers. A misread field here does not fail loudly: it produces a track of
// the wrong length, or frames read from the wrong offsets, which sounds like a
// broken file rather than a broken reader.

/** What both header layouts come to, once their differences are resolved. */
internal data class ApeHeader(
    val compressionLevel: Int,
    val formatFlags: Int,
    val blocksPerFrame: Int,
    val finalFrameBlocks: Int,
    val totalFrames: Int,
    val bitsPerSample: Int,
    val channelCount: Int,
    val sampleRate: Int,
    val seekTableBytes: Int,
    val wavHeaderBytes: Int,
    val terminatingBytes: Long,
    val frameDataBytes: Long,
    /** ID3v2 ahead of the audio, which shifts every offset the table states. */
    val junkBytes: Long,
    val firstFramePosition: Long,
)

internal const val APE_MAGIC_BYTES = 4

/** Descriptor, header and pre-3.98 header, at the sizes their versions fixed. */
internal const val APE_DESCRIPTOR_BYTES = 52
internal const val APE_HEADER_BYTES = 24
internal const val APE_LEGACY_HEADER_BYTES = 32

/** The block count and skip FFmpeg's decoder reads ahead of the bitstream. */
internal const val APE_PACKET_PREFIX_BYTES = 8

/** 3.98 is where the descriptor arrived and the header moved behind it. */
internal const val APE_DESCRIPTOR_VERSION = 3980

/** Versions 3.80 to 3.99, which is every Monkey's Audio file in circulation. */
internal const val APE_MIN_VERSION = 3800
internal const val APE_MAX_VERSION = 3990

/** Format flags, all but two of them left over from versions long past. */
internal const val MAC_FLAG_8_BIT = 1
internal const val MAC_FLAG_HAS_PEAK_LEVEL = 4
internal const val MAC_FLAG_24_BIT = 8
internal const val MAC_FLAG_HAS_SEEK_ELEMENTS = 16
internal const val MAC_FLAG_CREATE_WAV_HEADER = 32

/** A ceiling on one frame, well above the ~1.2 MB a 4.7-second frame reaches. */
internal const val MAX_FRAME_BYTES = 16 * 1024 * 1024

/** A ceiling on the frame table, past which the header is not a header. */
internal const val MAX_FRAMES = 1 shl 22

/** True when these bytes begin a Monkey's Audio file. */
internal fun isApeMagic(bytes: ByteArray): Boolean =
    bytes.size >= APE_MAGIC_BYTES &&
        bytes[0] == 'M'.code.toByte() && bytes[1] == 'A'.code.toByte() &&
        bytes[2] == 'C'.code.toByte() && bytes[3] == ' '.code.toByte()

/**
 * The six bytes FFmpeg's `ape` decoder expects as extradata.
 *
 * It reads the file version to know which of a decade of bitstream revisions it
 * is looking at, the compression level to size its filters, and the format
 * flags for the handful of behaviours those still select. Nothing else from the
 * header reaches it by this route.
 */
internal fun apeExtraData(fileVersion: Int, compressionLevel: Int, formatFlags: Int): ByteArray {
    val bytes = ByteArray(6)
    bytes.putU16le(0, fileVersion)
    bytes.putU16le(2, compressionLevel)
    bytes.putU16le(4, formatFlags)
    return bytes
}

/**
 * The frame size the encoder used, which older files do not record.
 *
 * It was raised at 3.80 for the highest compression level only, then for
 * everything at 3.90, then again at 3.95. A file from before 3.98 states none
 * of this, so the version has to stand in for the field.
 */
internal fun legacyBlocksPerFrame(fileVersion: Int, compressionLevel: Int): Int = when {
    fileVersion >= 3950 -> 73_728 * 4
    fileVersion >= 3900 || compressionLevel >= 4000 -> 73_728
    else -> 9_216
}

internal fun ByteArray.putU16le(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

internal fun ByteArray.putU32le(offset: Int, value: Long) {
    for (index in 0 until 4) {
        this[offset + index] = ((value shr (8 * index)) and 0xFF).toByte()
    }
}
