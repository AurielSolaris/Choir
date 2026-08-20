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
 * Reads ASF, which is how every `.wma` file is packaged.
 *
 * The other two containers Choir opens itself are audio formats that happen to
 * need a wrapper. ASF is the opposite: a general-purpose streaming container
 * from an era that expected files to be sent down a wire and to survive losing
 * pieces of themselves. Almost everything awkward about it follows from that.
 *
 * The audio is not stored as frames one after another. It is cut into
 * fixed-size *packets* — the unit that would have been a network datagram —
 * and each packet carries some number of *payloads*, each a piece of a *media
 * object*, which for audio is one compressed block. A block larger than the
 * space left in a packet is split across packets and has to be sewn back
 * together here, because the decoder is owed whole blocks and nothing less.
 *
 * So the shape of this reader is: parse the header once to learn the packet
 * size and what the audio stream is, then walk packets, and from each packet's
 * payloads reassemble the blocks that the decoder is then handed.
 *
 * ## What the decoder is handed
 *
 * FFmpeg's Windows Media decoders will not open without three numbers that
 * Media3's `Format` has no field for — the block alignment, the bit depth and
 * the bitrate — which is what [ChoirCodecContext] exists to carry. The codec
 * extradata proper is the tail of the `WAVEFORMATEX` in the Stream Properties
 * object, and travels the ordinary way.
 *
 * ## What is deliberately not read
 *
 * Encrypted streams are declined rather than half-read. The Simple Index
 * Object is ignored: it indexes video key frames, and for an audio-only file it
 * is either absent or useless, so seeking here works the way [WavPackExtractor]
 * does — land on a real packet boundary, and let the timestamp that comes back
 * off that packet be the correction.
 */
@UnstableApi
class AsfExtractor : Extractor {

    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var headerParsed = false

    /** The stream number of the audio, and the only one payloads are kept for. */
    private var audioStreamNumber = -1

    private var packetSize = 0
    private var packetCount = 0L
    private var dataStartPosition = 0L
    private var durationUs = C.TIME_UNSET

    /** Preroll, which every timestamp in the file is stated ahead of. */
    private var prerollMs = 0L

    private val packet = ParsableByteArray(0)

    // --- The block being reassembled -----------------------------------------

    private var objectNumber = -1
    private var objectSize = 0
    private var objectTimeUs = C.TIME_UNSET
    private var objectBytes = 0
    private var objectBuffer = ParsableByteArray(0)

    override fun sniff(input: ExtractorInput): Boolean {
        val guid = ByteArray(GUID_BYTES)
        input.resetPeekPosition()
        val read = input.peekFully(guid, 0, GUID_BYTES, /* allowEndOfInput= */ true)
        input.resetPeekPosition()
        return read && guid.contentEquals(ASF_HEADER_OBJECT)
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
        return readPacket(input)
    }

    override fun seek(position: Long, timeUs: Long) {
        // A half-assembled block belongs to wherever the reader used to be.
        discardPartialObject()
    }

    override fun release() = Unit

    // --- The header ----------------------------------------------------------

    /**
     * Reads the Header Object and the start of the Data Object that follows it.
     *
     * The Header Object states its own total size, so it is read whole and
     * walked in memory rather than streamed: its children are a handful of
     * kilobytes at most, several of them are ignored entirely, and the two that
     * matter are easier to reason about when they can be looked at in any
     * order.
     */
    private fun parseHeader(input: ExtractorInput): Boolean {
        val prologue = ByteArray(ASF_HEADER_PROLOGUE_BYTES)
        if (!input.readFully(prologue, 0, prologue.size, /* allowEndOfInput= */ true)) return false

        if (!prologue.copyOf(GUID_BYTES).contentEquals(ASF_HEADER_OBJECT)) {
            MusicLog.i(TAG, "file does not begin with an ASF header object")
            return false
        }

        val headerSize = prologue.u64le(GUID_BYTES)
        if (headerSize < ASF_HEADER_PROLOGUE_BYTES || headerSize > MAX_HEADER_BYTES) {
            MusicLog.i(TAG, "implausible header object size: $headerSize")
            return false
        }

        val body = ByteArray((headerSize - ASF_HEADER_PROLOGUE_BYTES).toInt())
        if (!input.readFully(body, 0, body.size, /* allowEndOfInput= */ true)) return false

        if (!walkHeaderObjects(body)) return false
        if (audioStreamNumber < 0) {
            MusicLog.i(TAG, "no playable audio stream in the header")
            return false
        }
        return openDataObject(input)
    }

