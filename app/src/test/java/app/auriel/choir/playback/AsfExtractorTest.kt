// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import androidx.media3.common.util.UnstableApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ASF files built by hand, a byte at a time.
 *
 * Nothing here comes out of an encoder. The objects are written from the
 * container specification and the packets from the payload-parsing rules, so a
 * test that passes says the reader agrees with the format rather than with
 * whatever produced a sample file.
 *
 * The packet builder takes the same flags a real writer chooses between —
 * whether a field is one byte or four, whether the packet holds one payload or
 * several — because those choices are exactly what an ASF reader gets wrong.
 * Reading a single field at the wrong width shifts every byte after it, and
 * nothing about the result looks wrong until it is played.
 */
@UnstableApi
class AsfExtractorTest {

    // --- What the file says it is --------------------------------------------

    @Test
    fun `publishes the format the stream properties describe`() {
        val file = asfFile(packets = listOf(packetOf(block(0, 40))))
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        val format = requireNotNull(output.track.format)
        assertEquals(ChoirMimeTypes.AUDIO_WMA, format.sampleMimeType)
        assertEquals(44_100, format.sampleRate)
        assertEquals(2, format.channelCount)
    }

    @Test
    fun `tells the codecs apart by their format tag, not by the extension`() {
        assertEquals(ChoirMimeTypes.AUDIO_WMA, wmaMimeType(0x0161))
        assertEquals(ChoirMimeTypes.AUDIO_WMA, wmaMimeType(0x0160))
        assertEquals(ChoirMimeTypes.AUDIO_WMA_PRO, wmaMimeType(0x0162))
        assertEquals(ChoirMimeTypes.AUDIO_WMA_LOSSLESS, wmaMimeType(0x0163))
        assertEquals(ChoirMimeTypes.AUDIO_WMA_VOICE, wmaMimeType(0x000A))
        assertNull(wmaMimeType(0x0055)) // MP3 in ASF, which nothing here decodes
    }

    @Test
    fun `carries the three fields the decoder refuses to open without`() {
        val file = asfFile(
            blockAlign = 2_973,
            bitsPerSample = 16,
            averageBytesPerSecond = 16_000,
            packets = listOf(packetOf(block(0, 40))),
        )
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        val context = requireNotNull(output.track.format).initializationData[1]
        val values = requireNotNull(ChoirCodecContext.decode(context))
        assertEquals(2_973, values.blockAlign)
        assertEquals(16, values.bitsPerCodedSample)
        assertEquals(16_000 * 8, values.bitRate)
    }

    @Test
    fun `hands on the tail of the wave format as codec extradata`() {
        val extra = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val file = asfFile(codecExtraData = extra, packets = listOf(packetOf(block(0, 40))))
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertArrayEquals(extra, requireNotNull(output.track.format).initializationData[0])
    }

    @Test
    fun `duration is the play time less the preroll nobody hears`() {
        val file = asfFile(
            playDurationMs = 10_000,
            prerollMs = 3_000,
            packets = listOf(packetOf(block(0, 40))),
        )
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(7_000_000L, requireNotNull(output.seekMap).durationUs)
    }

    @Test
    fun `declines an encrypted stream rather than decoding noise`() {
        val file = asfFile(encrypted = true, packets = listOf(packetOf(block(0, 40))))
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertNull(output.track.format)
        assertTrue(output.track.samples.isEmpty())
    }

    @Test
    fun `declines a codec it has no decoder for`() {
        val file = asfFile(formatTag = 0x0055, packets = listOf(packetOf(block(0, 40))))
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertNull(output.track.format)
    }

    // --- Blocks --------------------------------------------------------------

    @Test
    fun `a block that fits in one packet arrives whole`() {
        val audio = block(0, 40)
        val file = asfFile(packets = listOf(packetOf(audio)))
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(1, output.track.samples.size)
        assertArrayEquals(audio, output.track.samples.single().bytes)
    }