    /**
     * Walks the header's children, keeping the two that describe the file.
     *
     * Everything else — the codec list, the content description, the header
     * extension and its own nested children — is stepped over by the size each
     * object states. That is the whole point of the format's uniform
     * GUID-and-length framing, and it is what lets a reader this small survive
     * files written by encoders it has never seen.
     */
    private fun walkHeaderObjects(body: ByteArray): Boolean {
        var offset = 0
        while (offset + GUID_BYTES + 8 <= body.size) {
            val guid = body.copyOfRange(offset, offset + GUID_BYTES)
            val size = body.u64le(offset + GUID_BYTES)

            if (size < GUID_BYTES + 8 || offset + size > body.size) {
                MusicLog.i(TAG, "header object at $offset states a size that does not fit")
                return false
            }

            val dataStart = offset + GUID_BYTES + 8
            val dataSize = (size - GUID_BYTES - 8).toInt()

            when {
                guid.contentEquals(ASF_FILE_PROPERTIES) -> readFileProperties(body, dataStart, dataSize)
                guid.contentEquals(ASF_STREAM_PROPERTIES) ->
                    readStreamProperties(body, dataStart, dataSize)
            }
            offset += size.toInt()
        }
        return true
    }

    /** Duration, preroll and the packet size every packet is read at. */
    private fun readFileProperties(body: ByteArray, offset: Int, size: Int) {
        if (size < FILE_PROPERTIES_BYTES) return

        packetCount = body.u64le(offset + 32)
        val playDuration100Ns = body.u64le(offset + 40)
        prerollMs = body.u64le(offset + 56)
        val minPacketSize = body.u32le(offset + 68).toInt()
        val maxPacketSize = body.u32le(offset + 72).toInt()

        // Every ASF file that is not a live broadcast uses one packet size, and
        // the two fields agree. Where they do not, the reader has no way to
        // know where a packet ends, so the file is left unplayable rather than
        // read at the wrong stride.
        packetSize = if (minPacketSize == maxPacketSize) minPacketSize else 0

        // Play duration counts the preroll, which is not audio anyone hears.
        val playUs = playDuration100Ns / 10
        val prerollUs = prerollMs * 1000
        durationUs = if (playUs > prerollUs) playUs - prerollUs else C.TIME_UNSET
    }

    /**
     * The audio stream's `WAVEFORMATEX`, and the [Format] built from it.
     *
     * Only the first audio stream is kept. A `.wma` with two of them is a
     * multi-bitrate file meant for a server to choose between, and picking the
     * first is what every player does.
     */
    private fun readStreamProperties(body: ByteArray, offset: Int, size: Int) {
        if (size < STREAM_PROPERTIES_BYTES || audioStreamNumber >= 0) return
        if (!body.copyOfRange(offset, offset + GUID_BYTES).contentEquals(ASF_AUDIO_MEDIA)) return

        val typeDataLength = body.u32le(offset + 40).toInt()
        val flags = body.u16le(offset + 48)

        if (flags and STREAM_FLAG_ENCRYPTED != 0) {
            MusicLog.i(TAG, "audio stream is encrypted; declining it rather than decoding noise")
            return
        }
        val streamNumber = flags and STREAM_NUMBER_MASK
        if (streamNumber == 0) return

        val waveFormat = offset + STREAM_PROPERTIES_BYTES
        if (typeDataLength < WAVE_FORMAT_EX_BYTES || waveFormat + typeDataLength > body.size) return

        val formatTag = body.u16le(waveFormat)
        val channels = body.u16le(waveFormat + 2)
        val sampleRate = body.u32le(waveFormat + 4).toInt()
        val averageBytesPerSecond = body.u32le(waveFormat + 8).toInt()
        val blockAlign = body.u16le(waveFormat + 12)
        val bitsPerSample = body.u16le(waveFormat + 14)
        val extraSize = body.u16le(waveFormat + 16)

        val mimeType = wmaMimeType(formatTag)
        if (mimeType == null) {
            MusicLog.i(TAG, "unsupported WAVEFORMATEX tag 0x${formatTag.toString(16)}")
            return
        }
        if (sampleRate <= 0 || channels <= 0 || blockAlign <= 0) {
            MusicLog.i(TAG, "audio stream declares no usable sample rate, channels or block size")
            return
        }

        val extraStart = waveFormat + WAVE_FORMAT_EX_BYTES
        val extraData = if (extraSize > 0 && extraStart + extraSize <= body.size) {
            body.copyOfRange(extraStart, extraStart + extraSize)
        } else {
            ByteArray(0)
        }

        audioStreamNumber = streamNumber

        trackOutput?.format(
            Format.Builder()
                .setSampleMimeType(mimeType)
                .setChannelCount(channels)
                .setSampleRate(sampleRate)
                .setAverageBitrate(averageBytesPerSecond * 8)
                .setInitializationData(
                    listOf(
                        extraData,
                        // Without these three the decoder declines to open at
                        // all. See ChoirCodecContext for why they cannot ride
                        // on the Format itself.
                        ChoirCodecContext.encode(
                            blockAlign = blockAlign,
                            bitsPerCodedSample = bitsPerSample,
                            bitRate = averageBytesPerSecond * 8,
                        ),
                    ),
                )
                .build(),
        )

        MusicLog.d(
            TAG,
            "ASF stream $streamNumber: tag 0x${formatTag.toString(16)}, $sampleRate Hz, " +
                "$channels ch, block $blockAlign, ${extraSize}B extradata",
        )
    }

    /** Steps to the first packet and publishes how the file can be seeked. */
    private fun openDataObject(input: ExtractorInput): Boolean {
        val prologue = ByteArray(ASF_DATA_PROLOGUE_BYTES)
        if (!input.readFully(prologue, 0, prologue.size, /* allowEndOfInput= */ true)) return false

        if (!prologue.copyOf(GUID_BYTES).contentEquals(ASF_DATA_OBJECT)) {
            MusicLog.i(TAG, "no data object behind the header")
            return false
        }
        if (packetSize <= 0 || packetSize > MAX_PACKET_BYTES) {
            MusicLog.i(TAG, "file states no single usable packet size")
            return false
        }

        dataStartPosition = input.position
        packet.reset(packetSize)

        extractorOutput?.seekMap(PacketSeekMap(durationUs, dataStartPosition, packetSize, input.length))
        return true
    }

    // --- Packets -------------------------------------------------------------

    /**
     * Reads one packet and hands on whatever audio it completes.
     *
     * Packets are a fixed size, so this never has to search for a boundary —
     * which is the one kindness the format offers, and the reason a seek can
     * land exactly on a packet without any resynchronisation.
     */
    private fun readPacket(input: ExtractorInput): Int {
        val output = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        packet.reset(packetSize)
        if (!input.readFully(packet.data, 0, packetSize, /* allowEndOfInput= */ true)) {
            discardPartialObject()
            return Extractor.RESULT_END_OF_INPUT
        }

        val payloads = parseAsfPacket(packet.data, packetSize)
        if (payloads == null) {
            // One unreadable packet is not the end of the file: they are a
            // fixed size, so the next one starts at a known place regardless.
            MusicLog.d(TAG, "unreadable packet; skipping it")
            discardPartialObject()
            return Extractor.RESULT_CONTINUE
        }

        for (payload in payloads) {
            if (payload.streamNumber != audioStreamNumber) continue
            acceptPayload(output, payload)
        }
        return Extractor.RESULT_CONTINUE
    }