    @Test
    fun `a block split across two packets is sewn back together`() {
        val audio = block(0, 60)
        val file = asfFile(
            packets = listOf(
                packetOf(audio.copyOfRange(0, 40), mediaObjectSize = 60, offset = 0),
                packetOf(audio.copyOfRange(40, 60), mediaObjectSize = 60, offset = 40),
            ),
        )
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        // One sample, not two: the decoder is owed whole blocks.
        assertEquals(1, output.track.samples.size)
        assertArrayEquals(audio, output.track.samples.single().bytes)
    }

    @Test
    fun `a fragment whose opening piece was never seen is dropped`() {
        val audio = block(0, 60)
        val file = asfFile(
            packets = listOf(
                // Only the tail of the block, as the first packet after a seek
                // into the middle of one would be.
                packetOf(audio.copyOfRange(40, 60), mediaObjectSize = 60, offset = 40),
                packetOf(block(100, 40)),
            ),
        )
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(1, output.track.samples.size)
        assertArrayEquals(block(100, 40), output.track.samples.single().bytes)
    }

    @Test
    fun `payloads belonging to another stream are passed over`() {
        val file = asfFile(
            packets = listOf(
                packetOf(block(0, 40), streamNumber = 2),
                packetOf(block(50, 40), streamNumber = 1),
            ),
        )
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(1, output.track.samples.size)
        assertArrayEquals(block(50, 40), output.track.samples.single().bytes)
    }

    @Test
    fun `timestamps are stated ahead of the audio by the preroll`() {
        val file = asfFile(
            prerollMs = 3_000,
            packets = listOf(packetOf(block(0, 40), presentationTimeMs = 5_000)),
        )
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(2_000_000L, output.track.samples.single().timeUs)
    }

    @Test
    fun `a timestamp inside the preroll is clamped rather than made negative`() {
        val file = asfFile(
            prerollMs = 3_000,
            packets = listOf(packetOf(block(0, 40), presentationTimeMs = 1_000)),
        )
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(0L, output.track.samples.single().timeUs)
    }

    @Test
    fun `every block is a key frame, so the file can be seeked`() {
        val file = asfFile(packets = listOf(packetOf(block(0, 40))))
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertTrue(output.track.samples.single().isKeyFrame)
    }

    // --- Packets -------------------------------------------------------------

    @Test
    fun `reads several payloads out of one packet`() {
        val first = block(0, 20)
        val second = block(100, 20)
        val file = asfFile(packets = listOf(multiPayloadPacket(listOf(first, second))))

        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(2, output.track.samples.size)
        assertArrayEquals(first, output.track.samples[0].bytes)
        assertArrayEquals(second, output.track.samples[1].bytes)
    }

    @Test
    fun `steps over the error correction a streamed file was written with`() {
        val file = asfFile(packets = listOf(packetOf(block(0, 40), errorCorrection = true)))
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertArrayEquals(block(0, 40), output.track.samples.single().bytes)
    }

    @Test
    fun `unpacks a compressed payload into the blocks packed inside it`() {
        val first = block(0, 12)
        val second = block(50, 12)
        val file = asfFile(
            packets = listOf(
                compressedPayloadPacket(
                    listOf(first, second),
                    presentationTimeMs = 1_000,
                    timeDeltaMs = 20,
                ),
            ),
        )
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(2, output.track.samples.size)
        assertArrayEquals(first, output.track.samples[0].bytes)
        assertArrayEquals(second, output.track.samples[1].bytes)
        assertEquals(listOf(1_000_000L, 1_020_000L), output.track.samples.map { it.timeUs })
    }

    @Test
    fun `an unreadable packet costs one packet, not the rest of the file`() {
        val good = packetOf(block(50, 40))
        val rubbish = ByteArray(good.size) { 0xFF.toByte() }
        val file = asfFile(packets = listOf(rubbish, good))

        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertEquals(1, output.track.samples.size)
        assertArrayEquals(block(50, 40), output.track.samples.single().bytes)
    }