    /**
     * Adds one payload to the block being assembled, and hands the block on
     * once it is whole.
     *
     * A payload states which media object it belongs to and how far into it the
     * bytes go, so a fragment arriving out of order, or after its opening
     * fragment was skipped past by a seek, can be recognised and dropped rather
     * than stitched into the wrong place.
     */
    private fun acceptPayload(output: TrackOutput, payload: AsfPayload) {
        if (payload.offsetIntoMediaObject == 0) {
            // A new block. Whatever was half-assembled will now never finish.
            discardPartialObject()

            if (payload.mediaObjectSize <= 0 || payload.mediaObjectSize > MAX_MEDIA_OBJECT_BYTES) {
                return
            }
            objectNumber = payload.mediaObjectNumber
            objectSize = payload.mediaObjectSize
            objectTimeUs = presentationTimeUs(payload.presentationTimeMs)
            objectBytes = 0
            if (objectBuffer.capacity() < objectSize) objectBuffer = ParsableByteArray(objectSize)
        } else if (payload.mediaObjectNumber != objectNumber || payload.offsetIntoMediaObject != objectBytes) {
            // A continuation of something this reader never saw the start of,
            // which is the ordinary state of affairs for one packet after a
            // seek.
            return
        }

        val length = minOf(payload.dataLength, objectSize - objectBytes)
        if (length <= 0) return

        System.arraycopy(packet.data, payload.dataOffset, objectBuffer.data, objectBytes, length)
        objectBytes += length

        if (objectBytes < objectSize) return

        objectBuffer.setPosition(0)
        objectBuffer.setLimit(objectSize)
        output.sampleData(objectBuffer, objectSize)
        output.sampleMetadata(
            /* timeUs= */ objectTimeUs,
            // Windows Media blocks decode from scratch, and a file whose
            // payloads leave the key-frame bit clear would otherwise be
            // unseekable for no reason.
            /* flags= */ C.BUFFER_FLAG_KEY_FRAME,
            /* size= */ objectSize,
            /* offset= */ 0,
            /* cryptoData= */ null,
        )
        resetPartialObject()
    }

    private fun discardPartialObject() {
        if (objectBytes > 0) {
            MusicLog.d(TAG, "dropping $objectBytes bytes of an unfinished block")
        }
        resetPartialObject()
    }

    private fun resetPartialObject() {
        objectNumber = -1
        objectSize = 0
        objectBytes = 0
        objectTimeUs = C.TIME_UNSET
    }

    /** Presentation times are stated ahead of the audio by the preroll. */
    private fun presentationTimeUs(presentationTimeMs: Long): Long =
        ((presentationTimeMs - prerollMs) * 1000).coerceAtLeast(0L)

    /**
     * Seeking by proportion of the data object, landing on a packet boundary.
     *
     * ASF's index object indexes video key frames, so an audio-only file has
     * nothing to look a time up in. What it does have is a fixed packet size,
     * which means an estimate can be rounded to a real packet start rather than
     * to an arbitrary byte — and every packet states its own send time, so the
     * player learns the true position as soon as it reads one.
     */
    private class PacketSeekMap(
        private val durationUs: Long,
        private val dataStartPosition: Long,
        private val packetSize: Int,
        private val inputLength: Long,
    ) : SeekMap {

        private val packets: Long
            get() = if (inputLength == C.LENGTH_UNSET.toLong()) {
                0L
            } else {
                (inputLength - dataStartPosition) / packetSize
            }

        override fun isSeekable(): Boolean = durationUs != C.TIME_UNSET && packets > 0

        override fun getDurationUs(): Long = durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (!isSeekable) return SeekMap.SeekPoints(SeekPoint.START)

            val clamped = timeUs.coerceIn(0L, durationUs)
            val index = if (durationUs == 0L) 0L else packets * clamped / durationUs
            val packet = index.coerceIn(0L, packets - 1)

            return SeekMap.SeekPoints(
                SeekPoint(clamped, dataStartPosition + packet * packetSize),
            )
        }
    }

    private companion object {
        const val TAG = "AsfExtractor"

        /** The header is read whole, so it needs a ceiling that is not a file. */
        const val MAX_HEADER_BYTES = 8L * 1024 * 1024

        const val MAX_PACKET_BYTES = 1 shl 20

        /** No Windows Media block comes near this; a larger one is a misread. */
        const val MAX_MEDIA_OBJECT_BYTES = 1 shl 22
    }
}

// --- The container -----------------------------------------------------------
//
// Internal rather than private so packets can be tested as byte arrays. Packet
// parsing is where every bug in an ASF reader lives: the fields are optional,
// their widths are named by two-bit codes elsewhere in the same header, and
// reading one of them at the wrong width silently shifts everything after it.

internal const val GUID_BYTES = 16

/** GUID, then the object's own total size. */
internal const val ASF_HEADER_PROLOGUE_BYTES = GUID_BYTES + 8 + 4 + 1 + 1

/** GUID, size, file id, packet count, and two reserved bytes. */
internal const val ASF_DATA_PROLOGUE_BYTES = GUID_BYTES + 8 + GUID_BYTES + 8 + 2

internal const val FILE_PROPERTIES_BYTES = 80

/** Everything in a stream properties object ahead of the type-specific data. */
internal const val STREAM_PROPERTIES_BYTES = 54

internal const val WAVE_FORMAT_EX_BYTES = 18

internal const val STREAM_NUMBER_MASK = 0x7F
internal const val STREAM_FLAG_ENCRYPTED = 0x8000

/**
 * A GUID as it sits in the file.
 *
 * ASF writes the first three fields little-endian and the last eight bytes in
 * order, which is Microsoft's mixed-endian layout rather than anything anyone
 * would design. Parsing the canonical spelling into that arrangement here keeps
 * the constants below legible as the GUIDs they are, instead of as sixteen
 * bytes that have to be trusted.
 */
internal fun asfGuid(canonical: String): ByteArray {
    val hex = canonical.replace("-", "")
    require(hex.length == GUID_BYTES * 2) { "not a GUID: $canonical" }

    val raw = ByteArray(GUID_BYTES) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return byteArrayOf(
        raw[3], raw[2], raw[1], raw[0],
        raw[5], raw[4],
        raw[7], raw[6],
        raw[8], raw[9], raw[10], raw[11], raw[12], raw[13], raw[14], raw[15],
    )
}

internal val ASF_HEADER_OBJECT = asfGuid("75B22630-668E-11CF-A6D9-00AA0062CE6C")
internal val ASF_DATA_OBJECT = asfGuid("75B22636-668E-11CF-A6D9-00AA0062CE6C")
internal val ASF_FILE_PROPERTIES = asfGuid("8CABDCA1-A947-11CF-8EE4-00C00C205365")
internal val ASF_STREAM_PROPERTIES = asfGuid("B7DC0791-A9B7-11CF-8EE6-00C00C205365")
internal val ASF_AUDIO_MEDIA = asfGuid("F8699E40-5B4D-11CF-A8FD-00805F5C442B")

/**
 * The `WAVEFORMATEX` tags Choir's FFmpeg build has a decoder for.
 *
 * The tag is the only thing distinguishing five quite different codecs that all
 * arrive in the same container with the same file extension, which is why a
 * `.wma` cannot be identified by its name alone.
 */
internal fun wmaMimeType(formatTag: Int): String? = when (formatTag) {
    0x0160, 0x0161 -> ChoirMimeTypes.AUDIO_WMA
    0x0162 -> ChoirMimeTypes.AUDIO_WMA_PRO
    0x0163 -> ChoirMimeTypes.AUDIO_WMA_LOSSLESS
    0x000A, 0x000B -> ChoirMimeTypes.AUDIO_WMA_VOICE
    else -> null
}

/** One piece of one media object, as it was found inside a packet. */
internal data class AsfPayload(
    val streamNumber: Int,
    val mediaObjectNumber: Int,
    val offsetIntoMediaObject: Int,
    val mediaObjectSize: Int,
    val presentationTimeMs: Long,
    /** Where the bytes are in the packet, rather than a copy of them. */
    val dataOffset: Int,
    val dataLength: Int,
)

/**
 * Pulls the payloads out of one ASF packet, or null if it does not read as one.
 *
 * The packet header is a study in saving bytes at the cost of everyone's time:
 * six of its fields are present at one of four widths, or absent entirely,
 * according to two-bit codes packed into two flag bytes at the front. Nothing
 * is aligned, nothing is optional in a way that can be detected after the fact,
 * and reading a single field at the wrong width shifts every byte after it
 * without producing anything that looks wrong until it is decoded.
 *
 * Hence the shape of this function: read the flags, resolve every width up
 * front, then walk forward once with no backtracking and bail the moment an
 * offset leaves the packet.
 */