    @Test
    fun `field widths follow the two-bit codes the flags name them with`() {
        assertEquals(0, fieldWidth(0))
        assertEquals(1, fieldWidth(1))
        assertEquals(2, fieldWidth(2))
        assertEquals(4, fieldWidth(3))
    }

    // --- Seeking -------------------------------------------------------------

    @Test
    fun `seeking lands on a packet boundary`() {
        val packets = (0 until 8).map { packetOf(block(it * 10, 40), presentationTimeMs = it * 100L) }
        val file = asfFile(playDurationMs = 800, packets = packets)
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))
        val seekMap = requireNotNull(output.seekMap)

        val point = seekMap.getSeekPoints(400_000).first
        val fromStart = point.position - (file.size - packets.sumOf { it.size })
        assertEquals(0L, fromStart % PACKET_BYTES, "seek did not land on a packet")
    }

    @Test
    fun `a file with no duration cannot be seeked`() {
        val file = asfFile(playDurationMs = 0, packets = listOf(packetOf(block(0, 40))))
        val output = extractAll(AsfExtractor(), FakeExtractorInput(file))

        assertFalse(requireNotNull(output.seekMap).isSeekable)
    }

    // --- Recognising a file --------------------------------------------------

    @Test
    fun `sniffs a file that opens with the header object`() {
        val file = asfFile(packets = listOf(packetOf(block(0, 40))))
        assertTrue(AsfExtractor().sniff(FakeExtractorInput(file)))
    }

    @Test
    fun `declines a file that is not ASF`() {
        val notAsf = ByteArray(64) { 0x2A }
        assertFalse(AsfExtractor().sniff(FakeExtractorInput(notAsf)))
    }

    @Test
    fun `writes a GUID the mixed-endian way the format stores them`() {
        // The first three fields little-endian, the last eight bytes as they
        // are written. Anything else and no object in the file is recognised.
        assertArrayEquals(
            byteArrayOf(
                0x30, 0x26, 0xB2.toByte(), 0x75,
                0x8E.toByte(), 0x66,
                0xCF.toByte(), 0x11,
                0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(),
                0x00, 0x62, 0xCE.toByte(), 0x6C,
            ),
            asfGuid("75B22630-668E-11CF-A6D9-00AA0062CE6C"),
        )
    }
}

// --- Fixtures ----------------------------------------------------------------

/**
 * Big enough for the roomiest packet here — several payloads, each with its own
 * fifteen-byte header — with space left over to pad, which is what makes the
 * padding field worth writing at all.
 */
private const val PACKET_BYTES = 128
private const val AUDIO_STREAM = 1

/** Recognisable audio, so a reassembled block can be checked byte for byte. */
private fun block(seed: Int, size: Int): ByteArray = ByteArray(size) { (seed + it).toByte() }