internal fun parseAsfPacket(data: ByteArray, length: Int): List<AsfPayload>? {
    if (length < MIN_PACKET_BYTES || length > data.size) return null

    var offset = 0

    // Error correction, where the file was written expecting to lose pieces of
    // itself in transit. The data is of no use to a reader of a whole file.
    val first = data[0].toInt() and 0xFF
    if (first and EC_PRESENT != 0) {
        val ecLength = first and EC_LENGTH_MASK
        offset += 1 + ecLength
        if (offset >= length) return null
    }

    val lengthTypeFlags = data.byteAt(offset, length) ?: return null
    offset++
    val propertyFlags = data.byteAt(offset, length) ?: return null
    offset++

    val multiplePayloads = lengthTypeFlags and LENGTH_MULTIPLE_PAYLOADS != 0
    val packetLengthWidth = fieldWidth((lengthTypeFlags shr 5) and 3)
    val paddingWidth = fieldWidth((lengthTypeFlags shr 3) and 3)
    val sequenceWidth = fieldWidth((lengthTypeFlags shr 1) and 3)

    val replicatedWidth = fieldWidth(propertyFlags and 3)
    val offsetWidth = fieldWidth((propertyFlags shr 2) and 3)
    val objectNumberWidth = fieldWidth((propertyFlags shr 4) and 3)
    val streamNumberWidth = fieldWidth((propertyFlags shr 6) and 3)

    // The stream number is the one field the format does not allow to be
    // absent, because a payload that does not say which stream it belongs to
    // belongs to none of them.
    if (streamNumberWidth != 1) return null

    val declaredPacketLength = data.readField(offset, packetLengthWidth, length) ?: return null
    offset += packetLengthWidth
    // Sequence is read only to be stepped over; nothing in an audio file uses it.
    data.readField(offset, sequenceWidth, length) ?: return null
    offset += sequenceWidth
    val padding = data.readField(offset, paddingWidth, length) ?: return null
    offset += paddingWidth

    // Send time and duration, which describe the packet rather than the audio
    // in it. The payloads carry their own times.
    if (offset + 6 > length) return null
    offset += 6

    // A packet may declare itself shorter than the fixed size it was written
    // at, in which case the rest is padding to be ignored.
    val packetEnd = when {
        packetLengthWidth > 0 && declaredPacketLength in MIN_PACKET_BYTES.toLong()..length.toLong() ->
            declaredPacketLength.toInt()
        else -> length
    }
    val payloadEnd = (packetEnd - padding).toInt()
    if (payloadEnd <= offset) return null

    var payloadCount = 1
    var payloadLengthWidth = 0
    if (multiplePayloads) {
        val payloadFlags = data.byteAt(offset, length) ?: return null
        offset++
        payloadCount = payloadFlags and PAYLOAD_COUNT_MASK
        payloadLengthWidth = fieldWidth((payloadFlags shr 6) and 3)
        if (payloadCount == 0) return emptyList()
    }

    val payloads = ArrayList<AsfPayload>(payloadCount)

    for (index in 0 until payloadCount) {
        val streamByte = data.byteAt(offset, payloadEnd) ?: return payloads
        offset++
        val streamNumber = streamByte and STREAM_NUMBER_MASK

        val objectNumber = data.readField(offset, objectNumberWidth, payloadEnd) ?: return payloads
        offset += objectNumberWidth
        val offsetIntoObject = data.readField(offset, offsetWidth, payloadEnd) ?: return payloads
        offset += offsetWidth
        val replicatedLength =
            (data.readField(offset, replicatedWidth, payloadEnd) ?: return payloads).toInt()
        offset += replicatedWidth

        if (replicatedLength < 0 || offset + replicatedLength > payloadEnd) return payloads

        if (replicatedLength >= REPLICATED_MINIMUM) {
            // The ordinary case: the replicated data opens with the size of the
            // whole media object and the time it is to be presented at, both
            // repeated in every fragment so that any one of them can be the
            // first a receiver sees.
            val mediaObjectSize = data.u32le(offset).toInt()
            val presentationTimeMs = data.u32le(offset + 4)
            offset += replicatedLength

            val payloadLength = if (multiplePayloads) {
                val declared = data.readField(offset, payloadLengthWidth, payloadEnd)
                    ?: return payloads
                offset += payloadLengthWidth
                declared.toInt()
            } else {
                payloadEnd - offset
            }
            if (payloadLength < 0 || offset + payloadLength > payloadEnd) return payloads

            payloads += AsfPayload(
                streamNumber = streamNumber,
                mediaObjectNumber = objectNumber.toInt(),
                offsetIntoMediaObject = offsetIntoObject.toInt(),
                mediaObjectSize = mediaObjectSize,
                presentationTimeMs = presentationTimeMs,
                dataOffset = offset,
                dataLength = payloadLength,
            )
            offset += payloadLength
        } else if (replicatedLength == 1) {
            // A compressed payload: several whole media objects packed into one
            // payload, each behind a single length byte. The two fields change
            // meaning here — what was the offset into the object is the
            // presentation time, and the one replicated byte is the interval
            // between the objects that follow.
            val presentationTimeMs = offsetIntoObject
            val timeDeltaMs = (data.byteAt(offset, payloadEnd) ?: return payloads).toLong()
            offset++

            val payloadLength = if (multiplePayloads) {
                val declared = data.readField(offset, payloadLengthWidth, payloadEnd)
                    ?: return payloads
                offset += payloadLengthWidth
                declared.toInt()
            } else {
                payloadEnd - offset
            }
            if (payloadLength < 0 || offset + payloadLength > payloadEnd) return payloads

            val end = offset + payloadLength
            var subIndex = 0
            while (offset < end) {
                val subLength = (data.byteAt(offset, end) ?: return payloads)
                offset++
                if (subLength == 0 || offset + subLength > end) break

                payloads += AsfPayload(
                    streamNumber = streamNumber,
                    // Each sub-payload is whole, so none of them is a fragment
                    // of anything and the object number is not needed to match
                    // them up. Numbering them keeps them distinct all the same.
                    mediaObjectNumber = objectNumber.toInt() + subIndex,
                    offsetIntoMediaObject = 0,
                    mediaObjectSize = subLength,
                    presentationTimeMs = presentationTimeMs + timeDeltaMs * subIndex,
                    dataOffset = offset,
                    dataLength = subLength,
                )
                offset += subLength
                subIndex++
            }
            offset = end
        } else {
            // Replicated data of any other length says nothing about the object
            // it belongs to, so there is no way to place its bytes.
            return payloads
        }

        if (index < payloadCount - 1 && offset >= payloadEnd) break
    }
    return payloads
}