private fun asfFile(
    formatTag: Int = 0x0161,
    channels: Int = 2,
    sampleRate: Int = 44_100,
    averageBytesPerSecond: Int = 16_000,
    blockAlign: Int = 2_973,
    bitsPerSample: Int = 16,
    codecExtraData: ByteArray = byteArrayOf(0x00, 0x00, 0x0F, 0x00),
    playDurationMs: Long = 10_000,
    prerollMs: Long = 0,
    encrypted: Boolean = false,
    packets: List<ByteArray>,
): ByteArray {
    val fileProperties = ByteArray(FILE_PROPERTIES_BYTES).also { data ->
        data.writeU64(32, packets.size.toLong()) // data packet count
        data.writeU64(40, playDurationMs * 10_000) // play duration, in 100ns
        data.writeU64(48, playDurationMs * 10_000) // send duration
        data.writeU64(56, prerollMs)
        data.writeU32(68, PACKET_BYTES.toLong()) // minimum packet size
        data.writeU32(72, PACKET_BYTES.toLong()) // maximum packet size
        data.writeU32(76, (averageBytesPerSecond * 8).toLong())
    }

    val waveFormat = ByteArray(WAVE_FORMAT_EX_BYTES + codecExtraData.size).also { data ->
        data.writeU16(0, formatTag)
        data.writeU16(2, channels)
        data.writeU32(4, sampleRate.toLong())
        data.writeU32(8, averageBytesPerSecond.toLong())
        data.writeU16(12, blockAlign)
        data.writeU16(14, bitsPerSample)
        data.writeU16(16, codecExtraData.size)
        codecExtraData.copyInto(data, WAVE_FORMAT_EX_BYTES)
    }

    val streamProperties = ByteArray(STREAM_PROPERTIES_BYTES + waveFormat.size).also { data ->
        ASF_AUDIO_MEDIA.copyInto(data, 0)
        // Error correction type, which nothing here reads.
        ASF_AUDIO_MEDIA.copyInto(data, GUID_BYTES)
        data.writeU32(40, waveFormat.size.toLong())
        data.writeU32(44, 0) // no error correction data
        data.writeU16(48, AUDIO_STREAM or if (encrypted) STREAM_FLAG_ENCRYPTED else 0)
        waveFormat.copyInto(data, STREAM_PROPERTIES_BYTES)
    }

    val children = headerChild(ASF_FILE_PROPERTIES, fileProperties) +
        headerChild(ASF_STREAM_PROPERTIES, streamProperties)

    val header = ByteArray(ASF_HEADER_PROLOGUE_BYTES + children.size).also { data ->
        ASF_HEADER_OBJECT.copyInto(data, 0)
        data.writeU64(GUID_BYTES, data.size.toLong())
        data.writeU32(GUID_BYTES + 8, 2) // two header objects
        data[GUID_BYTES + 12] = 0x01
        data[GUID_BYTES + 13] = 0x02
        children.copyInto(data, ASF_HEADER_PROLOGUE_BYTES)
    }

    val packetBytes = packets.fold(ByteArray(0)) { all, packet -> all + packet }
    val data = ByteArray(ASF_DATA_PROLOGUE_BYTES + packetBytes.size).also { bytes ->
        ASF_DATA_OBJECT.copyInto(bytes, 0)
        bytes.writeU64(GUID_BYTES, bytes.size.toLong())
        bytes.writeU64(GUID_BYTES + 8 + GUID_BYTES, packets.size.toLong())
        bytes[ASF_DATA_PROLOGUE_BYTES - 2] = 0x01
        bytes[ASF_DATA_PROLOGUE_BYTES - 1] = 0x01
        packetBytes.copyInto(bytes, ASF_DATA_PROLOGUE_BYTES)
    }

    return header + data
}

/** One child of the header object: its GUID, its total size, then its data. */
private fun headerChild(guid: ByteArray, data: ByteArray): ByteArray =
    ByteArray(GUID_BYTES + 8 + data.size).also { bytes ->
        guid.copyInto(bytes, 0)
        bytes.writeU64(GUID_BYTES, bytes.size.toLong())
        data.copyInto(bytes, GUID_BYTES + 8)
    }

/**
 * A packet holding one payload.
 *
 * The field widths chosen here are the ones a real writer picks: a one-byte
 * media object number that wraps, a four-byte offset because a media object may
 * be large, and eight bytes of replicated data — the size and the time, stated
 * again in every fragment so that any one of them can be the first a receiver
 * sees.
 */