/** The two-bit codes ASF names a field's width with. */
internal fun fieldWidth(code: Int): Int = when (code) {
    1 -> 1
    2 -> 2
    3 -> 4
    else -> 0
}

private const val MIN_PACKET_BYTES = 10

private const val EC_PRESENT = 0x80
private const val EC_LENGTH_MASK = 0x0F

private const val LENGTH_MULTIPLE_PAYLOADS = 0x01
private const val PAYLOAD_COUNT_MASK = 0x3F

/** Below this the replicated data cannot state a size and a time. */
private const val REPLICATED_MINIMUM = 8

private fun ByteArray.byteAt(offset: Int, limit: Int): Int? =
    if (offset < 0 || offset >= limit || offset >= size) null else this[offset].toInt() and 0xFF

/** Reads a field of 0, 1, 2 or 4 bytes; an absent field reads as zero. */
private fun ByteArray.readField(offset: Int, width: Int, limit: Int): Long? {
    if (width == 0) return 0L
    if (offset < 0 || offset + width > limit || offset + width > size) return null
    return when (width) {
        1 -> (this[offset].toLong() and 0xFF)
        2 -> u16le(offset).toLong()
        else -> u32le(offset)
    }
}

internal fun ByteArray.u64le(offset: Int): Long {
    if (offset + 8 > size) return 0L
    var value = 0L
    for (index in offset + 7 downTo offset) {
        value = (value shl 8) or (this[index].toLong() and 0xFF)
    }
    return value
}