private fun packetOf(
    audio: ByteArray,
    streamNumber: Int = AUDIO_STREAM,
    mediaObjectNumber: Int = 0,
    mediaObjectSize: Int = audio.size,
    offset: Int = 0,
    presentationTimeMs: Long = 0,
    errorCorrection: Boolean = false,
): ByteArray {
    val packet = ByteArray(PACKET_BYTES)
    var at = 0

    if (errorCorrection) {
        packet[at] = (0x80 or 2).toByte() // present, two bytes of it
        packet[at + 1] = 0
        packet[at + 2] = 0
        at += 3
    }

    packet[at] = 0x08 // one payload, a one-byte padding length
    packet[at + 1] = 0x5D // replicated 1, offset 4, object number 1, stream 1
    at += 2

    val paddingAt = at
    at += 1
    packet.writeU32(at, 0) // send time
    at += 4
    packet.writeU16(at, 0) // duration
    at += 2

    packet[at] = (streamNumber or 0x80).toByte() // key frame
    at += 1
    packet[at] = mediaObjectNumber.toByte()
    at += 1
    packet.writeU32(at, offset.toLong())
    at += 4
    packet[at] = 8 // replicated data length
    at += 1
    packet.writeU32(at, mediaObjectSize.toLong())
    packet.writeU32(at + 4, presentationTimeMs)
    at += 8

    audio.copyInto(packet, at)
    packet[paddingAt] = (PACKET_BYTES - at - audio.size).toByte()
    return packet
}

/** A packet holding several whole blocks, each with its own length. */
private fun multiPayloadPacket(
    blocks: List<ByteArray>,
    streamNumber: Int = AUDIO_STREAM,
    presentationTimeMs: Long = 0,
): ByteArray {
    val packet = ByteArray(PACKET_BYTES)
    var at = 0

    packet[at] = 0x09 // multiple payloads, a one-byte padding length
    packet[at + 1] = 0x5D
    at += 2

    val paddingAt = at
    at += 1
    packet.writeU32(at, 0)
    at += 4
    packet.writeU16(at, 0)
    at += 2

    packet[at] = (blocks.size or 0x80).toByte() // count, two-byte payload lengths
    at += 1

    blocks.forEachIndexed { index, audio ->
        packet[at] = (streamNumber or 0x80).toByte()
        at += 1
        packet[at] = index.toByte()
        at += 1
        packet.writeU32(at, 0) // offset into the media object
        at += 4
        packet[at] = 8
        at += 1
        packet.writeU32(at, audio.size.toLong())
        packet.writeU32(at + 4, presentationTimeMs)
        at += 8
        packet.writeU16(at, audio.size)
        at += 2
        audio.copyInto(packet, at)
        at += audio.size
    }

    packet[paddingAt] = (PACKET_BYTES - at).toByte()
    return packet
}

/**
 * A packet whose single payload holds several blocks behind length bytes.
 *
 * This is the form where two fields change meaning: what is otherwise the
 * offset into the media object is the presentation time, and the one byte of
 * replicated data is the interval between the blocks that follow.
 */
private fun compressedPayloadPacket(
    blocks: List<ByteArray>,
    streamNumber: Int = AUDIO_STREAM,
    presentationTimeMs: Long = 0,
    timeDeltaMs: Int = 0,
): ByteArray {
    val packet = ByteArray(PACKET_BYTES)
    var at = 0

    packet[at] = 0x08
    packet[at + 1] = 0x5D
    at += 2

    val paddingAt = at
    at += 1
    packet.writeU32(at, 0)
    at += 4
    packet.writeU16(at, 0)
    at += 2

    packet[at] = (streamNumber or 0x80).toByte()
    at += 1
    packet[at] = 0 // media object number
    at += 1
    packet.writeU32(at, presentationTimeMs) // the offset field, repurposed
    at += 4
    packet[at] = 1 // replicated data length of one: a compressed payload
    at += 1
    packet[at] = timeDeltaMs.toByte()
    at += 1

    var payloadBytes = 0
    for (audio in blocks) {
        packet[at] = audio.size.toByte()
        at += 1
        audio.copyInto(packet, at)
        at += audio.size
        payloadBytes += 1 + audio.size
    }

    packet[paddingAt] = (PACKET_BYTES - at).toByte()
    return packet
}

private fun ByteArray.writeU16(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.writeU32(offset: Int, value: Long) {
    for (index in 0 until 4) {
        this[offset + index] = ((value shr (8 * index)) and 0xFF).toByte()
    }
}

private fun ByteArray.writeU64(offset: Int, value: Long) {
    for (index in 0 until 8) {
        this[offset + index] = ((value shr (8 * index)) and 0xFF).toByte()
    }
}
